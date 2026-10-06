package com.mercury.inventory.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Publishes outbox events to Kafka. At-least-once: an event is marked published only AFTER the broker
 * acknowledged it, so a crash in between publishes it again after restart. Consumers deduplicate by
 * event id.
 *
 * No database transaction is open while waiting for Kafka: claim (short transaction), send (none),
 * mark (short transaction). Several publishers can run at once; claiming is row-locked with a lease.
 */
@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxTransactions transactions;
    private final EventSender sender;
    private final EventMetrics metrics;

    public OutboxPublisher(OutboxTransactions transactions, EventSender sender, EventMetrics metrics) {
        this.transactions = transactions;
        this.sender = sender;
        this.metrics = metrics;
    }

    /** @return how many events were published in this pass */
    public int publishDue() {

        List<OutboxMessage> claimed = transactions.claimDue();
        int published = 0;

        for (OutboxMessage message : claimed) {
            try {
                sender.send(message);
            } catch (Exception e) {
                metrics.publishFailed();
                log.warn("outbox eventId={} type={} orderId={} result=PUBLISH_FAILED reason={}: {}",
                        message.eventId(), message.eventType(), message.orderId(),
                        e.getClass().getSimpleName(), e.getMessage());
                transactions.markFailed(message.seq(), e.getClass().getSimpleName() + ": " + e.getMessage());
                continue;
            }

            try {
                transactions.markPublished(message.seq());
            } catch (RuntimeException e) {
                // Kafka has it, but we could not record that. The lease expires and the event is
                // sent again: a duplicate, which consumers ignore.
                log.error("outbox eventId={} was published but could not be marked; it will be sent again",
                        message.eventId(), e);
                continue;
            }
            metrics.published();
            published++;
            log.info("outbox eventId={} type={} orderId={} result=PUBLISHED",
                    message.eventId(), message.eventType(), message.orderId());
        }
        return published;
    }
}
