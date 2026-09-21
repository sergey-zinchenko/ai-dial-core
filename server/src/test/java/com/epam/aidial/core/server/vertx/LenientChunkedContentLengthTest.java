package com.epam.aidial.core.server.vertx;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
public class LenientChunkedContentLengthTest {

    private static final String ERROR_JSON =
            "{\"error\":{\"message\":\"maximum context length is 262144 tokens\"}}";

    private ServerSocket origin;
    private Thread originThread;

    @AfterEach
    void stopOrigin() throws IOException {
        if (origin != null) {
            origin.close();
        }
        if (originThread != null) {
            originThread.interrupt();
        }
    }

    @Test
    void sendFailsWithoutHelper(Vertx vertx, VertxTestContext testContext) throws Throwable {
        startOrigin();
        HttpClient client = createClient(vertx);
        client.request(requestOptions())
                .compose(request -> request.send(Buffer.buffer("{}")))
                .onComplete(testContext.failing(error -> testContext.completeNow()));
        await(testContext);
        client.close();
    }

    @Test
    void sendForwards400AfterHelper(Vertx vertx, VertxTestContext testContext) throws Throwable {
        startOrigin();
        HttpClient client = createClient(vertx);
        client.request(requestOptions())
                .compose(request -> {
                    LenientChunkedContentLength.allowOn(request);
                    return request.send(Buffer.buffer("{}"));
                })
                .compose(response -> {
                    testContext.verify(() -> assertEquals(400, response.statusCode()));
                    return response.body();
                })
                .onComplete(testContext.succeeding(body -> testContext.verify(() -> {
                    assertTrue(body.toString().contains("maximum context length"));
                    testContext.completeNow();
                })));
        await(testContext);
        client.close();
    }

    private RequestOptions requestOptions() {
        return new RequestOptions()
                .setHost("127.0.0.1")
                .setPort(origin.getLocalPort())
                .setURI("/")
                .setMethod(HttpMethod.POST);
    }

    private static HttpClient createClient(Vertx vertx) {
        HttpClientOptions options = new HttpClientOptions();
        options.setDecompressionSupported(true);
        return vertx.createHttpClient(options);
    }

    private void startOrigin() throws IOException {
        origin = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        originThread = new Thread(() -> {
            try (Socket socket = origin.accept()) {
                drainRequest(socket.getInputStream());
                writeMalformed400(socket.getOutputStream());
            } catch (IOException ignored) {
                // socket closed from AfterEach
            }
        }, "lenient-chunked-origin");
        originThread.setDaemon(true);
        originThread.start();
    }

    private static void drainRequest(InputStream in) throws IOException {
        byte[] buffer = new byte[4096];
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        while (true) {
            int read = in.read(buffer);
            if (read < 0) {
                return;
            }
            raw.write(buffer, 0, read);
            byte[] bytes = raw.toByteArray();
            int headerEnd = indexOfHeaderEnd(bytes);
            if (headerEnd < 0) {
                continue;
            }
            int contentLength = parseContentLength(bytes, headerEnd);
            int have = bytes.length - headerEnd;
            while (have < contentLength) {
                read = in.read(buffer);
                if (read < 0) {
                    return;
                }
                have += read;
            }
            return;
        }
    }

    private static int indexOfHeaderEnd(byte[] bytes) {
        for (int i = 0; i + 3 < bytes.length; i++) {
            if (bytes[i] == '\r' && bytes[i + 1] == '\n' && bytes[i + 2] == '\r' && bytes[i + 3] == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    private static int parseContentLength(byte[] bytes, int headerEnd) {
        String headers = new String(bytes, 0, headerEnd, StandardCharsets.ISO_8859_1);
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("Content-Length")) {
                return Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        return 0;
    }

    private static void writeMalformed400(OutputStream out) throws IOException {
        byte[] body = ERROR_JSON.getBytes(StandardCharsets.US_ASCII);
        String headers = "HTTP/1.1 400 Bad Request\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(Integer.toHexString(body.length).getBytes(StandardCharsets.US_ASCII));
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void await(VertxTestContext testContext) throws Throwable {
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "test timed out");
        if (testContext.failed()) {
            throw testContext.causeOfFailure();
        }
    }
}
