package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.CollectRequestAttachmentsFn;
import com.epam.aidial.core.server.function.CollectRequestDataFn;
import com.epam.aidial.core.server.function.CollectResponseAttachmentsFn;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.List;

@Slf4j
public class InterceptorController {

    private final Proxy proxy;
    private final ProxyContext context;

    private final List<BaseRequestFunction<ObjectNode>> enhancementFunctions;

    public InterceptorController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
        this.enhancementFunctions = List.of(new CollectRequestAttachmentsFn(proxy, context), new CollectRequestDataFn(proxy, context));
    }

    public Future<?> handle() {
        log.info("Received request from client. Trace: {}. Span: {}. Project: {}. Deployment: {}. Headers: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                context.getRequest().headers().size());

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

    private void handleError(Throwable error) {
        log.error("Can't handle request. Project: {}. User sub: {}. Trace: {}. Span: {}. Error: {}",
                context.getProject(), context.getUserSub(), context.getTraceId(), context.getSpanId(), error.getMessage());
        respond(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private void handleRequestBody(Buffer requestBody) {
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


    private static String buildUri(ProxyContext context) {
        HttpServerRequest request = context.getRequest();
        Deployment deployment = context.getDeployment();
        String endpoint = deployment.getEndpoint();
        String query = request.query();
        return endpoint + (query == null ? "" : "?" + query);
    }

    private void sendRequest() {
        String uri = buildUri(context);
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(uri)
                .setMethod(context.getRequest().method())
                .setTraceOperation(context.getTraceOperation());

        proxy.getClient().request(options)
                .onSuccess(this::handleProxyRequest)
                .onFailure(this::handleProxyConnectionError);
    }

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
                context.getDeployment().getEndpoint(), error.getMessage());

        respond(HttpStatus.BAD_GATEWAY, "Failed to connect to origin");
    }


    void handleProxyRequest(HttpClientRequest proxyRequest) {
        log.info("Connected to interceptor. Trace: {}. Span: {}. Project: {}. Deployment: {}. Address: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                proxyRequest.connection().remoteAddress());

        HttpServerRequest request = context.getRequest();
        context.setProxyRequest(proxyRequest);
        context.setProxyConnectTimestamp(System.currentTimeMillis());

        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers());

        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        proxyRequest.headers().add(Proxy.HEADER_API_KEY, proxyApiKeyData.getPerRequestKey());


        Buffer requestBody = context.getRequestBody();
        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(requestBody.length()));

        proxyRequest.send(requestBody)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyResponseError);
    }

    /**
     * Called when proxy failed to receive response header from origin.
     */
    private void handleProxyResponseError(Throwable error) {
        log.warn("Proxy failed to receive response header from origin. Trace: {}. Span: {}. Project: {}. Deployment: {}. Address: {}. Error:",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                context.getProxyRequest().connection().remoteAddress(),
                error);
    }

    private void handleProxyResponse(HttpClientResponse proxyResponse) {
        log.info("Received header from origin. Trace: {}. Span: {}. Project: {}. Deployment: {}. Endpoint: {}. Status: {}. Headers: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                context.getDeployment().getEndpoint(),
                proxyResponse.statusCode(), proxyResponse.headers().size());

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

        responseStream.pipe()
                .endOnFailure(false)
                .endOnSuccess(false)
                .to(response)
                .onSuccess(ignore -> handleResponse(responseStream))
                .onFailure(this::handleResponseError);
    }

    void handleResponse(BufferingReadStream responseStream) {
        Buffer responseBody = context.getResponseStream().getContent();
        collectResponseAttachments(responseBody).onComplete(result -> {
            if (result.failed()) {
                log.warn("Failed to collect attachments from response. Trace: {}. Span: {}",
                        context.getTraceId(), context.getSpanId(), result.cause());
            }
            completeProxyResponse(responseStream);
        });
    }

    private Future<Void> collectResponseAttachments(Buffer responseBody) {
        if (context.isStreamingRequest()) {
            return Future.succeededFuture();
        }
        try (InputStream stream = new ByteBufInputStream(responseBody.getByteBuf())) {
            ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
            var fn = new CollectResponseAttachmentsFn(proxy, context);
            return fn.apply(tree);
        } catch (Throwable e) {
            log.warn("Can't parse JSON response body. Trace: {}. Span: {}. Error:",
                    context.getTraceId(), context.getSpanId(), e);
            return Future.failedFuture(e);
        }
    }

    private void completeProxyResponse(BufferingReadStream responseStream) {
        HttpServerResponse response = context.getResponse();
        responseStream.end(response);
        finalizeRequest();
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
