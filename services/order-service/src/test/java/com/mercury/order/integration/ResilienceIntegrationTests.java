package com.mercury.order.integration;

import com.mercury.order.integration.StackClient.Api;
import com.mercury.order.integration.StackClient.Stock;
import com.mercury.order.service.SagaRecovery;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.mercury.order.integration.StackClient.key;
import static com.mercury.order.integration.StackClient.orderJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario D against the real stack: Inventory goes away. The live API must not hang, the circuit
 * breaker must open so later requests fail fast, orders accepted before it opened must be resolved
 * by recovery once Inventory is back, and the circuit must close again.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "order.recovery.enabled=false",
        "order.recovery.initial-backoff=100ms",
        "order.recovery.max-backoff=500ms",
        "order.recovery.max-attempts=10",
        // a small, fast circuit so the test is quick and deterministic
        "order.resilience.inventory.sliding-window-size=4",
        "order.resilience.inventory.minimum-calls=4",
        "order.resilience.inventory.failure-rate-threshold=50",
        "order.resilience.inventory.wait-duration-in-open-state=2s",
        "order.resilience.inventory.permitted-calls-in-half-open-state=1"
})
class ResilienceIntegrationTests {

    @DynamicPropertySource
    static void realInfrastructure(DynamicPropertyRegistry registry) {
        RealServicesStack stack = RealServicesStack.start();
        registry.add("spring.datasource.url", stack::orderJdbcUrl);
        registry.add("spring.datasource.username", stack::dbUsername);
        registry.add("spring.datasource.password", stack::dbPassword);
        registry.add("product.service.url", stack::productBaseUrl);
        registry.add("inventory.service.url", stack::inventoryBaseUrl);
    }

    @Value("${local.server.port}")
    private int orderPort;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private SagaRecovery recovery;

    @Autowired
    private MeterRegistry meters;

    private StackClient client;
    private final List<UUID> products = new ArrayList<>();
    private static final String ORDER_DB = RealServicesStack.ORDER_DB;

    private StackClient client() {
        if (client == null) {
            client = new StackClient(jsonMapper);
        }
        return client;
    }

    private Api placeOrder(UUID product, int quantity) {
        return client().call("POST", "http://localhost:" + orderPort, "/api/v1/orders", key(),
                orderJson(product, quantity));
    }

    private double circuitState() {
        return meters.get("downstream.circuit.state").tag("service", "inventory").gauge().value();
    }

    @AfterEach
    void clean() throws Exception {
        RealServicesStack.start().startInventoryServiceIfStopped();
        client().cleanUp(ORDER_DB, products.toArray(UUID[]::new));
        products.clear();
    }

    @Test
    void anInventoryOutageNeverHangsOpensTheCircuitAndEverythingResolvesAfterItReturns() throws Exception {
        UUID product = client().productWithStock("Laptop", "100.00", 10);
        products.add(product);
        RealServicesStack stack = RealServicesStack.start();
        assertThat(circuitState()).isEqualTo(0.0);                        // healthy to begin with

        stack.stopInventoryService();
        List<Long> durations = new ArrayList<>();
        int placed = 0;
        try {
            // keep ordering during the outage until the circuit opens
            while (circuitState() != 2.0 && placed < 12) {
                long start = System.nanoTime();
                Api response = placeOrder(product, 1);
                durations.add((System.nanoTime() - start) / 1_000_000);
                placed++;
                assertThat(response.status()).isEqualTo(503);
                assertThat(response.body().path("error").asString()).isEqualTo("INVENTORY_SERVICE_UNAVAILABLE");
            }
            assertThat(circuitState()).as("circuit state after %d failed orders", placed).isEqualTo(2.0);   // OPEN

            // with the circuit open, requests are refused immediately, not after a timeout
            long start = System.nanoTime();
            Api failFast = placeOrder(product, 1);
            long failFastMillis = (System.nanoTime() - start) / 1_000_000;
            placed++;
            assertThat(failFast.status()).isEqualTo(503);
            assertThat(failFastMillis).as("fail-fast latency").isLessThan(1_000);
            // and nothing in the outage ever took longer than the bounded client timeouts
            assertThat(durations).allMatch(ms -> ms < 8_000);
        } finally {
            stack.startInventoryService();
        }

        // Inventory is back. Orders accepted before the circuit opened are still unresolved
        // (nothing was guessed while it was unreachable); recovery settles them once the circuit
        // lets calls through again (it half-opens after wait-duration-in-open-state).
        Thread.sleep(2_200);
        for (int pass = 0; pass < 8 && unresolvedOrders(product) > 0; pass++) {
            for (UUID orderId : orderIds(product)) {
                client().makeDue(ORDER_DB, orderId);
            }
            recovery.recoverDue();
        }

        assertThat(unresolvedOrders(product)).isZero();
        for (UUID orderId : orderIds(product)) {
            assertThat(client().orderStatus(ORDER_DB, orderId)).isEqualTo("CANCELLED");
            assertThat(client().sagaState(ORDER_DB, orderId)).isEqualTo("CANCELLED");
        }
        assertThat(orderIds(product)).hasSize(placed);                    // every attempt is accounted for
        assertThat(client().stock(product)).isEqualTo(new Stock(10, 0));  // nothing reserved, nothing leaked

        // the circuit has closed again and ordering works
        Api recovered = placeOrder(product, 2);
        assertThat(recovered.status()).isEqualTo(201);
        assertThat(circuitState()).isEqualTo(0.0);
        assertThat(client().stock(product)).isEqualTo(new Stock(8, 2));
    }

    private List<UUID> orderIds(UUID product) throws Exception {
        return client().orderIdsFor(ORDER_DB, product);
    }

    private int unresolvedOrders(UUID product) throws Exception {
        int unresolved = 0;
        for (UUID orderId : orderIds(product)) {
            if ("PENDING".equals(client().orderStatus(ORDER_DB, orderId))) {
                unresolved++;
            }
        }
        return unresolved;
    }
}
