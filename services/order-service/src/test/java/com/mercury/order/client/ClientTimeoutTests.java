package com.mercury.order.client;

import com.mercury.order.exception.InventoryServiceException;
import com.mercury.order.exception.ProductServiceException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A real HTTP server that accepts the request and then answers far too slowly. With the
 * configured read timeout the client must give up quickly and report the service as
 * unavailable instead of hanging.
 */
@SpringBootTest(properties = {
        "spring.http.clients.read-timeout=300ms",
        "spring.http.clients.connect-timeout=300ms"
})
class ClientTimeoutTests {

    private static final long SLOW_MILLIS = 3_000;
    private static HttpServer slowServer;

    @DynamicPropertySource
    static void slowDownstream(DynamicPropertyRegistry registry) throws IOException {
        slowServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        slowServer.createContext("/", exchange -> {
            try {
                Thread.sleep(SLOW_MILLIS);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException | IOException ignored) {
                // client already gave up
            } finally {
                exchange.close();
            }
        });
        slowServer.start();
        String url = "http://localhost:" + slowServer.getAddress().getPort();
        registry.add("product.service.url", () -> url);
        registry.add("inventory.service.url", () -> url);
    }

    @AfterAll
    static void stopServer() {
        slowServer.stop(0);
    }

    @Autowired
    private ProductClient productClient;

    @Autowired
    private InventoryClient inventoryClient;

    @Test
    void slowProductServiceTimesOutAs503() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> productClient.getProduct(UUID.randomUUID()))
                .isInstanceOfSatisfying(ProductServiceException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(503));

        assertThat(elapsedMillis(start)).isLessThan(SLOW_MILLIS);
    }

    @Test
    void slowInventoryServiceTimesOutAs503ForReserveAndRelease() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> inventoryClient.reserve(UUID.randomUUID(), 1, "k"))
                .isInstanceOfSatisfying(InventoryServiceException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(503));
        assertThatThrownBy(() -> inventoryClient.release(UUID.randomUUID(), 1, "k"))
                .isInstanceOfSatisfying(InventoryServiceException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(503));

        assertThat(elapsedMillis(start)).isLessThan(SLOW_MILLIS);   // two calls, each ~300 ms
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
