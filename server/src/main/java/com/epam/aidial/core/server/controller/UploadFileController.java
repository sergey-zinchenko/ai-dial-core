package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.stream.BlobWriteStream;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.HttpUtils;
import io.vertx.core.streams.Pipe;
import io.vertx.core.streams.impl.PipeImpl;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UploadFileController extends AccessControlBaseController {

    public UploadFileController(Proxy proxy, ProxyContext context) {
        super(proxy, context, true);
    }

    @Override
    protected Future<?> handle(ResourceDescriptor resource, boolean hasWriteAccess) {
        if (resource.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "File name is missing");
        }

        if (!ResourceDescriptorFactory.isValidResourcePath(resource)) {
            return context.respond(HttpStatus.BAD_REQUEST, "Resource name and/or parent folders must not end with .(dot)");
        }
        String author = context.getUserDisplayName();
        return proxy.getVertx().executeBlocking(() -> {
            EtagHeader etag = validateRequest(context.getRequest(), resource);
            context.getRequest()
                    .setExpectMultipart(true)
                    .uploadHandler(upload -> {
                        String contentType = upload.contentType();
                        Pipe<Buffer> pipe = new PipeImpl<>(upload).endOnFailure(false);
                        BlobWriteStream writeStream = new BlobWriteStream(proxy.getVertx(), proxy.getResourceService(),
                                proxy.getStorage(), resource, etag, contentType, author);
                        pipe.to(writeStream)
                                .onSuccess(success -> {
                                    FileMetadata metadata = writeStream.getMetadata();
                                    context.putHeader(HttpHeaders.ETAG, metadata.getEtag())
                                            .exposeHeaders()
                                            .respond(HttpStatus.OK, metadata);
                                })
                                .onFailure(error -> {
                                    writeStream.abortUpload(error);
                                    log.warn("Failed to upload file: {}", resource.getUrl(), error);
                                    context.respond(error, "Failed to upload file: " + resource.getUrl());
                                });
                    });

            return Future.succeededFuture();
        }, false)
                .otherwise(error -> {
                    log.warn("Failed to upload file: {}", resource.getUrl(), error);
                    context.respond(error, "Failed to upload file: " + resource.getUrl());
                    return null;
                });
    }

    private EtagHeader validateRequest(HttpServerRequest request, ResourceDescriptor resource) {
        EtagHeader etag = ProxyUtil.etag(context.getRequest());
        String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        etag.validate(() -> proxy.getResourceService().getEtag(resource));
        if (contentType == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Request must have a content-type header to decode a multipart request");
        }
        if (!HttpUtils.isValidMultipartContentType(contentType)) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Request must have a valid content-type header to decode a multipart request");
        }
        return etag;
    }
}
