package com.mercury.inventory.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the publisher on a fixed delay. Switch off with inventory.outbox.enabled=false. */
@Component
@ConditionalOnProperty(name = "inventory.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisherWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherWorker.class);

    private final OutboxPublisher publisher;

    public OutboxPublisherWorker(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${inventory.outbox.interval:1s}", initialDelayString = "${inventory.outbox.interval:1s}")
    public void run() {
        try {
            publisher.publishDue();
        } catch (RuntimeException e) {
            log.error("outbox publish pass failed", e);
        }
    }
}
