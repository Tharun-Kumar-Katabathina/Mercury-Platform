package com.mercury.order.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** FaultProxy's HOLD mode against a stand-in upstream; needs no Docker. */
class FaultProxyHoldTests {

    private HttpServer upstream;
    private final AtomicInteger upstreamCalls = new AtomicInteger();
    private FaultProxy proxy;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            upstreamCalls.incrementAndGet();
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        proxy = new FaultProxy("http://localhost:" + upstream.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        proxy.close();
        upstream.stop(0);
    }

    private CompletableFuture<HttpResponse<String>> post(String path) {
        return client.sendAsync(HttpRequest.newBuilder(URI.create(proxy.baseUrl() + path))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void withoutARuleTheProxyStaysTransparent() throws Exception {
        assertThat(post("/x/reserve").get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(upstreamCalls).hasValue(1);
    }

    @Test
    void aHeldRequestDoesNotReachTheUpstreamUntilReleased() throws Exception {
        FaultProxy.Hold hold = proxy.hold(FaultProxy.reserve());

        CompletableFuture<HttpResponse<String>> held = post("/x/reserve");
        assertThat(hold.awaitArrived(1, Duration.ofSeconds(5))).isTrue();
        Thread.sleep(300);
        assertThat(held).isNotDone();
        assertThat(upstreamCalls).hasValue(0);                                   // held BEFORE forwarding
        assertThat(post("/x/release").get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);   // others pass

        hold.release();

        assertThat(held.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(upstreamCalls).hasValue(2);
        assertThat(proxy.forwardedRequests()).isEqualTo(2);
    }

    @Test
    void resetCancelsAHeldRequestWithoutForwardingIt() throws Exception {
        FaultProxy.Hold hold = proxy.hold(FaultProxy.reserve());
        CompletableFuture<HttpResponse<String>> held = post("/x/reserve");
        assertThat(hold.awaitArrived(1, Duration.ofSeconds(5))).isTrue();

        proxy.reset();

        assertThat(held.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(503);
        assertThat(upstreamCalls).hasValue(0);
    }
}
