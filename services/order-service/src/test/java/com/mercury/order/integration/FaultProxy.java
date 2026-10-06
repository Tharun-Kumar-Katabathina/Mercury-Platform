package com.mercury.order.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * A tiny HTTP proxy that sits between Order Service and the REAL Inventory Service and misbehaves
 * on demand, so the failures a network really produces can be injected into the real stack:
 *
 *  - DROP_RESPONSE: forward the request (Inventory applies it) and then close the connection
 *    without answering. The caller sees an error although the work happened: a lost response.
 *  - FAIL_503: answer 503 without forwarding: the work did NOT happen.
 *  - DELAY: forward at once (Inventory applies it) but hold the answer back for a while.
 *  - PASS: behave like a transparent proxy (the default).
 *
 * Rules match on "METHOD /path" and the first matching rule wins.
 */
final class FaultProxy implements AutoCloseable {

    enum Mode { PASS, DROP_RESPONSE, FAIL_503, DELAY }

    private record Rule(Predicate<String> matches, Mode mode, Duration delay) {
    }

    private final HttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();
    private final String targetBaseUrl;
    private final List<Rule> rules = new CopyOnWriteArrayList<>();
    private final AtomicInteger forwarded = new AtomicInteger();

    FaultProxy(String targetBaseUrl) throws IOException {
        this.targetBaseUrl = targetBaseUrl;
        this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        this.server.setExecutor(Executors.newCachedThreadPool());
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    int forwardedRequests() {
        return forwarded.get();
    }

    /** From now on, requests matching {@code methodAndPath} (e.g. "POST /api/v1/inventory/") get {@code mode}. */
    void on(Predicate<String> methodAndPath, Mode mode) {
        rules.add(0, new Rule(methodAndPath, mode, Duration.ZERO));
    }

    void delay(Predicate<String> methodAndPath, Duration delay) {
        rules.add(0, new Rule(methodAndPath, Mode.DELAY, delay));
    }

    /** back to a transparent proxy */
    void reset() {
        rules.clear();
    }

    static Predicate<String> reserve() {
        return call -> call.startsWith("POST ") && call.endsWith("/reserve");
    }

    static Predicate<String> release() {
        return call -> call.startsWith("POST ") && call.endsWith("/release");
    }

    static Predicate<String> lookup() {
        return call -> call.startsWith("GET ") && call.contains("/reservations/");
    }

    private void handle(HttpExchange exchange) throws IOException {
        String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        Rule rule = rules.stream().filter(r -> r.matches().test(call)).findFirst()
                .orElse(new Rule(c -> true, Mode.PASS, Duration.ZERO));

        byte[] requestBody = exchange.getRequestBody().readAllBytes();

        if (rule.mode() == Mode.FAIL_503) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }

        HttpResponse<byte[]> upstream;
        try {
            HttpRequest.Builder forward = HttpRequest.newBuilder(URI.create(
                            targetBaseUrl + exchange.getRequestURI().getRawPath()))
                    .method(exchange.getRequestMethod(), requestBody.length == 0
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(requestBody));
            copy(exchange, forward, "Content-Type");
            copy(exchange, forward, "Idempotency-Key");
            upstream = client.send(forward.build(), HttpResponse.BodyHandlers.ofByteArray());
            forwarded.incrementAndGet();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(503, -1);   // the real service is down
            exchange.close();
            return;
        }

        if (rule.mode() == Mode.DROP_RESPONSE) {
            exchange.close();   // the work happened; the answer is simply never sent
            return;
        }
        if (rule.mode() == Mode.DELAY) {
            try {
                Thread.sleep(rule.delay().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
        }

        upstream.headers().firstValue("Content-Type")
                .ifPresent(v -> exchange.getResponseHeaders().add("Content-Type", v));
        upstream.headers().firstValue("Idempotent-Replayed")
                .ifPresent(v -> exchange.getResponseHeaders().add("Idempotent-Replayed", v));
        byte[] body = upstream.body();
        try {
            exchange.sendResponseHeaders(upstream.statusCode(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } catch (IOException clientGaveUp) {
            // the caller timed out or was killed: expected in the failure tests
        } finally {
            exchange.close();
        }
    }

    private static void copy(HttpExchange exchange, HttpRequest.Builder builder, String header) {
        String value = exchange.getRequestHeaders().getFirst(header);
        if (value != null) {
            builder.header(header, value);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
