package com.mercury.order.integration;

import com.mercury.order.client.InventoryClient;
import com.mercury.order.client.ProductClient;
import com.mercury.order.dto.CreateOrderItemRequest;
import com.mercury.order.dto.CreateOrderRequest;
import com.mercury.order.dto.InventoryOperationResult;
import com.mercury.order.dto.ProductDetails;
import com.mercury.order.inbound.AfterProcessingHook;
import com.mercury.order.inbound.InventoryEventHandler;
import com.mercury.order.model.OrderStatus;
import com.mercury.order.model.SagaState;
import com.mercury.order.outbox.OutboxEvent;
import com.mercury.order.outbox.OutboxRepository;
import com.mercury.order.repository.OrderItemRepository;
import com.mercury.order.repository.OrderRepository;
import com.mercury.order.repository.OrderSagaRepository;
import com.mercury.order.service.OrderService;
import com.mercury.order.service.SagaRecovery;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Order Service consuming Inventory's replies from a real Kafka broker: the reply decides the order, and
 * every failure on the way (duplicates, a consumer that dies after committing, transient errors, poison)
 * leaves exactly one effect and never blocks the replies behind it.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "order.reservation.mode=ASYNC",
        "order.reservation.async-deadline=10m",
        "order.inbound.enabled=true",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true",
        "order.inbound.retry.max-attempts=3",
        "order.inbound.retry.initial-interval=100ms",
        "order.inbound.retry.max-interval=300ms"
})
class InventoryEventKafkaTests {

    @Container
    static final KafkaTestBroker KAFKA = new KafkaTestBroker();

    static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    static final String EVENTS = "test.order.inventory.events." + SUFFIX;
    static final String EVENTS_DLQ = EVENTS + ".dlq";

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registry) throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.bootstrapServers()))) {
            await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(() -> {
                admin.createTopics(List.of(new NewTopic(EVENTS, 3, (short) 1), new NewTopic(EVENTS_DLQ, 1, (short) 1)))
                        .all().get(10, TimeUnit.SECONDS);
                return true;
            });
        }
        registry.add("spring.kafka.bootstrap-servers", KAFKA::bootstrapServers);
        registry.add("order.inbound.topic", () -> EVENTS);
        registry.add("order.inbound.dlq-topic", () -> EVENTS_DLQ);
    }

    @Autowired private KafkaTemplate<String, String> producer;
    @Autowired private OrderService orderService;
    @Autowired private SagaRecovery recovery;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository itemRepository;
    @Autowired private OrderSagaRepository sagaRepository;
    @Autowired private OutboxRepository outbox;
    @Autowired private MeterRegistry meters;

    @MockitoBean private ProductClient productClient;
    @MockitoBean private InventoryClient inventoryClient;
    @MockitoBean private AfterProcessingHook afterProcessing;
    @MockitoSpyBean private InventoryEventHandler handler;

    @BeforeEach
    void inventoryReleasesEverything() {
        when(inventoryClient.release(any(), anyInt(), anyString())).thenReturn(new InventoryOperationResult(false));
    }

    private record Placed(UUID orderId, UUID productId) { }

    private Placed placeOrder(int quantity) {
        UUID productId = UUID.randomUUID();
        when(productClient.getProduct(productId))
                .thenReturn(new ProductDetails(productId, "Item", "SKU-" + productId, new BigDecimal("10.00")));
        UUID orderId = orderService.createOrder("key-" + UUID.randomUUID(),
                new CreateOrderRequest(List.of(new CreateOrderItemRequest(productId, quantity)))).order().id();
        return new Placed(orderId, productId);
    }

    private static String reserved(UUID eventId, Placed order, int quantity) {
        return ("{\"eventId\":\"%s\",\"eventType\":\"InventoryReserved\",\"occurredAt\":\"2026-10-06T10:00:00Z\","
                + "\"orderId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"quantity\":%d}]}")
                .formatted(eventId, order.orderId(), order.productId(), quantity);
    }

    private static String rejected(UUID eventId, Placed order, String reason) {
        return ("{\"eventId\":\"%s\",\"eventType\":\"InventoryRejected\",\"occurredAt\":\"2026-10-06T10:00:00Z\","
                + "\"orderId\":\"%s\",\"reason\":\"%s\",\"productId\":\"%s\"}")
                .formatted(eventId, order.orderId(), reason, order.productId());
    }

    private void send(Placed order, String payload) throws Exception {
        producer.send(EVENTS, order.orderId().toString(), payload).get();
    }

    private OrderStatus status(Placed order) {
        return orderRepository.findById(order.orderId()).orElseThrow().getStatus();
    }

    private long events(Placed order, String type) {
        return outbox.findByAggregateIdOrderBySeq(order.orderId()).stream()
                .map(OutboxEvent::getEventType).filter(type::equals).count();
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

    @Test
    void anInventoryReservedReplyConfirmsThePendingOrder() throws Exception {
        Placed order = placeOrder(2);

        send(order, reserved(UUID.randomUUID(), order, 2));

        await().atMost(Duration.ofSeconds(60)).until(() -> status(order) == OrderStatus.CONFIRMED);
        assertThat(sagaRepository.findById(order.orderId()).orElseThrow().getState()).isEqualTo(SagaState.CONFIRMED);
        assertThat(events(order, "OrderConfirmed")).isEqualTo(1);
    }

    @Test
    void anInventoryRejectedReplyCancelsThePendingOrderWithTheReason() throws Exception {
        Placed order = placeOrder(2);

        send(order, rejected(UUID.randomUUID(), order, "INSUFFICIENT_STOCK"));

        await().atMost(Duration.ofSeconds(60)).until(() -> status(order) == OrderStatus.CANCELLED);
        assertThat(events(order, "OrderCancelled")).isEqualTo(1);
        assertThat(itemRepository.countHeld(order.orderId())).isZero();
    }

    @Test
    void theSameReplyDeliveredTwiceIsAppliedOnce() throws Exception {
        Placed order = placeOrder(1);
        String reply = reserved(UUID.randomUUID(), order, 1);
        double duplicatesBefore = count("order.inventory.events.duplicate");

        send(order, reply);
        send(order, reply);

        await().atMost(Duration.ofSeconds(60)).until(() -> count("order.inventory.events.duplicate") >= duplicatesBefore + 1);
        assertThat(status(order)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(events(order, "OrderConfirmed")).isEqualTo(1);
    }

    @Test
    void aConsumerThatCrashesAfterCommittingIsRedeliveredWithoutASecondEffect() throws Exception {
        Placed order = placeOrder(1);
        doThrow(new IllegalStateException("crash before acknowledging")).doNothing()
                .when(afterProcessing).afterProcessed(any());
        double duplicatesBefore = count("order.inventory.events.duplicate");

        send(order, reserved(UUID.randomUUID(), order, 1));

        await().atMost(Duration.ofSeconds(60)).until(() -> count("order.inventory.events.duplicate") >= duplicatesBefore + 1);
        assertThat(status(order)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(events(order, "OrderConfirmed")).isEqualTo(1);
        verify(afterProcessing, atLeast(2)).afterProcessed(any());
    }

    @Test
    void aTransientFailureIsRetriedAndSucceedsWithoutDeadLettering() throws Exception {
        Placed order = placeOrder(1);
        doThrow(new RuntimeException("database hiccup")).doThrow(new RuntimeException("database hiccup"))
                .doCallRealMethod().when(handler).handle(contains(order.orderId().toString()));
        double dlqBefore = count("order.inventory.events.dlq");

        send(order, reserved(UUID.randomUUID(), order, 1));

        await().atMost(Duration.ofSeconds(60)).until(() -> status(order) == OrderStatus.CONFIRMED);
        assertThat(count("order.inventory.events.dlq")).isEqualTo(dlqBefore);
        verify(handler, times(3)).handle(contains(order.orderId().toString()));
    }

    @Test
    void aPoisonEventIsDeadLetteredAndDoesNotBlockTheNextOne() throws Exception {
        String poison = "definitely not an inventory event " + UUID.randomUUID();
        double dlqBefore = count("order.inventory.events.dlq");
        producer.send(EVENTS, UUID.randomUUID().toString(), poison).get();

        Placed order = placeOrder(1);
        send(order, reserved(UUID.randomUUID(), order, 1));

        await().atMost(Duration.ofSeconds(60)).until(() -> status(order) == OrderStatus.CONFIRMED);
        await().atMost(Duration.ofSeconds(30)).until(() -> count("order.inventory.events.dlq") >= dlqBefore + 1);
        await().atMost(Duration.ofSeconds(30)).until(() ->
                readAll(EVENTS_DLQ).stream().anyMatch(r -> poison.equals(r.value())));
        verify(handler, times(1)).handle(eq(poison));   // malformed: not retried
    }

    @Test
    void aReservationThatArrivesAfterTheOrderWasCancelledIsGivenBack() throws Exception {
        Placed order = placeOrder(3);
        send(order, rejected(UUID.randomUUID(), order, "RESERVATION_TIMEOUT"));
        await().atMost(Duration.ofSeconds(60)).until(() -> status(order) == OrderStatus.CANCELLED);

        send(order, reserved(UUID.randomUUID(), order, 3));      // Inventory got there after all

        await().atMost(Duration.ofSeconds(60)).until(() ->
                sagaRepository.findById(order.orderId()).orElseThrow().getState() == SagaState.COMPENSATING);
        recovery.recoverDue();
        verify(inventoryClient).release(eq(order.productId()), eq(3), anyString());
        assertThat(sagaRepository.findById(order.orderId()).orElseThrow().getState()).isEqualTo(SagaState.CANCELLED);
        assertThat(itemRepository.countHeld(order.orderId())).isZero();
        assertThat(events(order, "OrderCancelled")).isEqualTo(1);
    }
}
