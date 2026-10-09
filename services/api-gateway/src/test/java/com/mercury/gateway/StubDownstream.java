package com.mercury.gateway;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A tiny HTTP server that plays every downstream service: records what it was sent (its Transfer-Encoding header and body
 * included) and answers 200. A request whose body is cut off is recorded in {@code aborted}, not in {@code calls}.
 */
public final class StubDownstream implements AutoCloseable {

    /** {@code transferEncoding} is the header as received, null when absent; {@code body} is every byte of the request body */
    public record Call(String method, String path, String authorization, String transferEncoding, byte[] body) { }

    private final HttpServer server;
    public final List<Call> calls = new CopyOnWriteArrayList<>();
    /** for every request whose body ended in an error instead of at its end: how many bytes of it had arrived */
    public final List<Integer> aborted = new CopyOnWriteArrayList<>();

    public StubDownstream() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            ByteArrayOutputStream arrived = new ByteArrayOutputStream();
            try (InputStream in = exchange.getRequestBody()) {
                byte[] buffer = new byte[8192];
                for (int n = in.read(buffer); n != -1; n = in.read(buffer)) {
                    arrived.write(buffer, 0, n);
                }
            } catch (IOException e) {
                aborted.add(arrived.size());
                throw e;
            }
            byte[] received = arrived.toByteArray();
            calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Transfer-Encoding"), received));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
