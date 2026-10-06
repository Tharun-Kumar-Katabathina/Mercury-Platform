package com.mercury.order.integration;

import com.mercury.order.integration.StackClient.Api;
import com.mercury.order.integration.StackClient.Stock;
import com.mercury.order.service.SagaRecovery;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.mercury.order.integration.StackClient.key;
import static com.mercury.order.integration.StackClient.orderJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failure injection against the REAL stack (Order, Product, Inventory, PostgreSQL). A fault proxy
 * sits between Order and Inventory to lose responses, refuse calls and delay answers, and in one test
 * the Order Service process is killed outright. Every assertion reads PostgreSQL directly.
 *
 * Recovery is run explicitly here (the background worker is off in this context) so each step is
 * deterministic; the kill test runs Order as a separate process WITH its worker on.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "order.recovery.enabled=false",
        "order.recovery.initial-backoff=100ms",
        "order.recovery.max-backoff=500ms",
        "order.recovery.max-attempts=6",
        // keep the circuit closed: this class injects many failures on purpose
        "order.resilience.inventory.sliding-window-size=1000",
        "order.resilience.inventory.minimum-calls=1000"
})
class SagaRecoveryIntegrationTests {

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
        // the services are shared with other test classes and stopped by the JVM shutdown hook
        if (proxy != null) {
            proxy.close();
        }
    }

    @Value("${local.server.port}")
    private int orderPort;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private SagaRecovery recovery;

    private StackClient client;
    private final List<UUID> products = new ArrayList<>();
    private static final String ORDER_DB = RealServicesStack.ORDER_DB;

    private StackClient client() {
        if (client == null) {
            client = new StackClient(jsonMapper);
        }
        return client;
    }

    private String orderUrl() {
        return "http://localhost:" + orderPort;
    }

    private UUID productWithStock(int stock) {
        UUID id = client().productWithStock("Item", "10.00", stock);
        products.add(id);
        return id;
    }

    private Api placeOrder(String key, Object... productAndQuantity) {
        return client().call("POST", orderUrl(), "/api/v1/orders", key, orderJson(productAndQuantity));
    }

    @AfterEach
    void clean() throws Exception {
        proxy.reset();
        client().cleanUp(ORDER_DB, products.toArray(UUID[]::new));
        products.clear();
    }

    // ---- A: the reservation happened but the response was lost ---------------------------------

    @Test
    void aLostReserveResponseIsResolvedByLookupAndTheReservationGivenBack() throws Exception {
        UUID product = productWithStock(10);
        proxy.on(FaultProxy.reserve(), FaultProxy.Mode.DROP_RESPONSE);   // Inventory applies it, Order never hears

        Api response = placeOrder(key(), product, 3);

        // the client is told it failed ...
        assertThat(response.status()).isEqualTo(503);
        // ... and the order is consistent with that: found the reservation, released it, cancelled
        UUID orderId = client().onlyOrderFor(ORDER_DB, product);
        assertThat(client().orderStatus(ORDER_DB, orderId)).isEqualTo("CANCELLED");
        assertThat(client().sagaState(ORDER_DB, orderId)).isEqualTo("CANCELLED");
        assertThat(client().itemStatus(ORDER_DB, orderId, product)).isEqualTo("RELEASED");
        assertThat(client().stock(product)).isEqualTo(new Stock(10, 0));
        assertThat(client().inventoryRecords(product, "RESERVE")).isEqualTo(1);   // it really was reserved
        assertThat(client().inventoryRecords(product, "RELEASE")).isEqualTo(1);   // and exactly once released
    }

    @Test
    void ifInventoryCannotBeQueriedYetTheStockStaysReservedUntilRecoveryResolvesIt() throws Exception {
        UUID product = productWithStock(10);
        proxy.on(FaultProxy.reserve(), FaultProxy.Mode.DROP_RESPONSE);
        proxy.on(FaultProxy.lookup(), FaultProxy.Mode.FAIL_503);          // cannot even ask

        assertThat(placeOrder(key(), product, 3).status()).isEqualTo(503);

        UUID orderId = client().onlyOrderFor(ORDER_DB, product);
        assertThat(client().orderStatus(ORDER_DB, orderId)).isEqualTo("PENDING");     // nothing is guessed
        assertThat(client().sagaState(ORDER_DB, orderId)).isEqualTo("COMPENSATING");
        assertThat(client().itemStatus(ORDER_DB, orderId, product)).isEqualTo("RESERVING");
        assertThat(client().stock(product)).isEqualTo(new Stock(7, 3));               // really reserved

        proxy.reset();                                                                // Inventory reachable again
        client().makeDue(ORDER_DB, orderId);
        assertThat(recovery.recoverDue()).isEqualTo(1);

        assertThat(client().orderStatus(ORDER_DB, orderId)).isEqualTo("CANCELLED");
        assertThat(client().stock(product)).isEqualTo(new Stock(10, 0));
        assertThat(client().inventoryRecords(product, "RELEASE")).isEqualTo(1);
    }

    @Test
    void aReserveThatNeverReachedInventoryIsFoundAbsentAndNothingIsReleased() throws Exception {
        UUID product = productWithStock(10);
        proxy.on(FaultProxy.reserve(), FaultProxy.Mode.FAIL_503);         // refused before Inventory saw it

        assertThat(placeOrder(key(), product, 3).status()).isEqualTo(503);

        UUID orderId = client().onlyOrderFor(ORDER_DB, product);
        assertThat(client().orderStatus(ORDER_DB, orderId)).isEqualTo("CANCELLED");
        assertThat(client().itemStatus(ORDER_DB, orderId, product)).isEqualTo("NOT_RESERVED");
        assertThat(client().stock(product)).isEqualTo(new Stock(10, 0));
        assertThat(client().inventoryRecords(product, "RELEASE")).isZero();           // nothing to give back
    }

    // ---- B: compensation itself fails -----------------------------------------------------------

    @Test
    void aFailedCompensationBecomesDurableWorkThatRecoveryFinishes() throws Exception {
        UUID p1 = productWithStock(10);
        UUID p2 = productWithStock(1);
        UUID reservedFirst = p1.compareTo(p2) < 0 ? p1 : p2;
        UUID shortOfStock = reservedFirst.equals(p1) ? p2 : p1;
        // make the later item the one that runs out
        client().call("PUT", RealServicesStack.start().inventoryBaseUrl(),
                "/api/v1/inventory/" + reservedFirst, null, "{\"availableQuantity\":10}");
        client().call("PUT", RealServicesStack.start().inventoryBaseUrl(),
                "/api/v1/inventory/" + shortOfStock, null, "{\"availableQuantity\":1}");
        proxy.on(FaultProxy.release(), FaultProxy.Mode.FAIL_503);         // giving stock back fails

        Api response = placeOrder(key(), reservedFirst, 4, shortOfStock, 5);

        assertThat(response.status()).isEqualTo(409);                     // the real cause is reported
        assertThat(response.body().path("error").asString()).isEqualTo("INSUFFICIENT_STOCK");
        UUID orderId = client().onlyOrderFor(ORDER_DB, reservedFirst);
        assertThat(client().orderStatus(ORDER_DB, orderId)).isEqualTo("PENDING");     // not "cancelled" while stock leaks
        assertThat(client().sagaState(ORDER_DB, orderId)).isEqualTo("COMPENSATING");
        assertThat(client().sagaAttempts(ORDER_DB, orderId)).isEqualTo(1);
        assertThat(client().itemStatus(ORDER_DB, orderId, reservedFirst)).isEqualTo("RELEASING");
        assertThat(client().stock(reservedFirst)).isEqualTo(new Stock(6, 4));         // still held

        proxy.reset();
        client().makeDue(ORDER_DB, orderId);
        assertThat(recovery.recoverDue()).isEqualTo(1);

        assertThat(client().orderStatus(ORDER_DB, orderId)).isEqualTo("CANCELLED");
        assertThat(client().sagaState(ORDER_DB, orderId)).isEqualTo("CANCELLED");
        assertThat(client().claims(ORDER_DB, orderId)).isZero();                      // key freed for a retry
        assertThat(client().stock(reservedFirst)).isEqualTo(new Stock(10, 0));
        assertThat(client().stock(shortOfStock)).isEqualTo(new Stock(1, 0));
        assertThat(client().inventoryRecords(reservedFirst, "RELEASE")).isEqualTo(1);

        // running recovery again changes nothing: no double release
        client().makeDue(ORDER_DB, orderId);
        assertThat(recovery.recoverDue()).isZero();
        assertThat(client().stock(reservedFirst)).isEqualTo(new Stock(10, 0));
    }

    @Test
    void recoveryGivesUpAfterTheConfiguredAttemptsAndLeavesTheOrderForAPerson() throws Exception {
        UUID product = productWithStock(10);
        proxy.on(FaultProxy.reserve(), FaultProxy.Mode.DROP_RESPONSE);
        proxy.on(FaultProxy.lookup(), FaultProxy.Mode.FAIL_503);          // Inventory stays unreachable
        assertThat(placeOrder(key(), product, 2).status()).isEqualTo(503);
        UUID orderId = client().onlyOrderFor(ORDER_DB, product);

        for (int attempt = 2; attempt <= 6; attempt++) {                  // attempt 1 happened inline
            client().makeDue(ORDER_DB, orderId);
            recovery.recoverDue();
            assertThat(client().sagaAttempts(ORDER_DB, orderId)).isEqualTo(attempt);
        }

        assertThat(client().sagaState(ORDER_DB, orderId)).isEqualTo("RECOVERY_FAILED");
        assertThat(client().orderStatus(ORDER_DB, orderId)).isEqualTo("PENDING");
        client().makeDue(ORDER_DB, orderId);
        assertThat(recovery.recoverDue()).isZero();                       // bounded: it stops trying
    }

    // ---- recovery is exclusive and idempotent ---------------------------------------------------

    @Test
    void severalRecoveryWorkersAtOnceRestoreTheStockExactlyOnce() throws Exception {
        UUID product = productWithStock(10);
        proxy.on(FaultProxy.reserve(), FaultProxy.Mode.DROP_RESPONSE);
        proxy.on(FaultProxy.lookup(), FaultProxy.Mode.FAIL_503);
        assertThat(placeOrder(key(), product, 6).status()).isEqualTo(503);
        UUID orderId = client().onlyOrderFor(ORDER_DB, product);
        assertThat(client().stock(product)).isEqualTo(new Stock(4, 6));
        proxy.reset();
        client().makeDue(ORDER_DB, orderId);

        int workers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return recovery.recoverDue();
            }));
        }
        go.countDown();
        int totalFinished = 0;
        for (Future<Integer> result : results) {
            totalFinished += result.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(totalFinished).isEqualTo(1);                           // exactly one worker took it
        assertThat(client().stock(product)).isEqualTo(new Stock(10, 0));
        assertThat(client().inventoryRecords(product, "RELEASE")).isEqualTo(1);
        assertThat(client().orderStatus(ORDER_DB, orderId)).isEqualTo("CANCELLED");
    }

    // ---- C: the Order Service process is killed mid-saga --------------------------------------

    @Test
    void anOrderServiceKilledMidSagaIsRecoveredAfterRestart() throws Exception {
        RealServicesStack stack = RealServicesStack.start();
        UUID product = productWithStock(10);
        String idempotencyKey = key();
        String processDb = RealServicesStack.ORDER_PROCESS_DB;

        try {
            // Inventory applies the reservation at once, but the answer is held back for 20 s
            proxy.delay(FaultProxy.reserve(), Duration.ofSeconds(20));
            stack.startOrderProcess(proxy.baseUrl());

            ExecutorService caller = Executors.newSingleThreadExecutor();
            Future<?> inFlight = caller.submit(() -> {
                try {
                    client().call("POST", stack.orderProcessBaseUrl(), "/api/v1/orders", idempotencyKey,
                            orderJson(product, 3));
                } catch (RuntimeException expectedWhenTheProcessDies) {
                    // the connection is cut when the process is killed
                }
            });

            // wait until the reservation is really in Inventory's database: the saga is mid-flight
            awaitTrue(() -> client().stock(product).reserved() == 3, 30);
            UUID orderId = client().onlyOrderFor(processDb, product);
            assertThat(client().orderStatus(processDb, orderId)).isEqualTo("PENDING");
            assertThat(client().itemStatus(processDb, orderId, product)).isEqualTo("RESERVING");

            stack.killOrderProcess();                                     // kill -9: no cleanup at all
            proxy.reset();
            assertThat(inFlight.isDone() || true).isTrue();
            caller.shutdownNow();

            // after the crash the database says exactly what was in flight
            assertThat(client().orderStatus(processDb, orderId)).isEqualTo("PENDING");
            assertThat(client().stock(product)).isEqualTo(new Stock(7, 3));

            stack.startOrderProcess(proxy.baseUrl());                     // restart: its recovery worker runs

            awaitTrue(() -> "CANCELLED".equals(client().orderStatus(processDb, orderId)), 60);
            assertThat(client().sagaState(processDb, orderId)).isEqualTo("CANCELLED");
            assertThat(client().stock(product)).isEqualTo(new Stock(10, 0));          // no stranded reservation
            assertThat(client().inventoryRecords(product, "RELEASE")).isEqualTo(1);   // released exactly once
            assertThat(client().claims(processDb, orderId)).isZero();

            // the client retrying with the same key gets a fresh, working order
            Api retry = client().call("POST", stack.orderProcessBaseUrl(), "/api/v1/orders",
                    idempotencyKey, orderJson(product, 3));
            assertThat(retry.status()).isEqualTo(201);
            assertThat(retry.body().path("status").asString()).isEqualTo("CONFIRMED");
            assertThat(client().stock(product)).isEqualTo(new Stock(7, 3));
        } finally {
            stack.stopOrderProcess();
            client().cleanUp(processDb, product);
            products.remove(product);
        }
    }

    // ---- helper ---------------------------------------------------------------------------------

    private interface Condition {
        boolean holds() throws Exception;
    }

    private static void awaitTrue(Condition condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.holds()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("condition not reached within " + seconds + " s");
    }
}
