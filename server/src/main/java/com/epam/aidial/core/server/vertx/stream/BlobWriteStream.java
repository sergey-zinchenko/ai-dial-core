package com.epam.aidial.core.server.vertx.stream;

import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagBuilder;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.WriteStream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.blobstore.domain.MultipartPart;
import org.jclouds.blobstore.domain.MultipartUpload;
import org.jclouds.io.Payload;
import org.jclouds.io.payloads.InputStreamPayload;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of vertx {@link io.vertx.core.streams.WriteStream} that handles data chunks (from {@link io.vertx.core.streams.ReadStream}) and writes them to the blob storage.
 * If file content is bigger than 5MB - multipart upload will be used.
 * Chunk size can be configured via {@link #setWriteQueueMaxSize(int)} method, but should be no less than 5 MB according to the s3 specification.
 * If any exception is caught in between - multipart upload will be aborted.
 */
@Slf4j
public class BlobWriteStream implements WriteStream<Buffer> {

    public static final int MIN_PART_SIZE_BYTES = 5 * 1024 * 1024;

    private final Vertx vertx;
    private final ResourceService resourceService;
    private final BlobStorage storage;
    private final ResourceDescriptor resource;
    private final EtagHeader etag;
    private final String contentType;

    private final Buffer chunkBuffer = Buffer.buffer();
    private int chunkSize = MIN_PART_SIZE_BYTES;
    private int position;
    private MultipartUpload mpu;
    private EtagBuilder etagBuilder;
    private int chunkNumber = 0;
    @Getter
    private FileMetadata metadata;

    private Throwable exception;

    private Handler<Throwable> errorHandler;

    private final List<MultipartPart> parts = new ArrayList<>();

    private boolean isBufferFull;

    private long bytesHandled;

    private final String author;

    public BlobWriteStream(Vertx vertx,
                           ResourceService resourceService,
                           BlobStorage storage,
                           ResourceDescriptor resource,
                           EtagHeader etag,
                           String contentType) {
        this(vertx, resourceService, storage, resource, etag, contentType, null);
    }

    public BlobWriteStream(Vertx vertx,
                           ResourceService resourceService,
                           BlobStorage storage,
                           ResourceDescriptor resource,
                           EtagHeader etag,
                           String contentType,
                           String author) {
        this.vertx = vertx;
        this.resourceService = resourceService;
        this.storage = storage;
        this.resource = resource;
        this.etag = etag;
        this.contentType = contentType != null ? contentType : BlobStorageUtil.getContentType(resource.getName());
        this.author = author;
    }

    @Override
    public synchronized WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        this.errorHandler = handler;
        return this;
    }

    @Override
    public synchronized Future<Void> write(Buffer data) {
        Promise<Void> promise = Promise.promise();
        write(data, promise);
        return promise.future();
    }

    @Override
    public synchronized void write(Buffer data, Handler<AsyncResult<Void>> handler) {
        // exception might be thrown during drain handling, if so we need to stop processing chunks
        // upload abortion will be handled in the end
        if (exception != null) {
            handler.handle(Future.failedFuture(exception));
            return;
        }

        int length = data.length();
        chunkBuffer.setBuffer(position, data);
        position += length;
        bytesHandled += length;
        if (position > chunkSize) {
            isBufferFull = true;
        }

        handler.handle(Future.succeededFuture());
    }

    @Override
    public void end(Handler<AsyncResult<Void>> handler) {
        Future<Void> result = vertx.executeBlocking(() -> {
            synchronized (BlobWriteStream.this) {
                if (exception != null) {
                    throw new RuntimeException(exception);
                }

                ByteBuf lastChunk = chunkBuffer.slice(0, position).getByteBuf();
                if (mpu == null) {
                    log.info("Resource is too small for multipart upload, sending as a regular blob");
                    try (InputStream chunkStream = new ByteBufInputStream(lastChunk)) {
                        metadata = resourceService.putFile(resource, chunkStream.readAllBytes(), etag, contentType, author);
                    }
                } else {
                    if (position != 0) {
                        try (Payload payload = bufferToPayload(lastChunk.duplicate())) {
                            MultipartPart part = storage.storeMultipartPart(mpu, ++chunkNumber, payload);
                            parts.add(part);
                        }
                    }

                    String newEtag = etagBuilder.append(lastChunk.nioBuffer()).build();
                    ResourceService.MultipartData multipartData = new ResourceService.MultipartData(
                            mpu, parts, contentType, bytesHandled, newEtag);
                    metadata = resourceService.finishFileUpload(resource, multipartData, etag, author);
                    log.info("Multipart upload committed, bytes handled {}", bytesHandled);
                }

                return null;
            }
        });
        if (handler != null) {
            result.onComplete(handler);
        }
    }

    @Override
    public synchronized WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
        assert maxSize > MIN_PART_SIZE_BYTES;
        chunkSize = maxSize;
        return this;
    }

    @Override
    public synchronized boolean writeQueueFull() {
        return isBufferFull;
    }

    @Override
    public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
        vertx.executeBlocking(() -> {
            synchronized (BlobWriteStream.this) {
                try {
                    if (mpu == null) {
                        mpu = storage.initMultipartUpload(resource.getAbsoluteFilePath(), contentType);
                        etagBuilder = new EtagBuilder();
                    }

                    ByteBuf chunk = chunkBuffer.slice(0, position).getByteBuf();
                    try (Payload payload = bufferToPayload(chunk.duplicate())) {
                        MultipartPart part = storage.storeMultipartPart(mpu, ++chunkNumber, payload);
                        parts.add(part);
                    }
                    etagBuilder.append(chunk.nioBuffer());
                    position = 0;
                    isBufferFull = false;
                } catch (Throwable ex) {
                    exception = ex;
                } finally {
                    if (handler != null) {
                        handler.handle(null);
                    }
                }
            }
            return null;
        });

        return this;
    }

    public synchronized void abortUpload(Throwable ex) {
        if (mpu != null) {
            storage.abortMultipartUpload(mpu);
        }

        if (errorHandler != null) {
            errorHandler.handle(ex);
        }

        log.warn("Multipart upload aborted", ex);
    }

    private static Payload bufferToPayload(ByteBuf buffer) {
        Payload payload = new InputStreamPayload(new ByteBufInputStream(buffer));
        // Content length is required by S3BlobStore
        payload.getContentMetadata().setContentLength((long) buffer.readableBytes());

        return payload;
    }
}
