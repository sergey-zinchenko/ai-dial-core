package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.limiter.RateLimitResult;
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
import io.vertx.core.http.RequestOptions;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.client.utils.URLEncodedUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class RouteController implements Controller {

    private final Proxy proxy;
    private final ProxyContext context;

    @Override
    public Future<?> handle() {
        Route route = selectRoute();
        if (route == null) {
            log.warn("RouteController can't find a route to proceed the request: {}", getRequestUri());
            respond(HttpStatus.BAD_GATEWAY, "No route");
            return Future.succeededFuture();
        }

        if (!route.hasAccess(context.getUserRoles())) {
            log.error("Forbidden route {}. Trace: {}. Span: {}. Project: {}. User sub: {}.",
                    route.getName(), context.getTraceId(), context.getSpanId(), context.getProject(), context.getUserSub());
            respond(HttpStatus.FORBIDDEN, "Forbidden route");
            return Future.succeededFuture();
        }

        Route.Response response = route.getResponse();
        if (response == null) {
            UpstreamRoute upstreamRoute = proxy.getUpstreamRouteProvider().get(route);
            if (!canRetry(upstreamRoute)) {
                return Future.succeededFuture();
            }
            context.setTraceOperation("Send request to %s route".formatted(route.getName()));
            context.setUpstreamRoute(upstreamRoute);
        } else {
            context.getResponse().setStatusCode(response.getStatus());
            context.setResponseBody(Buffer.buffer(response.getBody()));
        }
        context.setRoute(route);

        context.getRequest().body()
                .onSuccess(this::handleRequestBody)
                .onFailure(this::handleRequestBodyError);
        return Future.succeededFuture();
    }

    String getRequestUri() {
        HttpServerRequest request = context.getRequest();
        return request.uri();
    }

    @SneakyThrows
    private Future<?> sendRequest() {
        UpstreamRoute route = context.getUpstreamRoute();
        HttpServerRequest request = context.getRequest();

        Upstream upstream = route.get();
        Objects.requireNonNull(upstream);
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(getEndpointUri(upstream))
                .setMethod(request.method())
                .setTraceOperation(context.getTraceOperation());

        return proxy.getClient().request(options)
                .onSuccess(this::handleProxyRequest)
                .onFailure(this::handleProxyConnectionError);
    }

    private void handleRequestBody(Buffer requestBody) {
        context.setRequestBody(requestBody);

        if (context.getResponseBody() == null) {
            proxy.getRateLimiter().limit(context, context.getRoute())
                    .compose(rateLimitResult -> {
                        if (rateLimitResult.status() == HttpStatus.OK) {
                            setupProxyApiKeyData();
                            return sendRequest();
                        } else {
                            handleRateLimitHit(rateLimitResult);
                            return Future.succeededFuture();
                        }
                    })
                    .onFailure(this::handleError);
        } else {
            context.getResponse().send(context.getResponseBody());
            proxy.getLogStore().save(context);
        }
    }

    private void setupProxyApiKeyData() {
        Upstream upstream = context.getUpstreamRoute().get();
        if (upstream != null && upstream.getKey() != null) {
            return;
        }
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        context.setProxyApiKeyData(proxyApiKeyData);
        ApiKeyData.initFromContext(proxyApiKeyData, context);
        proxy.getApiKeyStore().assignPerRequestApiKey(proxyApiKeyData);
    }

    /**
     * Called when proxy connected to the origin.
     */
    private void handleProxyRequest(HttpClientRequest proxyRequest) {
        log.info("Connected to origin: {}", proxyRequest.connection().remoteAddress());

        HttpServerRequest request = context.getRequest();
        context.setProxyRequest(proxyRequest);

        Upstream upstream = context.getUpstreamRoute().get();
        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers());
        if (upstream != null && upstream.getKey() != null) {
            proxyRequest.putHeader(Proxy.HEADER_API_KEY, upstream.getKey());
        } else {
            ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
            proxyRequest.headers().add(Proxy.HEADER_API_KEY, proxyApiKeyData.getPerRequestKey());
        }

        Buffer proxyRequestBody = context.getRequestBody();
        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(proxyRequestBody.length()));

        proxyRequest.send(proxyRequestBody)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyRequestError);
    }

    /**
     * Called when proxy received the response headers from the origin.
     */
    private void handleProxyResponse(HttpClientResponse proxyResponse) {
        int responseStatusCode = proxyResponse.statusCode();
        log.info("Received response header from origin: status={}, headers={}", responseStatusCode,
                proxyResponse.headers().size());

        if (responseStatusCode == HttpStatus.TOO_MANY_REQUESTS.getCode()) {
            UpstreamRoute upstreamRoute = context.getUpstreamRoute();
            upstreamRoute.fail(proxyResponse);
            // get next upstream
            if (canRetry(upstreamRoute)) {
                sendRequest(); // try next
            }
            return;
        }

        if (responseStatusCode == 200) {
            context.getUpstreamRoute().succeed();
            proxy.getRateLimiter().increase(context, context.getRoute()).onFailure(error -> log.warn("Failed to increase limit. Trace: {}. Span: {}",
                    context.getTraceId(), context.getSpanId(), error));
        }

        BufferingReadStream proxyResponseStream = new BufferingReadStream(proxyResponse,
                ProxyUtil.contentLength(proxyResponse, 1024));

        context.setProxyResponse(proxyResponse);
        context.setResponseStream(proxyResponseStream);

        HttpServerResponse response = context.getResponse();
        response.setChunked(true);
        response.setStatusCode(responseStatusCode);
        ProxyUtil.copyHeaders(proxyResponse.headers(), response.headers());

        proxyResponseStream.pipe()
                .endOnFailure(false)
                .to(response)
                .onSuccess(ignored -> handleResponse())
                .onFailure(this::handleResponseError);
    }

    private boolean canRetry(UpstreamRoute route) {
        try {
            route.next();
        } catch (HttpException e) {
            respond(e);
            return false;
        }
        return true;
    }

    /**
     * Called when proxy sent response from the origin to the client.
     */
    private void handleResponse() {
        Buffer proxyResponseBody = context.getResponseStream().getContent();
        context.setResponseBody(proxyResponseBody);
        proxy.getLogStore().save(context);
    }

    private void handleRateLimitHit(RateLimitResult result) {
        ErrorData rateLimitError = new ErrorData();
        rateLimitError.getError().setCode(String.valueOf(result.status().getCode()));
        rateLimitError.getError().setMessage(result.errorMessage());

        log.error("Rate limit error {}. Project: {}. User sub: {}. Route: {}. Trace: {}. Span: {}", result.errorMessage(),
                context.getProject(), context.getUserSub(), context.getRoute().getName(), context.getTraceId(),
                context.getSpanId());

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
        String route = context.getRoute().getName();
        log.error("Failed to handle route {}", route, error);
        respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process route request: " + route);
    }

    /**
     * Called when proxy failed to receive request body from the client.
     */
    private void handleRequestBodyError(Throwable error) {
        log.warn("Failed to receive client body: {}", error.getMessage());
        respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
    }

    /**
     * Called when proxy failed to connect to the origin.
     */
    private void handleProxyConnectionError(Throwable error) {
        log.warn("Can't connect to origin: {}", error.getMessage());
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        // for 5xx errors we use exponential backoff strategy, so passing retryAfterSeconds parameter makes no sense
        upstreamRoute.fail(HttpStatus.BAD_GATEWAY);
        // get next upstream
        if (canRetry(upstreamRoute)) {
            sendRequest(); // try next
        }
    }

    /**
     * Called when proxy failed to send request to the origin.
     */
    private void handleProxyRequestError(Throwable error) {
        log.warn("Can't send request to origin: {}", error.getMessage());
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        // for 5xx errors we use exponential backoff strategy, so passing retryAfterSeconds parameter makes no sense
        upstreamRoute.fail(HttpStatus.BAD_GATEWAY);
        // get next upstream
        if (canRetry(upstreamRoute)) {
            sendRequest(); // try next
        }
    }

    /**
     * Called when proxy failed to send response to the client.
     */
    private void handleResponseError(Throwable error) {
        log.warn("Can't send response to client: {}", error.getMessage());
        context.getProxyRequest().reset(); // drop connection to stop origin response
        context.getResponse().reset();     // drop connection, so that partial client response won't seem complete
        finalizeRequest();
    }

    private Route selectRoute() {
        Config config = context.getConfig();
        HttpServerRequest request = context.getRequest();
        String path = request.path();

        for (Route route : config.getRoutes().values()) {
            List<Pattern> paths = route.getPaths();
            Set<String> methods = route.getMethods();

            if (!methods.isEmpty() && !methods.contains(request.method().name())) {
                continue;
            }

            if (paths.isEmpty()) {
                return route;
            }

            for (Pattern pattern : route.getPaths()) {
                if (pattern.matcher(path).matches()) {
                    return route;
                }
            }
        }

        return null;
    }

    @SneakyThrows
    private String getEndpointUri(Upstream upstream) {
        URIBuilder uriBuilder = new URIBuilder(upstream.getEndpoint());
        if (context.getRoute().isRewritePath()) {
            uriBuilder.setPath(context.getRequest().path());
            String query = context.getRequest().query();
            if (query != null) {
                uriBuilder.setParameters(URLEncodedUtils.parse(query, StandardCharsets.UTF_8));
            }
        }
        return uriBuilder.toString();
    }

    private void respond(HttpStatus status, String result) {
        finalizeRequest();
        context.respond(status, result);
    }

    private void respond(HttpException exception) {
        finalizeRequest();
        context.respond(exception);
    }

    private void finalizeRequest() {
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
