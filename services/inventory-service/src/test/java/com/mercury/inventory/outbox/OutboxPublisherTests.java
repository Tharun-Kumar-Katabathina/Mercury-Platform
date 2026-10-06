package com.mercury.inventory.outbox;

import com.mercury.inventory.event.InventoryRejectedEvent;
import com.mercury.inventory.event.InventoryReservedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Inventory's outbox publisher against H2 with Kafka replaced by a fake sender: replies survive a
 * dead broker, stay in order per order, are sent again after a crash, and are never sent twice by racing
 * publishers.
 */
@SpringBootTest(properties = {
        "inventory.outbox.initial-backoff=50ms",
        "inventory.outbox.max-backoff=200ms",
        "inventory.outbox.lease=2s"
})
class OutboxPublisherTests {

    @Autowired private OutboxPublisher publisher;
    @Autowired private OutboxWriter writer;
    @Autowired private OutboxRepository outbox;
    @Autowired private TransactionTemplate tx;
    @Autowired private MeterRegistry meters;

    @MockitoBean private EventSender sender;
    @MockitoSpyBean private OutboxTransactions outboxTransactions;

    @BeforeEach
    void emptyOutbox() {
        outbox.deleteAll();
    }

    private UUID reserved(UUID orderId) {
        var event = InventoryReservedEvent.of(orderId, Instant.now(), List.of());
        tx.executeWithoutResult(s -> writer.append(event));
        return event.eventId();
    }

    private UUID rejected(UUID orderId) {
        var event = InventoryRejectedEvent.of(orderId, Instant.now(), "INSUFFICIENT_STOCK", UUID.randomUUID());
        tx.executeWithoutResult(s -> writer.append(event));
        return event.eventId();
    }

    private List<OutboxMessage> sent() throws Exception {
        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(sender, atLeast(0)).send(captor.capture());
        return captor.getAllValues();
    }

    private OutboxEvent row(UUID eventId) {
        return outbox.findAll().stream().filter(e -> e.getEventId().equals(eventId)).findFirst().orElseThrow();
    }

    private double counter(String name) {
        var counter = meters.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }

    @Test
    void publishesTheReplyToTheInventoryEventsTopicKeyedByOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = reserved(orderId);

        assertThat(publisher.publishDue()).isEqualTo(1);

        OutboxMessage message = sent().get(0);
        assertThat(message.eventId()).isEqualTo(eventId);
        assertThat(message.topic()).isEqualTo("mercury.inventory.events");
        assertThat(message.key()).isEqualTo(orderId.toString());
        assertThat(message.eventType()).isEqualTo("InventoryReserved");
        assertThat(row(eventId).getPublishedAt()).isNotNull();
    }

    @Test
    void whenKafkaIsDownTheReplySurvivesAndIsRetriedWithBackoff() throws Exception {
        UUID eventId = reserved(UUID.randomUUID());
        doThrow(new java.util.concurrent.TimeoutException("broker not reachable")).when(sender).send(any());
        double failedBefore = counter("events.publish.failed");

        assertThat(publisher.publishDue()).isZero();

        OutboxEvent failed = row(eventId);
        assertThat(failed.getPublishedAt()).isNull();
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("broker not reachable");
        assertThat(counter("events.publish.failed")).isEqualTo(failedBefore + 1);

        doNothing().when(sender).send(any());
        Thread.sleep(120);
        assertThat(publisher.publishDue()).isEqualTo(1);
        assertThat(row(eventId).getPublishedAt()).isNotNull();
    }

    @Test
    void eventsOfOneOrderNeverOvertakeEachOther() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID first = reserved(orderId);
        UUID second = rejected(orderId);
        doThrow(new RuntimeException("boom")).doNothing().when(sender).send(any());

        publisher.publishDue();
        assertThat(sent()).extracting(OutboxMessage::eventId).containsExactly(first);
        assertThat(row(second).getPublishedAt()).isNull();

        Thread.sleep(120);
        publisher.publishDue();
        publisher.publishDue();
        assertThat(sent()).extracting(OutboxMessage::eventId).containsExactly(first, first, second);
    }

    @Test
    void ifThePublisherDiesAfterKafkaAcceptedTheReplyItIsSentAgain() throws Exception {
        UUID eventId = reserved(UUID.randomUUID());
        doThrow(new IllegalStateException("crash before published_at"))
                .doCallRealMethod().when(outboxTransactions).markPublished(anyLong());

        assertThat(publisher.publishDue()).isZero();
        assertThat(row(eventId).getPublishedAt()).isNull();

        Thread.sleep(2_200);
        assertThat(publisher.publishDue()).isEqualTo(1);

        assertThat(sent()).extracting(OutboxMessage::eventId).containsExactly(eventId, eventId);   // at-least-once
    }

    @Test
    void racingPublishersSendEachReplyExactlyOnce() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ids.add(reserved(UUID.randomUUID()));
        }
        List<UUID> delivered = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            Thread.sleep(80);
            delivered.add(((OutboxMessage) invocation.getArgument(0)).eventId());
            return null;
        }).when(sender).send(any());

        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread thread = new Thread(() -> {
                try {
                    go.await();
                    for (int pass = 0; pass < 3; pass++) {
                        publisher.publishDue();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
            thread.start();
        }
        go.countDown();
        for (Thread thread : threads) {
            thread.join(30_000);
        }

        assertThat(delivered).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void noDatabaseTransactionIsOpenWhileWaitingForKafka() throws Exception {
        reserved(UUID.randomUUID());
        AtomicBoolean transactionWasOpen = new AtomicBoolean(true);
        doAnswer(invocation -> {
            transactionWasOpen.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(sender).send(any());

        publisher.publishDue();

        assertThat(transactionWasOpen.get()).isFalse();
    }
}
