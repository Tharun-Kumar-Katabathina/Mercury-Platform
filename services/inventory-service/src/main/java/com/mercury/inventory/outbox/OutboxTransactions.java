package com.mercury.inventory.outbox;

import com.mercury.inventory.config.OutboxProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** The publisher's short local transactions. None of them ever spans a call to Kafka. */
@Service
public class OutboxTransactions {

    private final OutboxRepository outbox;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxTransactions(OutboxRepository outbox, OutboxProperties properties, Clock clock) {
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    private Instant now() {
        return Instant.now(clock);
    }

    /** Claims due events (row-locked, skipping ones another publisher holds) and leases them. */
    @Transactional
    public List<OutboxMessage> claimDue() {
        List<OutboxEvent> due = outbox.lockDue(now(), PageRequest.of(0, properties.batchSize()));
        due.forEach(event -> event.lease(now().plus(properties.lease())));
        outbox.saveAllAndFlush(due);
        return due.stream()
                .map(e -> new OutboxMessage(e.getSeq(), e.getEventId(), e.getAggregateId(),
                        e.getEventType(), properties.topic(), e.getPayload()))
                .toList();
    }

    @Transactional
    public void markPublished(long seq) {
        OutboxEvent event = outbox.findById(seq).orElseThrow();
        event.published(now());
        outbox.saveAndFlush(event);
    }

    /** Schedules the next attempt with exponential backoff. The event is never given up on. */
    @Transactional
    public void markFailed(long seq, String error) {
        OutboxEvent event = outbox.findById(seq).orElseThrow();
        event.failed(now().plus(properties.backoffAfter(event.getAttemptCount() + 1)), error);
        outbox.saveAndFlush(event);
    }
}
