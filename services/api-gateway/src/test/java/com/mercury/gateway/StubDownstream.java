package com.mercury.gateway;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A tiny HTTP server that plays every downstream service: records what it was sent (its Transfer-Encoding header and body included) and answers 200. */
public final class StubDownstream implements AutoCloseable {

    /** {@code transferEncoding} is the header as received, null when absent; {@code body} is every byte of the request body */
    public record Call(String method, String path, String authorization, String transferEncoding, byte[] body) { }

    private final HttpServer server;
    public final List<Call> calls = new CopyOnWriteArrayList<>();

    public StubDownstream() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            byte[] received = exchange.getRequestBody().readAllBytes();
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
