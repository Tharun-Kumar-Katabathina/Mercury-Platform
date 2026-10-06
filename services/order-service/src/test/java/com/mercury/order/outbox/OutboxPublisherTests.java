package com.mercury.order.outbox;

import com.mercury.order.event.OrderConfirmedEvent;
import com.mercury.order.event.OrderCreatedEvent;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * The outbox publisher against a real (H2) database with Kafka replaced by a fake sender, so each
 * failure (broker down, crash after the broker accepted an event, racing publishers) is exact.
 */
@SpringBootTest(properties = {
        "order.outbox.initial-backoff=50ms",
        "order.outbox.max-backoff=200ms",
        "order.outbox.lease=2s"
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

    private UUID eventFor(UUID orderId, boolean created) {
        var event = created
                ? OrderCreatedEvent.of(orderId, Instant.now(), List.of(), BigDecimal.ZERO)
                : OrderConfirmedEvent.of(orderId, Instant.now());
        tx.executeWithoutResult(status -> writer.append(event));
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

    @Test
    void publishesEventsToTheTopicKeyedByOrderAndMarksThemPublished() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID created = eventFor(orderId, true);

        assertThat(publisher.publishDue()).isEqualTo(1);

        OutboxMessage message = sent().get(0);
        assertThat(message.eventId()).isEqualTo(created);
        assertThat(message.topic()).isEqualTo("mercury.order.events");
        assertThat(message.key()).isEqualTo(orderId.toString());          // same order, same partition, in order
        assertThat(message.eventType()).isEqualTo("OrderCreated");
        assertThat(message.payload()).contains(orderId.toString());
        assertThat(row(created).getPublishedAt()).isNotNull();
    }

    @Test
    void anEventIsPublishedOnceNotOnEveryPass() throws Exception {
        eventFor(UUID.randomUUID(), true);

        publisher.publishDue();
        publisher.publishDue();
        publisher.publishDue();

        assertThat(sent()).hasSize(1);
    }

    @Test
    void whenKafkaIsDownTheEventSurvivesAndIsRetriedWithBackoff() throws Exception {
        UUID created = eventFor(UUID.randomUUID(), true);
        doThrow(new java.util.concurrent.TimeoutException("broker not reachable")).when(sender).send(any());
        double failedBefore = counter("events.publish.failed");

        assertThat(publisher.publishDue()).isZero();                       // no exception escapes

        OutboxEvent failed = row(created);
        assertThat(failed.getPublishedAt()).isNull();                      // still there, not lost
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("broker not reachable");
        assertThat(failed.getNextAttemptAt()).isAfter(Instant.now().minusSeconds(1));
        assertThat(counter("events.publish.failed")).isEqualTo(failedBefore + 1);

        // not retried before its backoff has elapsed
        assertThat(publisher.publishDue()).isZero();
        verify(sender, times(1)).send(any());

        // Kafka returns
        doNothing().when(sender).send(any());
        Thread.sleep(120);
        assertThat(publisher.publishDue()).isEqualTo(1);
        assertThat(row(created).getPublishedAt()).isNotNull();
    }

    @Test
    void eventsOfOneOrderNeverOvertakeEachOtherEvenAcrossFailures() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID first = eventFor(orderId, true);
        UUID second = eventFor(orderId, false);
        doThrow(new RuntimeException("boom")).doNothing().when(sender).send(any());

        publisher.publishDue();                                            // first fails; second must NOT be sent
        assertThat(sent()).extracting(OutboxMessage::eventId).containsExactly(first);
        assertThat(row(second).getPublishedAt()).isNull();

        Thread.sleep(120);
        publisher.publishDue();                                            // first goes out
        publisher.publishDue();                                            // only now the second
        assertThat(sent()).extracting(OutboxMessage::eventId).containsExactly(first, first, second);
        assertThat(row(first).getPublishedAt()).isNotNull();
        assertThat(row(second).getPublishedAt()).isNotNull();
    }

    @Test
    void aFailingOrderDoesNotBlockOtherOrders() throws Exception {
        UUID badOrder = UUID.randomUUID();
        UUID goodOrder = UUID.randomUUID();
        UUID bad = eventFor(badOrder, true);
        UUID good = eventFor(goodOrder, true);
        doAnswer(invocation -> {
            if (((OutboxMessage) invocation.getArgument(0)).orderId().equals(badOrder)) {
                throw new RuntimeException("poisoned");
            }
            return null;
        }).when(sender).send(any());

        assertThat(publisher.publishDue()).isEqualTo(1);

        assertThat(row(good).getPublishedAt()).isNotNull();
        assertThat(row(bad).getPublishedAt()).isNull();
    }

    @Test
    void ifTheProcessDiesAfterKafkaAcceptedTheEventItIsSentAgain() throws Exception {
        UUID created = eventFor(UUID.randomUUID(), true);
        // Kafka acknowledges, then "the process dies" before the published flag is saved
        doThrow(new IllegalStateException("crash before published_at"))
                .doCallRealMethod().when(outboxTransactions).markPublished(anyLong());

        assertThat(publisher.publishDue()).isZero();
        assertThat(row(created).getPublishedAt()).isNull();                // not recorded

        // after restart the lease has expired (here: wait it out) and the event is sent again
        Thread.sleep(2_200);
        assertThat(publisher.publishDue()).isEqualTo(1);

        assertThat(sent()).extracting(OutboxMessage::eventId).containsExactly(created, created);   // a duplicate: at-least-once
        assertThat(row(created).getPublishedAt()).isNotNull();
    }

    @Test
    void racingPublishersSendEachEventExactlyOnce() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ids.add(eventFor(UUID.randomUUID(), true));
        }
        List<UUID> delivered = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            Thread.sleep(80);                                              // keep publishers overlapping
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

        assertThat(delivered).containsExactlyInAnyOrderElementsOf(ids);   // each exactly once
    }

    @Test
    void noDatabaseTransactionIsOpenWhileWaitingForKafka() throws Exception {
        eventFor(UUID.randomUUID(), true);
        AtomicBoolean transactionWasOpen = new AtomicBoolean(true);
        doAnswer(invocation -> {
            transactionWasOpen.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(sender).send(any());

        publisher.publishDue();

        assertThat(transactionWasOpen.get()).isFalse();
    }

    @Test
    void publishedEventsAreCounted() throws Exception {
        double before = counter("events.published");
        eventFor(UUID.randomUUID(), true);
        eventFor(UUID.randomUUID(), true);

        publisher.publishDue();

        assertThat(counter("events.published")).isEqualTo(before + 2);
        assertThat(meters.get("outbox.pending").gauge().value()).isZero();
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
