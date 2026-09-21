package com.epam.aidial.core.server.vertx;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectDecoder;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.net.impl.ConnectionBase;
import lombok.experimental.UtilityClass;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

/**
 * Relaxes Netty's HTTP/1.1 client decoder to accept {@code Content-Length} together with
 * {@code Transfer-Encoding: chunked} (RFC 7230), as FastAPI/Starlette error responses do.
 */
@UtilityClass
public class LenientChunkedContentLength {

    private static final MethodHandle GET_INBOUND;
    private static final Field RFC9112_FIELD;

    static {
        try {
            MethodHandles.Lookup codecLookup = MethodHandles.privateLookupIn(
                    CombinedChannelDuplexHandler.class, MethodHandles.lookup());
            GET_INBOUND = codecLookup.findGetter(
                    CombinedChannelDuplexHandler.class, "inboundHandler", ChannelInboundHandler.class);
            RFC9112_FIELD = HttpObjectDecoder.class.getDeclaredField("useRfc9112TransferEncoding");
            RFC9112_FIELD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void allowOn(HttpClientRequest request) {
        HttpConnection connection = request.connection();
        if (!(connection instanceof ConnectionBase connectionBase)) {
            return;
        }
        ChannelHandler handler = connectionBase.channel().pipeline().get("codec");
        if (!(handler instanceof HttpClientCodec codec)) {
            return;
        }
        try {
            if (!(GET_INBOUND.invoke(codec) instanceof HttpObjectDecoder decoder)) {
                return;
            }
            if (!RFC9112_FIELD.getBoolean(decoder)) {
                return;
            }
            RFC9112_FIELD.setBoolean(decoder, false);
        } catch (Throwable e) {
            throw new IllegalStateException("Can't relax HTTP client decoder", e);
        }
    }
}
