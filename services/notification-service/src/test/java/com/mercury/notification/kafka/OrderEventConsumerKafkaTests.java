package com.mercury.notification.kafka;

import com.mercury.notification.event.InvalidEventException;
import com.mercury.notification.repository.NotificationRepository;
import com.mercury.notification.service.NotificationEventHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * The consumer against a real Kafka broker (Testcontainers): duplicates, a consumer that "crashes"
 * after processing, transient and permanent failures, and poison messages going to the dead-letter topic.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=true",
        "notification.retry.max-attempts=3",
        "notification.retry.initial-interval=100ms",
        "notification.retry.max-interval=300ms"
})
class OrderEventConsumerKafkaTests {

    @Container
    static final KafkaTestBroker KAFKA = new KafkaTestBroker();

    // one name for the whole run (a supplier that generated a new one per read broke every consumer)
    static final String TOPIC = "test.order.events." + UUID.randomUUID();
    static final String DLQ_TOPIC = TOPIC + ".dlq";

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registry) throws Exception {
        // create the topics up front, once the broker really answers, instead of racing the app's start-up
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.bootstrapServers()))) {
            await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(() -> {
                admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1), new NewTopic(DLQ_TOPIC, 1, (short) 1)))
                        .all().get(10, TimeUnit.SECONDS);
                return true;
            });
        }
        registry.add("spring.kafka.bootstrap-servers", KAFKA::bootstrapServers);
        registry.add("notification.topic", () -> TOPIC);
        registry.add("notification.dlq-topic", () -> DLQ_TOPIC);
    }

    @Autowired private KafkaTemplate<String, String> producer;
    @Autowired private NotificationRepository notifications;
    @Autowired private MeterRegistry meters;
    @org.springframework.beans.factory.annotation.Value("${notification.topic}") private String topic;
    @org.springframework.beans.factory.annotation.Value("${notification.dlq-topic}") private String dlqTopic;

    @MockitoBean private AfterProcessingHook afterProcessing;
    @MockitoSpyBean private NotificationEventHandler handler;

    private static String json(String type, UUID eventId, UUID orderId, String extra) {
        return "{\"eventId\":\"%s\",\"eventType\":\"%s\",\"occurredAt\":\"2026-10-06T10:00:00Z\",\"orderId\":\"%s\"%s}"
                .formatted(eventId, type, orderId, extra);
    }

    private void publish(UUID orderId, String payload) throws Exception {
        producer.send(topic, orderId.toString(), payload).get();
    }

    private double count(String metric) {
        var counter = meters.find(metric).counter();
        return counter == null ? 0 : counter.count();
    }

    private List<String> deadLetters() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> values = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(dlqTopic));
            for (int i = 0; i < 4; i++) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    values.add(record.value());
                }
            }
        }
        return values;
    }

    @Test
    void anEventPublishedToKafkaBecomesANotification() throws Exception {
        UUID orderId = UUID.randomUUID();

        publish(orderId, json("OrderConfirmed", UUID.randomUUID(), orderId, ""));

        await().atMost(Duration.ofSeconds(30))
                .until(() -> !notifications.findByOrderIdOrderByCreatedAt(orderId).isEmpty());
        assertThat(notifications.findByOrderIdOrderByCreatedAt(orderId).get(0).getType().name())
                .isEqualTo("ORDER_CONFIRMED");
    }

    @Test
    void eventsOfOneOrderAreProcessedInOrder() throws Exception {
        UUID orderId = UUID.randomUUID();

        publish(orderId, json("OrderCreated", UUID.randomUUID(), orderId, ",\"items\":[]"));
        publish(orderId, json("OrderCancelled", UUID.randomUUID(), orderId, ",\"reason\":\"INSUFFICIENT_STOCK\""));

        await().atMost(Duration.ofSeconds(30))
                .until(() -> !notifications.findByOrderIdOrderByCreatedAt(orderId).isEmpty());
        assertThat(notifications.findByOrderIdOrderByCreatedAt(orderId)).hasSize(1);   // Created: nothing to notify
    }

    @Test
    void theSameEventDeliveredTwiceHasOneEffect() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String event = json("OrderConfirmed", eventId, orderId, "");
        double duplicatesBefore = count("events.duplicate");

        publish(orderId, event);
        publish(orderId, event);          // e.g. the publisher retried after a crash

        await().atMost(Duration.ofSeconds(30)).until(() -> count("events.duplicate") >= duplicatesBefore + 1);
        assertThat(notifications.countByEventId(eventId)).isEqualTo(1);
    }

    @Test
    void aConsumerThatCrashesAfterProcessingIsRedeliveredTheEventWithoutADuplicateEffect() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        // the row is committed, then the consumer "dies" before Kafka is told the record is done
        doThrow(new IllegalStateException("crash before acknowledging")).doNothing()
                .when(afterProcessing).afterProcessed(any());
        double duplicatesBefore = count("events.duplicate");

        publish(orderId, json("OrderConfirmed", eventId, orderId, ""));

        await().atMost(Duration.ofSeconds(30)).until(() -> count("events.duplicate") >= duplicatesBefore + 1);
        assertThat(notifications.countByEventId(eventId)).isEqualTo(1);   // delivered twice, effect once
        verify(afterProcessing, atLeast(2)).afterProcessed(any());
    }

    @Test
    void aTransientFailureIsRetriedAndSucceedsWithoutDeadLettering() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        doThrow(new RuntimeException("database hiccup")).doThrow(new RuntimeException("database hiccup"))
                .doCallRealMethod().when(handler).handle(contains(eventId.toString()));
        double dlqBefore = count("events.dlq");

        publish(orderId, json("OrderConfirmed", eventId, orderId, ""));

        await().atMost(Duration.ofSeconds(30)).until(() -> notifications.countByEventId(eventId) == 1);
        assertThat(count("events.dlq")).isEqualTo(dlqBefore);
        verify(handler, times(3)).handle(contains(eventId.toString()));
    }

    @Test
    void aMessageThatAlwaysFailsIsRetriedBoundedlyThenDeadLettered() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String event = json("OrderConfirmed", eventId, orderId, "");
        doThrow(new RuntimeException("always broken")).when(handler).handle(contains(eventId.toString()));
        double dlqBefore = count("events.dlq");

        publish(orderId, event);

        await().atMost(Duration.ofSeconds(30)).until(() -> count("events.dlq") >= dlqBefore + 1);
        verify(handler, times(3)).handle(contains(eventId.toString()));        // max-attempts, not forever
        assertThat(notifications.countByEventId(eventId)).isZero();
        await().atMost(Duration.ofSeconds(30)).until(() -> deadLetters().contains(event));
    }

    @Test
    void aPoisonMessageGoesStraightToTheDeadLetterTopicAndTheConsumerKeepsWorking() throws Exception {
        UUID orderId = UUID.randomUUID();
        String poison = "{ this is not json " + UUID.randomUUID();
        double dlqBefore = count("events.dlq");

        publish(orderId, poison);
        UUID eventId = UUID.randomUUID();
        publish(orderId, json("OrderConfirmed", eventId, orderId, ""));   // a good event BEHIND the poison

        await().atMost(Duration.ofSeconds(30)).until(() -> notifications.countByEventId(eventId) == 1);
        assertThat(count("events.dlq")).isGreaterThanOrEqualTo(dlqBefore + 1);
        await().atMost(Duration.ofSeconds(30)).until(() -> deadLetters().contains(poison));
        // not retried: malformed input can never succeed
        verify(handler, times(1)).handle(eq(poison));
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    @SuppressWarnings("unused")
    private static final Class<?> KEEP_IMPORT = InvalidEventException.class;
}
