package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Getter
@Setter
public class ProxyContext {

    private static final int LOG_MAX_ERROR_LENGTH = 200;
    private static final Set<CharSequence> CORS_SAFE_LIST = Stream.of(
            HttpHeaders.CACHE_CONTROL,
            HttpHeaders.CONTENT_LANGUAGE,
            HttpHeaders.CONTENT_LENGTH,
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.EXPIRES,
            HttpHeaders.LAST_MODIFIED)
            .map(header -> header.toString().toLowerCase())
            .collect(Collectors.toUnmodifiableSet());

    private final Proxy proxy;
    private final Config config;
    // API key of root requester
    private final Key key;
    private final HttpServerRequest request;
    private final HttpServerResponse response;
    // OpenTelemetry trace ID
    private final String traceId;
    // API key data associated with the current request
    private final ApiKeyData apiKeyData;
    // OpenTelemetry span ID created by the Core
    private final String spanId;
    // OpenTelemetry parent span ID created by the Core
    private final String parentSpanId;
    // deployment name of the source(application/assistant/model) associated with the current request
    private final String sourceDeployment;

    private Deployment deployment;
    private String userSub;
    private List<String> userRoles;
    private String userProject;
    private String userHash;
    private TokenUsage tokenUsage;
    private Route route;
    private UpstreamRoute upstreamRoute;
    private HttpClientRequest proxyRequest;
    private Map<String, String> requestHeaders = Map.of();
    private HttpClientResponse proxyResponse;
    private Buffer requestBody;
    private Buffer responseBody;
    private BufferingReadStream responseStream; // received from origin
    private long requestTimestamp;
    private long requestBodyTimestamp;
    private long proxyConnectTimestamp;
    private long proxyResponseTimestamp;
    private long responseBodyTimestamp;
    private ExtractedClaims extractedClaims;
    private ApiKeyData proxyApiKeyData;
    // deployment triggers interceptors
    private String initialDeployment;
    private String initialDeploymentApi;
    // List of interceptors copied from the deployment config
    private List<String> interceptors;
    private boolean isStreamingRequest;
    private String traceOperation;
    // userName to be extracted from JWT or project name belongs to API key
    private String userDisplayName;

    public ProxyContext(Proxy proxy, Config config, HttpServerRequest request, ApiKeyData apiKeyData, ExtractedClaims extractedClaims, String traceId, String spanId) {
        this.proxy = proxy;
        this.config = config;
        this.apiKeyData = apiKeyData;
        this.request = request;
        this.response = request.response();
        this.requestTimestamp = System.currentTimeMillis();
        this.key = apiKeyData.getOriginalKey();
        if (apiKeyData.getPerRequestKey() != null) {
            initExtractedClaims(apiKeyData.getExtractedClaims(), apiKeyData.getOriginalKey());
            this.traceId = apiKeyData.getTraceId();
            this.parentSpanId = apiKeyData.getSpanId();
            this.sourceDeployment = apiKeyData.getSourceDeployment();
        } else {
            initExtractedClaims(extractedClaims, apiKeyData.getOriginalKey());
            this.traceId = traceId;
            this.parentSpanId = null;
            this.sourceDeployment = null;
        }
        this.spanId = spanId;
    }

    private void initExtractedClaims(ExtractedClaims extractedClaims, Key originalKey) {
        this.extractedClaims = extractedClaims;
        if (extractedClaims != null) {
            this.userRoles = extractedClaims.userRoles();
            this.userHash = extractedClaims.userHash();
            this.userSub = extractedClaims.sub();
            this.userProject = extractedClaims.project();
            this.userDisplayName = extractedClaims.userDisplayName();
        } else {
            this.userRoles = Objects.requireNonNull(originalKey, "API key must be provided if user claims are missed")
                    .getMergedRoles();
            this.userDisplayName = originalKey.getProject();
        }
    }

    public Future<?> respond(HttpStatus status) {
        return respond(status, null);
    }

    @SneakyThrows
    public Future<?> respond(HttpStatus status, Object object) {
        return respond(status, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON, object);
    }

    @SneakyThrows
    public Future<?> respond(HttpStatus status, String contentType, Object object) {
        String json = ProxyUtil.MAPPER.writeValueAsString(object);
        response.putHeader(HttpHeaders.CONTENT_TYPE, contentType);
        return respond(status, json);
    }

    public Future<?> respond(HttpStatus status, String body) {
        if (body == null) {
            body = "";
        }

        if (status != HttpStatus.OK) {
            log.warn("Responding with error. Project: {}. Trace: {}. Span: {}. Status: {}. Body: {}", getProject(), traceId, spanId, status,
                    body.length() > LOG_MAX_ERROR_LENGTH ? body.substring(0, LOG_MAX_ERROR_LENGTH) : body);
        }

        response.setStatusCode(status.getCode()).end(body);
        return Future.succeededFuture();
    }

    public Future<?> respond(Throwable error, String fallbackError) {
        return error instanceof HttpException exception
                ? respond(exception)
                : respond(HttpStatus.INTERNAL_SERVER_ERROR, fallbackError);
    }

    public Future<?> respond(HttpException exception) {
        response.headers().addAll(exception.getHeaders());
        return respond(exception.getStatus(), exception.getMessage());
    }

    public String getProject() {
        if (userProject != null) {
            return userProject;
        }
        return key == null ? null : key.getProject();
    }

    public boolean isSecuredApiKey() {
        return key != null && key.isSecured();
    }

    public List<String> getExecutionPath() {
        return proxyApiKeyData == null ? null : proxyApiKeyData.getExecutionPath();
    }

    public boolean getBooleanRequestQueryParam(String name) {
        return Boolean.parseBoolean(request.getParam(name, "false"));
    }

    public List<String> getInterceptors() {
        return interceptors == null ? apiKeyData.getInterceptors() : interceptors;
    }

    public boolean hasNextInterceptor() {
        // initial call to the deployment or the interceptor calls another deployment
        if (apiKeyData.getInterceptors() == null || !deployment.getName().equals(getInitialDeployment())) {
            return !deployment.getInterceptors().isEmpty();
        } else { // make sure if a next interceptor is available from the list
            return apiKeyData.getInterceptorIndex() + 1 < apiKeyData.getInterceptors().size();
        }
    }

    public String getInitialDeployment() {
        return initialDeployment == null ? apiKeyData.getInitialDeployment() : initialDeployment;
    }

    public String getInitialDeploymentApi() {
        return initialDeploymentApi == null ? apiKeyData.getInitialDeploymentApi() : initialDeploymentApi;
    }

    public ProxyContext putHeader(CharSequence name, String value) {
        response.putHeader(name, value);

        return this;
    }

    public ProxyContext exposeHeaders() {
        Set<String> headers = response.headers().names().stream()
                .filter(header -> {
                    String lowerCase = header.toLowerCase();
                    return !CORS_SAFE_LIST.contains(lowerCase)
                            // Exclude CORS headers
                            && !lowerCase.startsWith("access-control-");
                })
                .collect(Collectors.toUnmodifiableSet());
        if (!headers.isEmpty()) {
            response.putHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, String.join(", ", headers));
        }

        return this;
    }

    public String getRequestHeader(String name) {
        String value = request.getHeader(name);
        if (value != null) {
            return value;
        }
        return Optional.ofNullable(apiKeyData)
                .map(ApiKeyData::getHttpHeaders)
                .map(h -> h.get(name))
                .orElse(null);
    }
}
