package com.mercury.order.integration;

import com.mercury.order.integration.StackClient.Api;
import com.mercury.order.integration.StackClient.Stock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.mercury.order.integration.StackClient.key;
import static com.mercury.order.integration.StackClient.orderJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stranded-reservation race, reproduced deterministically against the REAL stack.
 *
 * A reserve request is held inside the fault proxy BEFORE Inventory sees it. Order's read timeout
 * expires, so the saga compensates: it asks Inventory whether a reservation exists, is told NONE,
 * marks the item NOT_RESERVED and cancels the order. Only then is the held request released, and
 * Inventory (which has no memory of the cancellation) reserves the stock for an order that no
 * longer exists.
 *
 * Correct behaviour: stock reserved for a CANCELLED order must not stay held, so this test is
 * expected to FAIL until Inventory can refuse such a late reservation (documented limitation:
 * docs/saga-recovery.md, docs/async-reservation.md).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "order.recovery.enabled=false",
        // short enough to keep the test fast, long enough for the real stack to answer lookups
        "spring.http.clients.read-timeout=1s",
        "order.resilience.inventory.sliding-window-size=1000",
        "order.resilience.inventory.minimum-calls=1000"
})
class StrandedReservationRaceIntegrationTests {

    private static final String ORDER_DB = RealServicesStack.ORDER_DB;
    private static FaultProxy proxy;

    @DynamicPropertySource
    static void realInfrastructure(DynamicPropertyRegistry registry) throws Exception {
        RealServicesStack stack = RealServicesStack.start();
        proxy = new FaultProxy(stack.inventoryBaseUrl());
        registry.add("spring.datasource.url", stack::orderJdbcUrl);
        registry.add("spring.datasource.username", stack::dbUsername);
        registry.add("spring.datasource.password", stack::dbPassword);
        registry.add("product.service.url", stack::productBaseUrl);
        registry.add("inventory.service.url", proxy::baseUrl);
    }

    @AfterAll
    static void closeProxy() {
        if (proxy != null) {
            proxy.close();   // also cancels anything still held
        }
    }

    @Value("${local.server.port}")
    private int orderPort;

    @Autowired
    private JsonMapper jsonMapper;

    private StackClient client;
    private final List<UUID> products = new ArrayList<>();

    private StackClient client() {
        if (client == null) {
            client = new StackClient(jsonMapper);
        }
        return client;
    }

    @AfterEach
    void clean() throws Exception {
        proxy.reset();   // cancels any hold left behind by a failed assertion
        client().cleanUp(ORDER_DB, products.toArray(UUID[]::new));
        products.clear();
    }

    @Test
    void aReserveHeldPastOrdersTimeoutMustNotStrandStockAfterTheOrderIsCancelled() throws Exception {
        UUID product = client().productWithStock("Item", "10.00", 10);
        products.add(product);
        FaultProxy.Hold hold = proxy.hold(FaultProxy.reserve());

        // 1. the reserve is parked in the proxy: Order times out waiting for it
        Api response = client().call("POST", "http://localhost:" + orderPort, "/api/v1/orders", key(),
                orderJson(product, 3));
        assertThat(hold.awaitArrived(1, Duration.ofSeconds(10))).as("reserve reached the proxy").isTrue();
        assertThat(response.status()).isEqualTo(503);

        // 2. Order's cancellation path ran: lookup found nothing, so it gave up on the item
        UUID orderId = client().onlyOrderFor(ORDER_DB, product);
        assertThat(client().orderStatus(ORDER_DB, orderId)).isEqualTo("CANCELLED");
        assertThat(client().itemStatus(ORDER_DB, orderId, product)).isEqualTo("NOT_RESERVED");
        assertThat(client().inventoryRecords(product, "RESERVE")).as("Inventory has seen nothing yet").isZero();
        assertThat(client().stock(product)).isEqualTo(new Stock(10, 0));

        // 3. the held reserve finally reaches Inventory
        int forwardedBeforeRelease = proxy.forwardedRequests();   // the saga's own lookup was forwarded already
        hold.release();
        awaitForwarded(forwardedBeforeRelease + 1, 10);   // counted once Inventory has answered the reserve

        // 4. nothing may stay reserved for the cancelled order (and no Order-side release will ever come)
        Stock after = client().stock(product);
        int reserveRecords = client().inventoryRecords(product, "RESERVE");
        assertThat(client().orderStatus(ORDER_DB, orderId)).isEqualTo("CANCELLED");
        assertThat(after)
                .as("late reserve accepted by Inventory for a cancelled order: RESERVE records=%d, stock=%s",
                        reserveRecords, after)
                .isEqualTo(new Stock(10, 0));
    }

    private static void awaitForwarded(int count, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (proxy.forwardedRequests() < count) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("held reserve was not forwarded within " + seconds + " s");
            }
            Thread.sleep(50);
        }
    }
}
