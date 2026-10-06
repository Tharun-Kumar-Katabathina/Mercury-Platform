package com.mercury.order.integration;

import com.mercury.order.integration.StackClient.Api;
import com.mercury.order.integration.StackClient.Stock;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
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
 * Event-driven processing against the REAL stack: PostgreSQL, Kafka, Product, Inventory, Order (with
 * its outbox publisher on) and the Notification Service consuming. Every assertion reads PostgreSQL
 * or Kafka directly.
 *
 *   POST /orders -> order + outbox row (one transaction) -> publisher -> Kafka -> Notification -> its DB
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "order.recovery.enabled=false",
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
        "spring.kafka.producer.acks=all",
        // the concurrency test must not trip the circuit
        "order.resilience.inventory.sliding-window-size=1000",
        "order.resilience.inventory.minimum-calls=1000"
})
class EventFlowIntegrationTests {

    @DynamicPropertySource
    static void realInfrastructure(DynamicPropertyRegistry registry) {
        RealServicesStack stack = RealServicesStack.start();
        registry.add("spring.datasource.url", stack::orderJdbcUrl);
        registry.add("spring.datasource.username", stack::dbUsername);
        registry.add("spring.datasource.password", stack::dbPassword);
        registry.add("product.service.url", stack::productBaseUrl);
        registry.add("inventory.service.url", stack::inventoryBaseUrl);
        registry.add("spring.kafka.bootstrap-servers", stack::kafkaBootstrapServers);
    }

    @Value("${local.server.port}")
    private int orderPort;

    @Autowired private JsonMapper jsonMapper;
    @Autowired private MeterRegistry meters;
    @Autowired private KafkaTemplate<String, String> producer;

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

    private UUID productWithStock(int stock) {
        UUID id = client().productWithStock("Item", "10.00", stock);
        products.add(id);
        return id;
    }

    @AfterEach
    void clean() throws Exception {
        RealServicesStack.start().unpauseKafkaIfPaused();
        client().cleanUp(ORDER_DB, products.toArray(UUID[]::new));
        products.clear();
    }

    // ---- the whole path ---------------------------------------------------------------------

    @Test
    void aConfirmedOrderReachesTheNotificationDatabaseThroughOutboxAndKafka() throws Exception {
        UUID product = productWithStock(10);

        Api response = placeOrder(product, 2);

        assertThat(response.status()).isEqualTo(201);
        UUID orderId = UUID.fromString(response.body().path("id").asString());
        // written in the same transaction as the order: two events, in order
        assertThat(client().outboxTotal(ORDER_DB, orderId)).isEqualTo(2);

        await().atMost(Duration.ofSeconds(60)).until(() -> client().notificationRows(orderId) == 1);
        assertThat(client().notificationTypes(orderId)).containsExactly("ORDER_CONFIRMED");   // Created: nothing to send
        await().atMost(Duration.ofSeconds(30)).until(() -> client().outboxUnpublished(ORDER_DB, orderId) == 0);

        // and the Notification Service's own API shows it
        Api api = client().call("GET", RealServicesStack.start().notificationBaseUrl(),
                "/api/v1/notifications/orders/" + orderId, null, null);
        assertThat(api.status()).isEqualTo(200);
        assertThat(api.body().get(0).path("type").asString()).isEqualTo("ORDER_CONFIRMED");
        assertThat(api.body().get(0).path("status").asString()).isEqualTo("RECORDED");
    }

    @Test
    void aCancelledOrderProducesAnOrderCancelledNotificationWithItsReason() throws Exception {
        UUID product = productWithStock(1);

        Api response = placeOrder(product, 5);                            // not enough stock

        assertThat(response.status()).isEqualTo(409);
        UUID orderId = client().onlyOrderFor(ORDER_DB, product);
        await().atMost(Duration.ofSeconds(60)).until(() -> client().notificationRows(orderId) == 1);
        assertThat(client().notificationTypes(orderId)).containsExactly("ORDER_CANCELLED:INSUFFICIENT_STOCK");
    }

    // ---- Kafka outage ------------------------------------------------------------------------

    @Test
    void whenKafkaIsDownOrdersStillWorkEventsWaitInTheOutboxAndAreDeliveredWhenItReturns() throws Exception {
        RealServicesStack stack = RealServicesStack.start();
        UUID product = productWithStock(10);
        double failedBefore = counter("events.publish.failed");

        stack.pauseKafka();
        UUID first;
        UUID second;
        try {
            Api a = placeOrder(product, 1);
            Api b = placeOrder(product, 1);
            // the API does not depend on Kafka: both succeed
            assertThat(a.status()).isEqualTo(201);
            assertThat(b.status()).isEqualTo(201);
            first = UUID.fromString(a.body().path("id").asString());
            second = UUID.fromString(b.body().path("id").asString());

            // the publisher keeps trying and failing; nothing is lost, nothing is published
            await().atMost(Duration.ofSeconds(60)).until(() -> counter("events.publish.failed") > failedBefore);
            assertThat(client().outboxUnpublished(ORDER_DB, first)).isEqualTo(2);
            assertThat(client().outboxUnpublished(ORDER_DB, second)).isEqualTo(2);
            assertThat(meters.get("outbox.pending").gauge().value()).isGreaterThanOrEqualTo(4);
            assertThat(client().notificationRows(first)).isZero();
        } finally {
            stack.unpauseKafka();
        }

        // Kafka is back: everything arrives, each notification exactly once
        await().atMost(Duration.ofSeconds(90)).until(() ->
                client().notificationRows(first) == 1 && client().notificationRows(second) == 1);
        await().atMost(Duration.ofSeconds(60)).until(() ->
                client().outboxUnpublished(ORDER_DB, first) == 0 && client().outboxUnpublished(ORDER_DB, second) == 0);
        assertThat(client().stock(product)).isEqualTo(new Stock(8, 2));
    }

    // ---- the Order process dies before it ever publishes -----------------------------------------

    @Test
    void anEventWrittenBeforeACrashIsPublishedAfterTheRestart() throws Exception {
        RealServicesStack stack = RealServicesStack.start();
        UUID product = productWithStock(10);
        String processDb = RealServicesStack.ORDER_PROCESS_DB;

        try {
            // an Order Service that commits orders (and their events) but never gets to publish
            stack.startOrderProcess(stack.inventoryBaseUrl(), Map.of("ORDER_OUTBOX_ENABLED", "false"));
            Api response = client().call("POST", stack.orderProcessBaseUrl(), "/api/v1/orders", key(),
                    orderJson(product, 3));
            assertThat(response.status()).isEqualTo(201);
            UUID orderId = UUID.fromString(response.body().path("id").asString());
            assertThat(client().outboxUnpublished(processDb, orderId)).isEqualTo(2);
            assertThat(client().notificationRows(orderId)).isZero();

            stack.killOrderProcess();                                      // crash: nothing was published

            // the rows are still in the database ...
            assertThat(client().outboxUnpublished(processDb, orderId)).isEqualTo(2);

            // ... and the restarted service (publisher on) finds them and publishes them
            stack.startOrderProcess(stack.inventoryBaseUrl());
            await().atMost(Duration.ofSeconds(60)).until(() -> client().notificationRows(orderId) == 1);
            await().atMost(Duration.ofSeconds(30)).until(() -> client().outboxUnpublished(processDb, orderId) == 0);
            assertThat(client().notificationTypes(orderId)).containsExactly("ORDER_CONFIRMED");
        } finally {
            stack.stopOrderProcess();
            client().cleanUp(processDb, product);
            products.remove(product);
        }
    }

    // ---- delivery guarantees seen through the real broker ---------------------------------------

    @Test
    void theSameEventDeliveredTwiceThroughKafkaHasOneEffect() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String event = "{\"eventId\":\"%s\",\"eventType\":\"OrderConfirmed\",\"occurredAt\":\"2026-10-06T10:00:00Z\",\"orderId\":\"%s\"}"
                .formatted(eventId, orderId);

        producer.send(RealServicesStack.EVENTS_TOPIC, orderId.toString(), event).get();
        producer.send(RealServicesStack.EVENTS_TOPIC, orderId.toString(), event).get();   // e.g. a publisher retry

        await().atMost(Duration.ofSeconds(60)).until(() -> client().notificationRows(orderId) >= 1);
        Thread.sleep(3_000);                                                // give the duplicate time to be processed
        assertThat(client().notificationRows(orderId)).isEqualTo(1);
        client().deleteNotification(orderId);
    }

    @Test
    void aPoisonMessageIsDeadLetteredAndDoesNotStopLaterEvents() throws Exception {
        String poison = "definitely not an event " + UUID.randomUUID();
        producer.send(RealServicesStack.EVENTS_TOPIC, UUID.randomUUID().toString(), poison).get();

        // a real order right behind it still flows end to end
        UUID product = productWithStock(10);
        UUID orderId = UUID.fromString(placeOrder(product, 1).body().path("id").asString());
        await().atMost(Duration.ofSeconds(60)).until(() -> client().notificationRows(orderId) == 1);

        // and the poison ended up in the dead-letter topic, not in the consumer's way
        await().atMost(Duration.ofSeconds(60)).until(() -> deadLetters().contains(poison));
    }

    // ---- concurrency: the Phase 8 guarantees hold, and Kafka adds no duplicate effects ------------

    @Test
    void fiftyConcurrentOrdersNeverOversellAndEachFinishedOrderProducesExactlyOneNotification() throws Exception {
        int stock = 20;
        UUID product = productWithStock(stock);

        List<Api> responses = runConcurrently(50, () -> placeOrder(product, 1));

        long confirmed = responses.stream().filter(r -> r.status() == 201).count();
        assertThat(confirmed).isLessThanOrEqualTo(stock);
        assertThat(responses.stream().filter(r -> r.status() != 201)).allMatch(r -> r.status() == 409 || r.status() == 503);
        Stock result = client().stock(product);
        assertThat(result.available() + result.reserved()).isEqualTo(stock);              // nothing lost or created
        assertThat(result.reserved()).isEqualTo((int) confirmed);                         // only confirmed orders hold stock

        List<UUID> orders = client().orderIdsFor(ORDER_DB, product);
        for (UUID orderId : orders) {
            assertThat(client().orderStatus(ORDER_DB, orderId)).isIn("CONFIRMED", "CANCELLED");   // none stuck PENDING
        }

        // every finished order is announced, exactly once, once Kafka has drained
        await().atMost(Duration.ofSeconds(120)).until(() -> {
            for (UUID orderId : orders) {
                if (client().notificationRows(orderId) != 1 || client().outboxUnpublished(ORDER_DB, orderId) != 0) {
                    return false;
                }
            }
            return true;
        });
        Thread.sleep(2_000);
        for (UUID orderId : orders) {
            assertThat(client().notificationRows(orderId)).as("notifications for %s", orderId).isEqualTo(1);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private double counter(String name) {
        var counter = meters.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    private List<String> deadLetters() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, RealServicesStack.start().kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> values = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(RealServicesStack.DLQ_TOPIC));
            for (int i = 0; i < 4; i++) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    values.add(record.value());
                }
            }
        }
        return values;
    }

    private <T> List<T> runConcurrently(int tasks, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks);
        CountDownLatch ready = new CountDownLatch(tasks);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < tasks; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await();
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(120, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }
}
