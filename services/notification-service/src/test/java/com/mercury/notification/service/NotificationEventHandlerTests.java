package com.mercury.notification.service;

import com.mercury.notification.event.InvalidEventException;
import com.mercury.notification.model.Notification;
import com.mercury.notification.model.NotificationType;
import com.mercury.notification.repository.NotificationRepository;
import com.mercury.notification.service.NotificationEventHandler.Outcome;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class NotificationEventHandlerTests {

    @Autowired private NotificationEventHandler handler;
    @Autowired private NotificationRepository notifications;
    @Autowired private MeterRegistry meters;

    private static String json(String type, UUID eventId, UUID orderId, String extra) {
        return "{\"eventId\":\"%s\",\"eventType\":\"%s\",\"occurredAt\":\"2026-10-06T10:00:00Z\",\"orderId\":\"%s\"%s}"
                .formatted(eventId, type, orderId, extra);
    }

    private double count(String metric) {
        var counter = meters.find(metric).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void anOrderConfirmedEventBecomesANotification() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        assertThat(handler.handle(json("OrderConfirmed", eventId, orderId, ""))).isEqualTo(Outcome.RECORDED);

        List<Notification> recorded = notifications.findByOrderIdOrderByCreatedAt(orderId);
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).getEventId()).isEqualTo(eventId);
        assertThat(recorded.get(0).getType()).isEqualTo(NotificationType.ORDER_CONFIRMED);
        assertThat(recorded.get(0).getStatus()).isEqualTo("RECORDED");
    }

    @Test
    void anOrderCancelledEventKeepsItsReason() {
        UUID orderId = UUID.randomUUID();

        handler.handle(json("OrderCancelled", UUID.randomUUID(), orderId, ",\"reason\":\"INSUFFICIENT_STOCK\""));

        Notification n = notifications.findByOrderIdOrderByCreatedAt(orderId).get(0);
        assertThat(n.getType()).isEqualTo(NotificationType.ORDER_CANCELLED);
        assertThat(n.getDetail()).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void orderCreatedAndUnknownTypesAreValidButProduceNothing() {
        UUID orderId = UUID.randomUUID();

        assertThat(handler.handle(json("OrderCreated", UUID.randomUUID(), orderId,
                ",\"items\":[],\"totalAmount\":1"))).isEqualTo(Outcome.IGNORED);
        assertThat(handler.handle(json("SomethingFromTheFuture", UUID.randomUUID(), orderId, "")))
                .isEqualTo(Outcome.IGNORED);

        assertThat(notifications.findByOrderIdOrderByCreatedAt(orderId)).isEmpty();
    }

    @Test
    void unknownFieldsAreTolerated() {
        UUID orderId = UUID.randomUUID();

        assertThat(handler.handle(json("OrderConfirmed", UUID.randomUUID(), orderId,
                ",\"addedLater\":{\"x\":1}"))).isEqualTo(Outcome.RECORDED);
    }

    @Test
    void theSameEventDeliveredAgainHasNoSecondEffect() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String event = json("OrderConfirmed", eventId, orderId, "");
        double duplicatesBefore = count("events.duplicate");

        assertThat(handler.handle(event)).isEqualTo(Outcome.RECORDED);
        assertThat(handler.handle(event)).isEqualTo(Outcome.DUPLICATE);
        assertThat(handler.handle(event)).isEqualTo(Outcome.DUPLICATE);

        assertThat(notifications.countByEventId(eventId)).isEqualTo(1);
        assertThat(count("events.duplicate")).isEqualTo(duplicatesBefore + 2);
    }

    @Test
    void simultaneousDeliveriesOfOneEventRecordItOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        String event = json("OrderConfirmed", eventId, UUID.randomUUID(), "");
        int deliveries = 30;
        ExecutorService pool = Executors.newFixedThreadPool(deliveries);
        CountDownLatch ready = new CountDownLatch(deliveries);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> results = new ArrayList<>();
        for (int i = 0; i < deliveries; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return handler.handle(event);
            }));
        }
        ready.await();
        go.countDown();
        int recorded = 0;
        for (Future<Outcome> result : results) {
            if (result.get(30, TimeUnit.SECONDS) == Outcome.RECORDED) {
                recorded++;
            }
        }
        pool.shutdown();

        assertThat(recorded).isEqualTo(1);
        assertThat(notifications.countByEventId(eventId)).isEqualTo(1);
    }

    @Test
    void messagesThatCanNeverBeProcessedAreRejectedAsInvalid() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> handler.handle("this is not json"))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> handler.handle("{\"eventType\":\"OrderConfirmed\",\"orderId\":\"" + id + "\"}"))
                .isInstanceOf(InvalidEventException.class);                       // no eventId
        assertThatThrownBy(() -> handler.handle("{\"eventId\":\"" + id + "\",\"eventType\":\"OrderConfirmed\"}"))
                .isInstanceOf(InvalidEventException.class);                       // no orderId
        assertThatThrownBy(() -> handler.handle("{\"eventId\":\"not-a-uuid\",\"eventType\":\"OrderConfirmed\"}"))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> handler.handle(null))
                .isInstanceOf(InvalidEventException.class);
    }
}
