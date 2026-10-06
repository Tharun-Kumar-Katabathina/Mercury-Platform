package com.mercury.order.integration;

import com.mercury.order.integration.StackClient.Api;
import com.mercury.order.integration.StackClient.Stock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
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
import static org.awaitility.Awaitility.await;

/**
 * The asynchronous reservation against the REAL stack: PostgreSQL, Kafka, Product, Inventory (consuming
 * commands and publishing replies), Order (ASYNC mode, outbox and reply consumer on) and Notification.
 * Every assertion reads PostgreSQL directly.
 *
 *   POST /orders -> 202 -> outbox -> Kafka -> Inventory (atomic reserve) -> outbox -> Kafka -> Order -> CONFIRMED / CANCELLED
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "order.reservation.mode=ASYNC",
        "order.reservation.async-deadline=10m",
        "order.recovery.enabled=false",
        "order.inbound.enabled=true",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true",
        "order.outbox.enabled=true",
        "order.outbox.interval=300ms",
        "order.outbox.initial-backoff=500ms",
        "order.outbox.max-backoff=2s",
        "order.outbox.send-timeout=4s",
        "order.outbox.lease=5s",
        "spring.kafka.producer.properties.max.block.ms=2000",
        "spring.kafka.producer.properties.request.timeout.ms=2000",
        "spring.kafka.producer.properties.delivery.timeout.ms=4000",
        "spring.kafka.producer.properties.enable.idempotence=true",
        "spring.kafka.producer.acks=all"
})
class AsyncReservationIntegrationTests {

    private static final String ORDER_DB = RealServicesStack.ORDER_DB;

    @DynamicPropertySource
    static void realInfrastructure(DynamicPropertyRegistry registry) {
        RealServicesStack stack = RealServicesStack.start();
        registry.add("spring.datasource.url", stack::orderJdbcUrl);
        registry.add("spring.datasource.username", stack::dbUsername);
        registry.add("spring.datasource.password", stack::dbPassword);
        registry.add("product.service.url", stack::productBaseUrl);
        registry.add("inventory.service.url", stack::inventoryBaseUrl);
        registry.add("spring.kafka.bootstrap-servers", stack::kafkaBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "order-it-" + UUID.randomUUID());
    }

    @Value("${local.server.port}")
    private int orderPort;

    @Autowired private JsonMapper jsonMapper;
    @Autowired private KafkaTemplate<String, String> producer;

    private StackClient client;
    private final List<UUID> products = new ArrayList<>();

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

    private static UUID idOf(Api response) {
        return UUID.fromString(response.body().path("id").asString());
    }

    private void awaitStatus(UUID orderId, String status) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(client().orderStatus(ORDER_DB, orderId)).isEqualTo(status));
    }

    @AfterEach
    void clean() throws Exception {
        RealServicesStack stack = RealServicesStack.start();
        stack.unpauseKafkaIfPaused();
        stack.startInventoryServiceIfStopped();
        client().cleanUp(ORDER_DB, products.toArray(UUID[]::new));
        products.clear();
    }

    // ---- the happy path ---------------------------------------------------------------------

    @Test
    void anAsyncOrderIsAcceptedThenConfirmedAndTheStockIsReservedOnce() throws Exception {
        UUID product = productWithStock(10);
        String key = key();

        Api accepted = placeOrder(key, product, 3);

        assertThat(accepted.status()).isEqualTo(202);
        assertThat(accepted.body().path("status").asString()).isEqualTo("PENDING");
        UUID orderId = idOf(accepted);

        awaitStatus(orderId, "CONFIRMED");
        assertThat(client().sagaState(ORDER_DB, orderId)).isEqualTo("CONFIRMED");
        assertThat(client().itemStatus(ORDER_DB, orderId, product)).isEqualTo("RESERVED");
        assertThat(client().stock(product)).isEqualTo(new Stock(7, 3));
        assertThat(client().inventoryDecision(orderId)).isEqualTo("RESERVED");
        assertThat(client().inventoryRecords(product, "RESERVE")).isZero();      // not the per-item REST path
        assertThat(client().outboxOfType(ORDER_DB, orderId, "InventoryReservationRequested")).isEqualTo(1);
        await().atMost(Duration.ofSeconds(60)).until(() -> client().notificationRows(orderId) == 1);
        assertThat(client().notificationTypes(orderId)).containsExactly("ORDER_CONFIRMED");

        // the same request again returns the final order without reserving anything more
        Api replay = placeOrder(key, product, 3);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.replayed()).isEqualTo("true");
        assertThat(idOf(replay)).isEqualTo(orderId);
        assertThat(replay.body().path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(client().stock(product)).isEqualTo(new Stock(7, 3));
    }

    @Test
    void notEnoughStockCancelsTheOrderAndReservesNothing() throws Exception {
        UUID plentiful = productWithStock(10);
        UUID scarce = productWithStock(1);

        Api accepted = placeOrder(key(), plentiful, 2, scarce, 5);   // all or nothing

        assertThat(accepted.status()).isEqualTo(202);
        UUID orderId = idOf(accepted);
        awaitStatus(orderId, "CANCELLED");
        assertThat(client().cancelReason(ORDER_DB, orderId)).contains("INSUFFICIENT_STOCK");
        assertThat(client().stock(plentiful)).isEqualTo(new Stock(10, 0));
        assertThat(client().stock(scarce)).isEqualTo(new Stock(1, 0));
        assertThat(client().inventoryDecision(orderId)).isEqualTo("REJECTED");
        assertThat(client().itemStatus(ORDER_DB, orderId, plentiful)).isEqualTo("NOT_RESERVED");
        await().atMost(Duration.ofSeconds(60)).until(() -> client().notificationRows(orderId) == 1);
    }

    @Test
    void aCancelledOrdersKeyReturnsTheCancelledOrder() throws Exception {
        UUID product = productWithStock(1);
        String key = key();
        UUID orderId = idOf(placeOrder(key, product, 5));
        awaitStatus(orderId, "CANCELLED");

        Api replay = placeOrder(key, product, 5);

        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.replayed()).isEqualTo("true");
        assertThat(idOf(replay)).isEqualTo(orderId);
        assertThat(replay.body().path("status").asString()).isEqualTo("CANCELLED");
        assertThat(client().ordersFor(ORDER_DB, product)).isEqualTo(1);
    }

    @Test
    void anUnknownProductIsStillRejectedBeforeAnythingIsSaved() throws Exception {
        Api response = placeOrder(key(), UUID.randomUUID(), 1);

        assertThat(response.status()).isEqualTo(404);
    }

    // ---- no overselling ---------------------------------------------------------------------

    @Test
    void twentyConcurrentAsyncOrdersForFiveUnitsConfirmExactlyFive() throws Exception {
        UUID product = productWithStock(5);
        int orders = 20;
        ExecutorService pool = Executors.newFixedThreadPool(orders);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Api>> calls = new ArrayList<>();
        for (int i = 0; i < orders; i++) {
            calls.add(pool.submit(() -> {
                go.await();
                return placeOrder(key(), product, 1);
            }));
        }
        go.countDown();
        List<UUID> ids = new ArrayList<>();
        for (Future<Api> call : calls) {
            Api response = call.get(60, TimeUnit.SECONDS);
            assertThat(response.status()).isEqualTo(202);
            ids.add(idOf(response));
        }
        pool.shutdown();

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            long final_ = ids.stream().filter(id -> {
                try {
                    return !client().orderStatus(ORDER_DB, id).equals("PENDING");
                } catch (Exception e) {
                    return false;
                }
            }).count();
            assertThat(final_).isEqualTo(orders);
        });

        long confirmed = ids.stream().filter(id -> {
            try {
                return client().orderStatus(ORDER_DB, id).equals("CONFIRMED");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).count();
        assertThat(confirmed).isEqualTo(5);
        assertThat(client().stock(product)).isEqualTo(new Stock(0, 5));   // never oversold, never stranded
    }

    // ---- failures along the way -----------------------------------------------------------------

    @Test
    void ifKafkaIsDownTheOrderIsStillAcceptedAndCompletesAfterwards() throws Exception {
        RealServicesStack stack = RealServicesStack.start();
        UUID product = productWithStock(10);

        stack.pauseKafka();
        UUID orderId;
        try {
            Api accepted = placeOrder(key(), product, 2);
            assertThat(accepted.status()).isEqualTo(202);           // the outbox absorbs the outage
            orderId = idOf(accepted);
            Thread.sleep(2_000);
            assertThat(client().orderStatus(ORDER_DB, orderId)).isEqualTo("PENDING");
            assertThat(client().stock(product)).isEqualTo(new Stock(10, 0));
        } finally {
            stack.unpauseKafka();
        }

        awaitStatus(orderId, "CONFIRMED");
        assertThat(client().stock(product)).isEqualTo(new Stock(8, 2));
    }

    @Test
    void ifInventoryIsDownTheCommandWaitsInKafkaAndTheOrderCompletesWhenItReturns() throws Exception {
        RealServicesStack stack = RealServicesStack.start();
        UUID product = productWithStock(10);
        stack.stopInventoryService();

        UUID orderId = idOf(placeOrder(key(), product, 2));
        Thread.sleep(2_000);
        assertThat(client().orderStatus(ORDER_DB, orderId)).isEqualTo("PENDING");

        stack.startInventoryService();

        awaitStatus(orderId, "CONFIRMED");
        assertThat(client().stock(product)).isEqualTo(new Stock(8, 2));
    }

    @Test
    void aCommandDeliveredTwiceReservesOnce() throws Exception {
        UUID product = productWithStock(10);
        UUID orderId = idOf(placeOrder(key(), product, 3));
        awaitStatus(orderId, "CONFIRMED");

        // the same command again (as Kafka may redeliver it), under a new event id and under the same one
        for (int i = 0; i < 2; i++) {
            String command = "{\"eventId\":\"%s\",\"eventType\":\"InventoryReservationRequested\","
                    .formatted(UUID.randomUUID())
                    + "\"occurredAt\":\"2026-10-06T10:00:00Z\",\"orderId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"quantity\":3}]}"
                    .formatted(orderId, product);
            producer.send(RealServicesStack.INVENTORY_COMMANDS_TOPIC, orderId.toString(), command).get();
        }
        Thread.sleep(4_000);

        assertThat(client().stock(product)).isEqualTo(new Stock(7, 3));            // not 10 -> 4 -> 1
        assertThat(client().inventoryRepliesWritten(orderId)).isEqualTo(1);        // one decision, one reply
        assertThat(client().orderStatus(ORDER_DB, orderId)).isEqualTo("CONFIRMED");
    }

    @Test
    void aMalformedCommandIsDeadLetteredAndTheNextOrderStillCompletes() throws Exception {
        producer.send(RealServicesStack.INVENTORY_COMMANDS_TOPIC, UUID.randomUUID().toString(),
                "garbage " + UUID.randomUUID()).get();
        UUID product = productWithStock(10);

        UUID orderId = idOf(placeOrder(key(), product, 1));

        awaitStatus(orderId, "CONFIRMED");
    }
}
