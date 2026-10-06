package com.mercury.inventory.kafka;

import com.mercury.inventory.dto.CreateInventoryRequest;
import com.mercury.inventory.dto.InventoryResponse;
import com.mercury.inventory.service.InventoryService;
import com.mercury.inventory.service.OrderReservationService;
import com.mercury.inventory.outbox.OutboxTransactions;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * Inventory's asynchronous reservation against a real Kafka broker: a command comes in on
 * mercury.inventory.commands, exactly one InventoryReserved/InventoryRejected goes out on
 * mercury.inventory.events, and every failure on the way (duplicates, crashes, poison, broker down)
 * leaves exactly one effect.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=true",
        "inventory.outbox.enabled=true",
        "inventory.outbox.interval=200ms",
        "inventory.outbox.lease=2s",
        "inventory.outbox.initial-backoff=300ms",
        "inventory.outbox.max-backoff=1s",
        "inventory.outbox.send-timeout=3s",
        "spring.kafka.producer.properties.max.block.ms=2000",
        "spring.kafka.producer.properties.request.timeout.ms=2000",
        "spring.kafka.producer.properties.delivery.timeout.ms=4000",
        "spring.kafka.producer.properties.enable.idempotence=true",
        "spring.kafka.producer.acks=all",
        "inventory.retry.max-attempts=3",
        "inventory.retry.initial-interval=100ms",
        "inventory.retry.max-interval=300ms",
        "inventory.reserve.max-attempts=30"
})
class ReservationCommandKafkaTests {

    @Container
    static final KafkaTestBroker KAFKA = new KafkaTestBroker();

    static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    static final String COMMANDS = "test.inventory.commands." + SUFFIX;
    static final String COMMANDS_DLQ = COMMANDS + ".dlq";
    static final String EVENTS = "test.inventory.events." + SUFFIX;

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registry) throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.bootstrapServers()))) {
            await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(() -> {
                admin.createTopics(List.of(new NewTopic(COMMANDS, 3, (short) 1),
                        new NewTopic(COMMANDS_DLQ, 1, (short) 1), new NewTopic(EVENTS, 3, (short) 1)))
                        .all().get(10, TimeUnit.SECONDS);
                return true;
            });
        }
        registry.add("spring.kafka.bootstrap-servers", KAFKA::bootstrapServers);
        registry.add("inventory.command-topic", () -> COMMANDS);
        registry.add("inventory.command-dlq-topic", () -> COMMANDS_DLQ);
        registry.add("inventory.outbox.topic", () -> EVENTS);
    }

    @Autowired private KafkaTemplate<String, String> producer;
    @Autowired private InventoryService inventoryService;
    @Autowired private OrderReservationService reservations;
    @Autowired private MeterRegistry meters;
    @Autowired private JsonMapper json;

    @MockitoBean private AfterProcessingHook afterProcessing;
    @MockitoSpyBean private ReservationCommandHandler handler;
    @MockitoSpyBean private OutboxTransactions outboxTransactions;

    private UUID product(int stock) {
        UUID id = UUID.randomUUID();
        inventoryService.createInventory(new CreateInventoryRequest(id, stock));
        return id;
    }

    private InventoryResponse stock(UUID productId) {
        return inventoryService.getInventory(productId);
    }

    private static String command(UUID eventId, UUID orderId, Object... productAndQuantity) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < productAndQuantity.length; i += 2) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"productId\":\"%s\",\"quantity\":%d}".formatted(productAndQuantity[i], productAndQuantity[i + 1]));
        }
        return ("{\"eventId\":\"%s\",\"eventType\":\"InventoryReservationRequested\","
                + "\"occurredAt\":\"2026-10-06T10:00:00Z\",\"orderId\":\"%s\",\"items\":[%s]}").formatted(eventId, orderId, items);
    }

    private void send(UUID orderId, String payload) throws Exception {
        producer.send(COMMANDS, orderId.toString(), payload).get();
    }

    private double count(String metric) {
        var counter = meters.find(metric).counter();
        return counter == null ? 0 : counter.count();
    }

    private List<ConsumerRecord<String, String>> readAll(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> all = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            for (int i = 0; i < 4; i++) {
                consumer.poll(Duration.ofMillis(500)).forEach(all::add);
            }
        }
        return all;
    }

    /** the replies published for one order, as parsed JSON */
    private List<JsonNode> repliesFor(UUID orderId) {
        return readAll(EVENTS).stream()
                .filter(r -> orderId.toString().equals(r.key()))
                .map(r -> json.readTree(r.value()))
                .toList();
    }

    @Test
    void aReservationCommandProducesOneInventoryReservedReplyAndReservesTheStock() throws Exception {
        UUID a = product(10);
        UUID b = product(5);
        UUID orderId = UUID.randomUUID();

        send(orderId, command(UUID.randomUUID(), orderId, a, 3, b, 2));

        await().atMost(Duration.ofSeconds(60)).until(() -> !repliesFor(orderId).isEmpty());
        Thread.sleep(2_000);
        List<JsonNode> replies = repliesFor(orderId);
        assertThat(replies).hasSize(1);
        assertThat(replies.get(0).path("eventType").asString()).isEqualTo("InventoryReserved");
        assertThat(replies.get(0).path("items").size()).isEqualTo(2);
        assertThat(stock(a).availableQuantity()).isEqualTo(7);
        assertThat(stock(b).availableQuantity()).isEqualTo(3);
    }

    @Test
    void insufficientStockProducesOneInventoryRejectedReplyAndChangesNothing() throws Exception {
        UUID plentiful = product(10);
        UUID scarce = product(1);
        UUID orderId = UUID.randomUUID();

        send(orderId, command(UUID.randomUUID(), orderId, plentiful, 2, scarce, 5));

        await().atMost(Duration.ofSeconds(60)).until(() -> !repliesFor(orderId).isEmpty());
        List<JsonNode> replies = repliesFor(orderId);
        assertThat(replies).hasSize(1);
        assertThat(replies.get(0).path("eventType").asString()).isEqualTo("InventoryRejected");
        assertThat(replies.get(0).path("reason").asString()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(replies.get(0).path("productId").asString()).isEqualTo(scarce.toString());
        assertThat(stock(plentiful).availableQuantity()).isEqualTo(10);   // all or nothing
        assertThat(stock(plentiful).reservedQuantity()).isZero();
    }

    @Test
    void theSameCommandDeliveredTwiceReservesOnceAndRepliesOnce() throws Exception {
        UUID a = product(10);
        UUID orderId = UUID.randomUUID();
        String command = command(UUID.randomUUID(), orderId, a, 3);
        double duplicatesBefore = count("inventory.commands.duplicate");

        send(orderId, command);
        send(orderId, command);

        await().atMost(Duration.ofSeconds(60)).until(() -> count("inventory.commands.duplicate") >= duplicatesBefore + 1);
        Thread.sleep(2_000);
        assertThat(stock(a).availableQuantity()).isEqualTo(7);            // 10 -> 7, not 10 -> 4
        assertThat(repliesFor(orderId)).hasSize(1);
    }

    @Test
    void aSecondCommandForTheSameOrderWithAnotherEventIdIsIgnored() throws Exception {
        UUID a = product(10);
        UUID orderId = UUID.randomUUID();
        double duplicatesBefore = count("inventory.commands.duplicate");

        send(orderId, command(UUID.randomUUID(), orderId, a, 3));
        send(orderId, command(UUID.randomUUID(), orderId, a, 3));

        await().atMost(Duration.ofSeconds(60)).until(() -> count("inventory.commands.duplicate") >= duplicatesBefore + 1);
        assertThat(stock(a).reservedQuantity()).isEqualTo(3);
        assertThat(repliesFor(orderId)).hasSize(1);
    }

    @Test
    void aConsumerThatCrashesAfterCommittingIsRedeliveredWithoutASecondReservation() throws Exception {
        UUID a = product(10);
        UUID orderId = UUID.randomUUID();
        // the reservation is committed, then the consumer "dies" before Kafka is told the command is done
        doThrow(new IllegalStateException("crash before acknowledging")).doNothing()
                .when(afterProcessing).afterProcessed(any());
        double duplicatesBefore = count("inventory.commands.duplicate");

        send(orderId, command(UUID.randomUUID(), orderId, a, 4));

        await().atMost(Duration.ofSeconds(60)).until(() -> count("inventory.commands.duplicate") >= duplicatesBefore + 1);
        assertThat(stock(a).availableQuantity()).isEqualTo(6);             // delivered twice, reserved once
        assertThat(repliesFor(orderId)).hasSize(1);
        verify(afterProcessing, atLeast(2)).afterProcessed(any());
    }

    @Test
    void aTransientFailureIsRetriedAndSucceedsWithoutDeadLettering() throws Exception {
        UUID a = product(10);
        UUID orderId = UUID.randomUUID();
        doThrow(new RuntimeException("database hiccup")).doThrow(new RuntimeException("database hiccup"))
                .doCallRealMethod().when(handler).handle(contains(orderId.toString()));
        double dlqBefore = count("inventory.commands.dlq");

        send(orderId, command(UUID.randomUUID(), orderId, a, 1));

        await().atMost(Duration.ofSeconds(60)).until(() -> !repliesFor(orderId).isEmpty());
        assertThat(count("inventory.commands.dlq")).isEqualTo(dlqBefore);
        verify(handler, times(3)).handle(contains(orderId.toString()));
        assertThat(stock(a).availableQuantity()).isEqualTo(9);
    }

    @Test
    void aPoisonCommandIsDeadLetteredAndDoesNotBlockTheNextOne() throws Exception {
        String poison = "not a command at all " + UUID.randomUUID();
        double dlqBefore = count("inventory.commands.dlq");
        producer.send(COMMANDS, UUID.randomUUID().toString(), poison).get();

        UUID a = product(10);
        UUID orderId = UUID.randomUUID();
        send(orderId, command(UUID.randomUUID(), orderId, a, 2));      // a good command right behind it

        await().atMost(Duration.ofSeconds(60)).until(() -> !repliesFor(orderId).isEmpty());
        await().atMost(Duration.ofSeconds(30)).until(() -> count("inventory.commands.dlq") >= dlqBefore + 1);
        await().atMost(Duration.ofSeconds(30)).until(() ->
                readAll(COMMANDS_DLQ).stream().anyMatch(r -> poison.equals(r.value())));
        verify(handler, times(1)).handle(org.mockito.ArgumentMatchers.eq(poison));   // not retried
        assertThat(stock(a).availableQuantity()).isEqualTo(8);
    }

    @Test
    void aMissingProductIsAnInventoryRejectedReply() throws Exception {
        UUID orderId = UUID.randomUUID();

        send(orderId, command(UUID.randomUUID(), orderId, UUID.randomUUID(), 1));

        await().atMost(Duration.ofSeconds(60)).until(() -> !repliesFor(orderId).isEmpty());
        assertThat(repliesFor(orderId).get(0).path("reason").asString()).isEqualTo("INVENTORY_NOT_FOUND");
    }

    @Test
    void ifKafkaIsDownWhenTheReplyIsWrittenItWaitsInTheOutboxAndIsPublishedAfterwards() throws Exception {
        UUID a = product(10);
        UUID orderId = UUID.randomUUID();

        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            // the reservation itself needs only the database: it succeeds and the reply is stored durably
            reservations.reserve(new com.mercury.inventory.event.ReservationCommand(
                    UUID.randomUUID(), "InventoryReservationRequested", java.time.Instant.now(), orderId,
                    List.of(new com.mercury.inventory.event.ReservationCommand.Item(a, 3))));
            assertThat(stock(a).availableQuantity()).isEqualTo(7);
            await().atMost(Duration.ofSeconds(30)).until(() -> count("events.publish.failed") > 0);
        } finally {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(90)).until(() -> !repliesFor(orderId).isEmpty());
        // Delivery is at-least-once: a send that timed out while the broker was frozen may still land once it
        // resumes, next to the retry. Both copies carry the SAME event id, so there is exactly one distinct
        // reply and a consumer ignores the repeat.
        assertThat(repliesFor(orderId).stream().map(r -> r.path("eventId").asString()).distinct()).hasSize(1);
        assertThat(stock(a).availableQuantity()).isEqualTo(7);             // and the stock was reserved only once
    }

    @Test
    void ifThePublisherDiesAfterKafkaAcceptedTheReplyItIsSentAgainWithTheSameEventId() throws Exception {
        UUID a = product(10);
        UUID orderId = UUID.randomUUID();
        // Kafka acknowledges the reply, then "the process dies" before published_at is saved
        doThrow(new IllegalStateException("crash before published_at"))
                .doCallRealMethod().when(outboxTransactions).markPublished(anyLong());

        send(orderId, command(UUID.randomUUID(), orderId, a, 1));

        await().atMost(Duration.ofSeconds(60)).until(() -> repliesFor(orderId).size() >= 2);
        List<JsonNode> replies = repliesFor(orderId);
        // at-least-once: a duplicate, with the SAME event id so a consumer can ignore it
        assertThat(replies.stream().map(r -> r.path("eventId").asString()).distinct()).hasSize(1);
        assertThat(stock(a).availableQuantity()).isEqualTo(9);             // and the stock was reserved only once
    }
}
