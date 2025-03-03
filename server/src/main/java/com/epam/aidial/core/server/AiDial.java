package com.epam.aidial.core.server;

import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.config.FileConfigStore;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.GfLogStore;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.AccessTokenValidator;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationOperatorService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.HeartbeatService;
import com.epam.aidial.core.server.service.InvitationService;
import com.epam.aidial.core.server.service.NotificationService;
import com.epam.aidial.core.server.service.PublicationService;
import com.epam.aidial.core.server.service.ResourceOperationService;
import com.epam.aidial.core.server.service.RuleService;
import com.epam.aidial.core.server.service.ShareService;
import com.epam.aidial.core.server.service.VertxTimerService;
import com.epam.aidial.core.server.service.codeinterpreter.CodeInterpreterService;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.tracing.DialTracingFactory;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.cache.CacheClientFactory;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TimerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.Clock;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.vertx.config.spi.utils.JsonObjectHelper;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.metrics.MetricsOptions;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Slf4j
@Setter
@Getter
public class AiDial {

    private JsonObject settings;
    private Vertx vertx;
    private HttpServer server;
    private HttpClient client;

    private RedissonClient redis;
    private Proxy proxy;

    private AccessTokenValidator accessTokenValidator;

    private BlobStorage storage;
    private ResourceService resourceService;

    private LongSupplier clock = System::currentTimeMillis;
    private Supplier<String> generator = () -> UUID.randomUUID().toString().replace("-", "");

    @VisibleForTesting
    void start() throws Exception {
        System.setProperty("io.opentelemetry.context.contextStorageProvider", "io.vertx.tracing.opentelemetry.VertxContextStorageProvider");
        try {
            settings = (settings == null) ? settings() : settings;
            VertxOptions vertxOptions = new VertxOptions(settings("vertx"));
            setupMetrics(vertxOptions);
            setupTracing(vertxOptions);

            vertx = Vertx.vertx(vertxOptions);
            client = vertx.createHttpClient(new HttpClientOptions(settings("client")));

            LogStore logStore = new GfLogStore(vertx);
            UpstreamRouteProvider upstreamRouteProvider = new UpstreamRouteProvider(vertx, Random::new);

            if (accessTokenValidator == null) {
                accessTokenValidator = new AccessTokenValidator(settings("identityProviders"), vertx, client);
            }

            if (storage == null) {
                Storage storageConfig = Json.decodeValue(settings("storage").toBuffer(), Storage.class);
                storage = new BlobStorage(storageConfig);
            }
            EncryptionService encryptionService = new EncryptionService(settings("encryption"));

            redis = CacheClientFactory.create(toJsonNode(settings("redis")));

            LockService lockService = new LockService(redis, storage.getPrefix());
            TimerService timerService = new VertxTimerService(vertx);
            ResourceService.Settings resourceServiceSettings = Json.decodeValue(settings("resources").toBuffer(), ResourceService.Settings.class);
            resourceService = new ResourceService(timerService, redis, storage, lockService, resourceServiceSettings, storage.getPrefix());
            InvitationService invitationService = new InvitationService(resourceService, encryptionService, settings("invitations"));
            ApiKeyStore apiKeyStore = new ApiKeyStore(resourceService, vertx);
            ConfigStore configStore = new FileConfigStore(vertx, settings("config"), apiKeyStore);
            ApplicationOperatorService operatorService = new ApplicationOperatorService(client, settings("applications"));
            ApplicationService applicationService = new ApplicationService(vertx, redis, encryptionService,
                    resourceService, lockService, operatorService, generator, settings("applications"));
            ShareService shareService = new ShareService(resourceService, invitationService, encryptionService, applicationService, configStore);
            RuleService ruleService = new RuleService(resourceService);
            AccessService accessService = new AccessService(encryptionService, shareService, ruleService, settings("access"));
            NotificationService notificationService = new NotificationService(resourceService, encryptionService);
            ResourceOperationService resourceOperationService = new ResourceOperationService(applicationService,
                    resourceService, invitationService, shareService, lockService);
            PublicationService publicationService = new PublicationService(encryptionService, resourceService, accessService,
                    ruleService, notificationService, applicationService, resourceOperationService, generator, clock);
            RateLimiter rateLimiter = new RateLimiter(vertx, resourceService);
            CodeInterpreterService codeInterpreterService = new CodeInterpreterService(vertx, redis, resourceService,
                    accessService, encryptionService, operatorService, generator, settings("codeInterpreter"));

            TokenStatsTracker tokenStatsTracker = new TokenStatsTracker(vertx, resourceService);

            HeartbeatService heartbeatService = new HeartbeatService(
                    vertx, settings("resources").getLong("heartbeatPeriod"));
            proxy = new Proxy(vertx, client, configStore, logStore,
                    rateLimiter, upstreamRouteProvider, accessTokenValidator,
                    storage, encryptionService, apiKeyStore, tokenStatsTracker, resourceService, invitationService,
                    shareService, publicationService, accessService, lockService, resourceOperationService, ruleService,
                    notificationService, applicationService, codeInterpreterService, heartbeatService, version());

            server = vertx.createHttpServer(new HttpServerOptions(settings("server"))).requestHandler(proxy);
            open(server, HttpServer::listen);
            log.info("Proxy started on {}", server.actualPort());
        } catch (Throwable e) {
            log.error("Proxy failed to start:", e);
            stop();
            throw e;
        }
    }

    @VisibleForTesting
    void stop() throws Exception {
        try {
            close(server, HttpServer::close);
            close(client, HttpClient::close);
            close(resourceService);
            close(vertx, Vertx::close);
            close(storage);
            close(redis);
            log.info("Proxy stopped");
        } catch (Throwable e) {
            log.warn("Proxy failed to stop:", e);
            throw e;
        }
    }

    @SneakyThrows
    private static JsonNode toJsonNode(JsonObject jsonObject) {
        return ProxyUtil.MAPPER.readTree(jsonObject.encode());
    }

    public static JsonObject settings() throws Exception {
        return defaultSettings()
                .mergeIn(fileSettings(), true)
                .mergeIn(envSettings(), true);
    }

    private JsonObject settings(String key) {
        return settings.getJsonObject(key, new JsonObject());
    }

    private static JsonObject defaultSettings() throws IOException {
        String file = "aidial.settings.json";

        try (InputStream stream = AiDial.class.getClassLoader().getResourceAsStream(file)) {
            Objects.requireNonNull(stream, "Default resource file with settings is not found");
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new JsonObject(json);
        }
    }

    private static String version() {
        String filename = "version";
        String version = "undefined";

        try (InputStream stream = AiDial.class.getClassLoader().getResourceAsStream(filename)) {
            Objects.requireNonNull(stream, "Version file not found");
            version = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to load version", e);
        }
        return version;
    }

    private static JsonObject fileSettings() throws IOException {
        String file = System.getenv().get("AIDIAL_SETTINGS");
        if (file == null) {
            return new JsonObject();
        }

        try (InputStream stream = new FileInputStream(file)) {
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new JsonObject(json);
        }
    }

    private static JsonObject envSettings() {
        String[] prefixes = {"aidial.", "proxy."}; // "proxy." is deprecated
        Properties properties = new Properties();

        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            for (String prefix : prefixes) {
                if (key.startsWith(prefix)) {
                    String suffix = key.substring(prefix.length());
                    properties.put(suffix, value);
                    break;
                }
            }
        }

        return JsonObjectHelper.from(properties, false, true);
    }

    private static <R> void open(R resource, AsyncOpener<R> opener) throws Exception {
        CompletableFuture<R> startup = new CompletableFuture<>();
        opener.open(resource).onSuccess(startup::complete).onFailure(startup::completeExceptionally);
        startup.get(15, TimeUnit.SECONDS);
    }

    private static <R> void close(R resource, AsyncCloser<R> closer) throws Exception {
        if (resource != null) {
            CompletableFuture<Void> shutdown = new CompletableFuture<>();
            closer.close(resource).onSuccess(shutdown::complete).onFailure(shutdown::completeExceptionally);
            shutdown.get(15, TimeUnit.SECONDS);
        }
    }

    private static void close(AutoCloseable resource) throws Exception {
        if (resource != null) {
            resource.close();
        }
    }

    private static void close(RedissonClient resource) {
        if (resource != null) {
            resource.shutdown();
        }
    }

    private interface AsyncOpener<R> {
        Future<R> open(R resource);
    }

    private interface AsyncCloser<R> {
        Future<Void> close(R resource);
    }

    public static void main(String[] args) {
        AiDial dial = new AiDial();
        try {
            dial.start();
        } catch (Throwable e) {
            System.exit(-1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                dial.stop();
            } catch (Throwable e) {
                System.exit(-1);
            }
        }, "shutdown-hook"));
    }

    private static void setupMetrics(VertxOptions options) {
        MetricsOptions metrics = options.getMetricsOptions();
        if (metrics == null || !metrics.isEnabled()) {
            return;
        }

        JsonObject oltp = metrics.toJson().getJsonObject("oltpOptions", new JsonObject());
        if (oltp == null || !oltp.getBoolean("enabled", false)) {
            return;
        }

        MicrometerMetricsOptions micrometer = new MicrometerMetricsOptions(metrics.toJson());
        micrometer.setMicrometerRegistry(new OtlpMeterRegistry(oltp::getString, Clock.SYSTEM));

        options.setMetricsOptions(micrometer);
    }

    private static void setupTracing(VertxOptions vertxOptions) {
        String otlMetricExporter = getOtlSetting("OTEL_METRICS_EXPORTER", "otel.metrics.exporter");
        if (otlMetricExporter == null) {
            System.setProperty("otel.metrics.exporter", "none");
        }
        String otlLogsExporter = getOtlSetting("OTEL_LOGS_EXPORTER", "otel.logs.exporter");
        if (otlLogsExporter == null) {
            System.setProperty("otel.logs.exporter", "none");
        }
        // disable trace exporter if the endpoint is not provided explicitly
        String otlExporterEndpoint = getOtlSetting("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "otel.exporter.otlp.traces.endpoint");
        if (otlExporterEndpoint == null) {
            System.setProperty("otel.traces.exporter", "none");
        }
        OpenTelemetry openTelemetry = AutoConfiguredOpenTelemetrySdk.builder().build().getOpenTelemetrySdk();
        OpenTelemetryOptions otelOpts = new OpenTelemetryOptions(openTelemetry);
        otelOpts.setFactory(new DialTracingFactory(otelOpts.getFactory()));
        vertxOptions.setTracingOptions(otelOpts);
    }

    private static String getOtlSetting(String envVar, String systemProperty) {
        String val = System.getenv(envVar);
        if (val != null) {
            return val;
        }
        return System.getProperty(systemProperty);
    }
}
