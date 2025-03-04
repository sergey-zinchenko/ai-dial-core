package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.CollectRequestApplicationFilesFn;
import com.epam.aidial.core.server.function.CollectRequestAttachmentsFn;
import com.epam.aidial.core.server.function.CollectRequestDataFn;
import com.epam.aidial.core.server.function.CollectResponseAttachmentsFn;
import com.epam.aidial.core.server.function.enhancement.AppendApplicationPropertiesFn;
import com.epam.aidial.core.server.function.enhancement.ApplyDefaultDeploymentSettingsFn;
import com.epam.aidial.core.server.function.enhancement.EnhanceAssistantRequestFn;
import com.epam.aidial.core.server.function.enhancement.EnhanceModelRequestFn;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.token.TokenUsageParser;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.ModelCostCalculator;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
public class DeploymentPostController {

    private static final Set<Integer> DEFAULT_RETRIABLE_HTTP_CODES = Set.of(HttpStatus.TOO_MANY_REQUESTS.getCode(),
            HttpStatus.BAD_GATEWAY.getCode(), HttpStatus.GATEWAY_TIMEOUT.getCode(),
            HttpStatus.SERVICE_UNAVAILABLE.getCode());

    private final Proxy proxy;
    private final ProxyContext context;
    private final List<BaseRequestFunction<ObjectNode>> enhancementFunctions;

    public DeploymentPostController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
        this.enhancementFunctions = List.of(new CollectRequestAttachmentsFn(proxy, context),
                new CollectRequestDataFn(proxy, context),
                new ApplyDefaultDeploymentSettingsFn(proxy, context),
                new EnhanceAssistantRequestFn(proxy, context),
                new EnhanceModelRequestFn(proxy, context),
                new AppendApplicationPropertiesFn(proxy, context),
                new CollectRequestApplicationFilesFn(proxy, context));
    }

    public Future<?> handle(String deploymentId, String deploymentApi) {
        String contentType = context.getRequest().getHeader(HttpHeaders.CONTENT_TYPE);
        if (!StringUtils.containsIgnoreCase(contentType, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON)) {
            return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only application/json is supported");
        }
        // handle a special deployment `interceptor`
        if ("interceptor".equals(deploymentId)) {
            // move to next interceptor
            int nextIndex = context.getApiKeyData().getInterceptorIndex() + 1;
            return handleInterceptor(nextIndex);
        }
        return handleDeployment(deploymentId, deploymentApi);
    }

    private Future<?> handleDeployment(String deploymentId, String deploymentApi) {
        return DeploymentController.selectDeployment(context, deploymentId, false, true)
                .map(dep -> {
                    if (dep.getEndpoint() == null) {
                        throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE, "");
                    }

                    Features features = dep.getFeatures();
                    boolean isPerRequestKey = context.getApiKeyData().getPerRequestKey() != null;
                    if (features != null && Boolean.FALSE.equals(features.getAccessibleByPerRequestKey()) && isPerRequestKey) {
                        throw new PermissionDeniedException(String.format("Deployment %s is not accessible by %s", deploymentId, context.getApiKeyData().getSourceDeployment()));
                    }

                    context.setTraceOperation("Send request to %s deployment".formatted(dep.getName()));
                    context.setDeployment(dep);
                    return dep;
                })
                .compose(dep -> {
                    if (dep instanceof Model && !context.hasNextInterceptor()) {
                        return proxy.getRateLimiter().limit(context, dep);
                    } else {
                        return Future.succeededFuture(RateLimitResult.SUCCESS);
                    }
                })
                .compose(rateLimitResult -> {
                    Future<?> future;
                    if (rateLimitResult.status() == HttpStatus.OK) {
                        if (context.hasNextInterceptor()) {
                            context.setInitialDeployment(deploymentId);
                            context.setInitialDeploymentApi(deploymentApi);
                            context.setInterceptors(context.getDeployment().getInterceptors());
                            future = handleInterceptor(0);
                        } else {
                            future = handleRateLimitSuccess();
                        }
                    } else {
                        handleRateLimitHit(deploymentId, rateLimitResult);
                        future = Future.succeededFuture();
                    }
                    return future;
                })
                .otherwise(error -> {
                    handleRequestError(deploymentId, error);
                    return null;
                });
    }

    private Future<?> handleInterceptor(int interceptorIndex) {
        ApiKeyData apiKeyData = context.getApiKeyData();
        List<String> interceptors = context.getInterceptors();
        if (interceptorIndex < interceptors.size()) {
            String interceptorName = interceptors.get(interceptorIndex);
            Interceptor interceptor = context.getConfig().getInterceptors().get(interceptorName);
            if (interceptor == null) {
                log.warn("Interceptor is not found for the given name: {}", interceptorName);
                return respond(HttpStatus.NOT_FOUND, "Interceptor is not found");
            }
            context.setTraceOperation("Send request to %s interceptor".formatted(interceptorName));
            context.setDeployment(interceptor);
            ApiKeyData proxyApiKeyData = new ApiKeyData();
            proxyApiKeyData.setInterceptorIndex(interceptorIndex);
            proxyApiKeyData.setInterceptors(interceptors);
            proxyApiKeyData.setInitialDeployment(context.getInitialDeployment());
            proxyApiKeyData.setInitialDeploymentApi(context.getInitialDeploymentApi());
            setupProxyApiKeyData(proxyApiKeyData);

            InterceptorController controller = new InterceptorController(proxy, context);
            return controller.handle();
        } else { // all interceptors are completed we should call the initial deployment
            return handleDeployment(apiKeyData.getInitialDeployment(), apiKeyData.getInitialDeploymentApi());
        }
    }

    private void handleRequestError(String deploymentId, Throwable error) {
        if (error instanceof PermissionDeniedException) {
            log.error("Forbidden deployment {}. Project: {}. User sub: {}", deploymentId, context.getProject(), context.getUserSub());
            respond(HttpStatus.FORBIDDEN, error.getMessage());
        } else if (error instanceof ResourceNotFoundException) {
            log.error("Deployment not found {}", deploymentId, error);
            respond(HttpStatus.NOT_FOUND, error.getMessage());
        } else if (error instanceof HttpException e) {
            log.error("Deployment error {}", deploymentId, error);
            respond(e.getStatus(), e.getMessage());
        } else {
            log.error("Failed to handle deployment {}", deploymentId, error);
            respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process deployment: " + deploymentId);
        }
    }

    private Future<?> handleRateLimitSuccess() {
        log.info("Received request from client. Trace: {}. Span: {}. Project: {}. Deployment: {}. Headers: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                context.getRequest().headers().size());

        Deployment deployment = context.getDeployment();
        UpstreamRoute upstreamRoute = proxy.getUpstreamRouteProvider().get(deployment);
        if (!canRetry(upstreamRoute)) {
            return Future.succeededFuture();
        }
        context.setUpstreamRoute(upstreamRoute);

        setupProxyApiKeyData(new ApiKeyData());
        return proxy.getTokenStatsTracker().startSpan(context).map(ignore -> {
            context.getRequest().body()
                    .onSuccess(body -> proxy.getVertx().executeBlocking(() -> {
                        handleRequestBody(body);
                        return null;
                    }, false).onFailure(this::handleError))
                    .onFailure(this::handleRequestBodyError);
            return null;
        });
    }

    private void setupProxyApiKeyData(ApiKeyData proxyApiKeyData) {
        context.setProxyApiKeyData(proxyApiKeyData);
        ApiKeyData.initFromContext(proxyApiKeyData, context);
    }

    private void handleRateLimitHit(String deploymentId, RateLimitResult result) {
        // Returning an error similar to the Azure format.
        ErrorData rateLimitError = new ErrorData();
        rateLimitError.getError().setCode(String.valueOf(result.status().getCode()));
        rateLimitError.getError().setMessage(result.errorMessage());

        log.error("Rate limit error {}. Project: {}. User sub: {}. Deployment: {}. Trace: {}. Span: {}", result.errorMessage(),
                context.getProject(), context.getUserSub(), deploymentId, context.getTraceId(), context.getSpanId());

        String errorMessage = ProxyUtil.convertToString(rateLimitError);
        HttpException httpException;
        if (result.replyAfterSeconds() >= 0) {
            Map<String, String> headers = Map.of(HttpHeaders.RETRY_AFTER.toString(), Long.toString(result.replyAfterSeconds()));
            httpException = new HttpException(result.status(), errorMessage, headers);
        } else {
            httpException = new HttpException(result.status(), errorMessage);
        }

        respond(httpException);
    }

    private void handleError(Throwable error) {
        log.error("Can't handle request. Project: {}. User sub: {}. Trace: {}. Span: {}. Error: {}",
                context.getProject(), context.getUserSub(), context.getTraceId(), context.getSpanId(), error.getMessage());
        respond(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @SneakyThrows
    private void sendRequest() {
        UpstreamRoute route = context.getUpstreamRoute();
        HttpServerRequest request = context.getRequest();

        Upstream upstream = route.get();
        Objects.requireNonNull(upstream);

        String uri = buildUri(context);
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(uri)
                .setMethod(request.method())
                .setTraceOperation(context.getTraceOperation());

        proxy.getClient().request(options)
                .onSuccess(this::handleProxyRequest)
                .onFailure(this::handleProxyConnectionError);
    }

    @VisibleForTesting
    void handleRequestBody(Buffer requestBody) {
        Deployment deployment = context.getDeployment();
        log.info("Received body from client. Trace: {}. Span: {}. Project: {}. Deployment: {}. Length: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), deployment.getName(), requestBody.length());

        context.setRequestBody(requestBody);
        context.setRequestBodyTimestamp(System.currentTimeMillis());

        try (InputStream stream = new ByteBufInputStream(requestBody.getByteBuf())) {
            ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
            if (ProxyUtil.processChain(tree, enhancementFunctions)) {
                context.setRequestBody(Buffer.buffer(ProxyUtil.MAPPER.writeValueAsBytes(tree)));
            }
            proxy.getApiKeyStore().assignPerRequestApiKey(context.getProxyApiKeyData());
        } catch (Throwable e) {
            if (e instanceof HttpException httpException) {
                respond(httpException.getStatus(), httpException.getMessage());
            } else {
                respond(HttpStatus.BAD_REQUEST);
            }
            log.warn("Can't process JSON request body. Trace: {}. Span: {}. Error:",
                    context.getTraceId(), context.getSpanId(), e);
            return;
        }

        sendRequest();
    }

    /**
     * Called when proxy connected to the origin.
     */
    @VisibleForTesting
    void handleProxyRequest(HttpClientRequest proxyRequest) {
        log.info("Connected to origin. Trace: {}. Span: {}. Project: {}. Deployment: {}. Address: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                proxyRequest.connection().remoteAddress());

        HttpServerRequest request = context.getRequest();
        context.setProxyRequest(proxyRequest);
        context.setProxyConnectTimestamp(System.currentTimeMillis());

        Deployment deployment = context.getDeployment();
        MultiMap excludeHeaders = MultiMap.caseInsensitiveMultiMap();
        if (!deployment.isForwardAuthToken()) {
            excludeHeaders.add(HttpHeaders.AUTHORIZATION, "whatever");
        }

        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers(), excludeHeaders);

        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        proxyRequest.headers().add(Proxy.HEADER_API_KEY, proxyApiKeyData.getPerRequestKey());

        if (context.getDeployment() instanceof Model model && !model.getUpstreams().isEmpty()) {
            Upstream upstream = context.getUpstreamRoute().get();
            proxyRequest.putHeader(Proxy.HEADER_UPSTREAM_ENDPOINT, upstream.getEndpoint());
            proxyRequest.putHeader(Proxy.HEADER_UPSTREAM_KEY, upstream.getKey());
            proxyRequest.putHeader(Proxy.HEADER_UPSTREAM_EXTRA_DATA, upstream.getExtraData());
        }

        Buffer requestBody = context.getRequestBody();
        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(requestBody.length()));
        context.getRequestHeaders().forEach(proxyRequest::putHeader);

        proxyRequest.send(requestBody)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyResponseError);
    }

    /**
     * Called when proxy received the response headers from the origin.
     */
    private void handleProxyResponse(HttpClientResponse proxyResponse) {
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        Upstream currentUpstream = upstreamRoute.get();
        log.info("Received header from origin. Trace: {}. Span: {}. Project: {}. Deployment: {}. Endpoint: {}. Upstream: {}. Status: {}. Headers: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                context.getDeployment().getEndpoint(), currentUpstream == null ? "N/A" : currentUpstream.getEndpoint(),
                proxyResponse.statusCode(), proxyResponse.headers().size());

        int responseStatusCode = proxyResponse.statusCode();
        if (isRetriableError(responseStatusCode)) {
            upstreamRoute.fail(proxyResponse);
            // get next upstream
            if (canRetry(upstreamRoute)) {
                sendRequest(); // try next
            }
            return;
        }

        if (responseStatusCode == 200) {
            upstreamRoute.succeed();
        } else {
            // mark the upstream as failed
            // and the next time we will select another one
            upstreamRoute.fail(proxyResponse);
        }

        CollectResponseAttachmentsFn handler = context.isStreamingRequest() ? new CollectResponseAttachmentsFn(proxy, context) : null;

        BufferingReadStream responseStream = new BufferingReadStream(proxyResponse,
                ProxyUtil.contentLength(proxyResponse, 1024), handler);

        context.setProxyResponse(proxyResponse);
        context.setProxyResponseTimestamp(System.currentTimeMillis());
        context.setResponseStream(responseStream);

        HttpServerResponse response = context.getResponse();

        response.setChunked(true);
        response.setStatusCode(proxyResponse.statusCode());

        ProxyUtil.copyHeaders(proxyResponse.headers(), response.headers());
        response.putHeader(Proxy.HEADER_UPSTREAM_ATTEMPTS, Integer.toString(upstreamRoute.getAttemptCount()));

        responseStream.pipe()
                .endOnFailure(false)
                .endOnSuccess(false)
                .to(response)
                .onSuccess(ignored -> handleResponse(responseStream))
                .onFailure(this::handleResponseError);
    }

    private boolean isRetriableError(int statusCode) {
        return DEFAULT_RETRIABLE_HTTP_CODES.contains(statusCode) || context.getConfig().getRetriableErrorCodes().contains(statusCode);
    }

    /**
     * Called when proxy sent response from the origin to the client.
     */
    @VisibleForTesting
    void handleResponse(BufferingReadStream responseStream) {
        Buffer responseBody = context.getResponseStream().getContent();
        context.setResponseBody(responseBody);
        context.setResponseBodyTimestamp(System.currentTimeMillis());
        Future<TokenUsage> tokenUsageFuture = collectTokenUsage(responseBody);

        Future<Void> handleResponseFuture = tokenUsageFuture.transform(result -> {
            if (result.failed()) {
                log.warn("Failed to collect token usage. Trace: {}. Span: {}",
                        context.getTraceId(), context.getSpanId(), result.cause());
            }
            return collectResponseAttachments(responseBody);
        });

        handleResponseFuture.onComplete(result -> {
            if (result.failed()) {
                log.warn("Failed to collect attachments from response. Trace: {}. Span: {}",
                        context.getTraceId(), context.getSpanId(), result.cause());
            }
            completeProxyResponse(responseStream);
        });
    }

    private Future<TokenUsage> collectTokenUsage(Buffer responseBody) {
        Future<TokenUsage> tokenUsageFuture = Future.succeededFuture();
        if (context.getDeployment() instanceof Model model) {
            if (context.getResponse().getStatusCode() == HttpStatus.OK.getCode()) {
                TokenUsage tokenUsage = TokenUsageParser.parse(responseBody);
                if (tokenUsage == null) {
                    Pricing pricing = model.getPricing();
                    if (pricing == null || "token".equals(pricing.getUnit())) {
                        log.warn("Can't find token usage. Trace: {}. Span: {}. Project: {}. Deployment: {}. Endpoint: {}. Upstream: {}. Status: {}. Length: {}",
                                context.getTraceId(), context.getSpanId(),
                                context.getProject(), context.getDeployment().getName(),
                                context.getDeployment().getEndpoint(),
                                context.getUpstreamRoute().get().getEndpoint(),
                                context.getResponse().getStatusCode(),
                                context.getResponseBody().length());
                    }
                    tokenUsage = new TokenUsage();
                }
                context.setTokenUsage(tokenUsage);
                proxy.getRateLimiter().increase(context, context.getDeployment()).onFailure(error -> log.warn("Failed to increase limit. Trace: {}. Span: {}",
                        context.getTraceId(), context.getSpanId(), error));
                try {
                    BigDecimal cost = ModelCostCalculator.calculate(context);
                    tokenUsage.setCost(cost);
                    tokenUsage.setAggCost(cost);
                } catch (Throwable e) {
                    log.warn("Failed to calculate cost for model={}. Trace: {}. Span: {}",
                            context.getDeployment().getName(), context.getTraceId(), context.getSpanId(), e);
                }
                tokenUsageFuture = proxy.getTokenStatsTracker().updateModelStats(context);
            }
        } else {
            tokenUsageFuture = proxy.getTokenStatsTracker().getTokenStats(context).andThen(result -> context.setTokenUsage(result.result()));
        }
        return tokenUsageFuture;
    }

    private Future<Void> collectResponseAttachments(Buffer responseBody) {
        if (context.isStreamingRequest()) {
            return Future.succeededFuture();
        }
        try (InputStream stream = new ByteBufInputStream(responseBody.getByteBuf())) {
            ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
            var fn = new CollectResponseAttachmentsFn(proxy, context);
            return fn.apply(tree);
        } catch (IOException e) {
            log.warn("Can't parse JSON response body. Trace: {}. Span: {}. Error:",
                    context.getTraceId(), context.getSpanId(), e);
            return Future.failedFuture(e);
        }
    }

    private void completeProxyResponse(BufferingReadStream responseStream) {
        HttpServerResponse response = context.getResponse();
        responseStream.end(response);

        proxy.getLogStore().save(context);

        log.info("Sent response to client. Trace: {}. Span: {}. Project: {}. Deployment: {}. Endpoint: {}. Upstream: {}. Status: {}. Length: {}."
                        + " Timing: {} (body={}, connect={}, header={}, body={}). Tokens: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                context.getDeployment().getEndpoint(),
                context.getUpstreamRoute().get().getEndpoint(),
                context.getResponse().getStatusCode(),
                context.getResponseBody().length(),
                context.getResponseBodyTimestamp() - context.getRequestTimestamp(),
                context.getRequestBodyTimestamp() - context.getRequestTimestamp(),
                context.getProxyConnectTimestamp() - context.getRequestBodyTimestamp(),
                context.getProxyResponseTimestamp() - context.getProxyConnectTimestamp(),
                context.getResponseBodyTimestamp() - context.getProxyResponseTimestamp(),
                context.getTokenUsage() == null ? "n/a" : context.getTokenUsage());

        finalizeRequest();
    }

    /**
     * Called when proxy failed to receive request body from the client.
     */
    private void handleRequestBodyError(Throwable error) {
        log.warn("Failed to receive client body. Trace: {}. Span: {}. Error: {}",
                context.getTraceId(), context.getSpanId(), error.getMessage());

        respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
    }

    /**
     * Called when proxy failed to connect to the origin.
     */
    private void handleProxyConnectionError(Throwable error) {
        log.warn("Can't connect to origin. Trace: {}. Span: {}. Project: {}. Deployment: {}. Address: {}. Error: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                buildUri(context), error.getMessage());

        respond(HttpStatus.BAD_GATEWAY, "Failed to connect to origin");
    }

    /**
     * Called when proxy failed to receive response header from origin.
     */
    private void handleProxyResponseError(Throwable error) {
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        log.warn("Proxy failed to receive response header from origin. Trace: {}. Span: {}. Project: {}. Deployment: {}. Address: {}. Error:",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                context.getProxyRequest().connection().remoteAddress(),
                error);

        // for 5xx errors we use exponential backoff strategy, so passing retryAfterSeconds parameter makes no sense
        upstreamRoute.fail(HttpStatus.BAD_GATEWAY);
        if (canRetry(upstreamRoute)) {
            sendRequest(); // try next
        }
    }

    /**
     * Called when proxy failed to send response to the client.
     */
    private void handleResponseError(Throwable error) {
        log.warn("Can't send response to client. Trace: {}. Span: {}. Error:",
                context.getTraceId(), context.getSpanId(), error);

        context.getProxyRequest().reset(); // drop connection to stop origin response
        context.getResponse().reset();     // drop connection, so that partial client response won't seem complete
        finalizeRequest();
    }

    private static boolean isValidDeploymentApi(Deployment deployment, String deploymentApi) {
        ModelType type = switch (deploymentApi) {
            case "completions" -> ModelType.COMPLETION;
            case "chat/completions" -> ModelType.CHAT;
            case "embeddings" -> ModelType.EMBEDDING;
            default -> null;
        };

        if (type == null) {
            return false;
        }

        // Models support all APIs
        if (deployment instanceof Model model) {
            return type == model.getType();
        }

        // Assistants and applications only support chat API
        return type == ModelType.CHAT;
    }

    private static String buildUri(ProxyContext context) {
        HttpServerRequest request = context.getRequest();
        Deployment deployment = context.getDeployment();
        String endpoint = deployment.getEndpoint();
        String query = request.query();
        return endpoint + (query == null ? "" : "?" + query);
    }

    private boolean canRetry(UpstreamRoute route) {
        try {
            route.next();
        } catch (HttpException e) {
            log.error("No route. Trace: {}. Span: {}. Project: {}. Deployment: {}. User sub: {}",
                    context.getTraceId(), context.getSpanId(),
                    context.getProject(), context.getDeployment().getName(), context.getUserSub());
            respond(e);
            return false;
        }
        return true;
    }

    private Future<?> respond(HttpStatus status, String errorMessage) {
        finalizeRequest();
        return context.respond(status, errorMessage);
    }

    private void respond(HttpException exception) {
        finalizeRequest();
        context.respond(exception);
    }

    private void respond(HttpStatus status) {
        finalizeRequest();
        context.respond(status);
    }

    private void respond(HttpStatus status, Object result) {
        finalizeRequest();
        context.respond(status, result);
    }

    private void finalizeRequest() {
        proxy.getTokenStatsTracker().endSpan(context).onFailure(error -> log.error("Error occurred at completing span", error));
        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        if (proxyApiKeyData != null) {
            proxy.getApiKeyStore().invalidatePerRequestApiKey(proxyApiKeyData)
                    .onSuccess(invalidated -> {
                        if (!invalidated) {
                            log.warn("Per request is not removed: {}", proxyApiKeyData.getPerRequestKey());
                        }
                    }).onFailure(error -> log.error("error occurred on invalidating per-request key", error));
        }
    }
}
