package com.mercury.inventory.service;

import com.mercury.inventory.model.IdempotencyOperation;
import com.mercury.inventory.repository.IdempotencyRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Removes fence tombstones (idempotency records with operation FENCED) that are old enough to be of no use.
 *
 * A tombstone exists to refuse a reserve that arrives after the caller gave up on its key. That reserve is a request
 * that was in flight or retried when the order was cancelled, so it can only arrive within the caller's timeouts and
 * retries of that moment, minutes at the very most. The retention is far beyond that (7 days by default), so deleting
 * an expired tombstone cannot let a late reserve through; it only stops the table growing by one row per cancelled
 * order for ever. RESERVE and RELEASE records are never touched: they are what makes a client's retry of a
 * successful call return the original answer, and they have no such bound.
 *
 * Switch off with inventory.fence.cleanup.enabled=false.
 */
@Component
@ConditionalOnProperty(name = "inventory.fence.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class FenceTombstoneCleanup {

    private static final Logger log = LoggerFactory.getLogger(FenceTombstoneCleanup.class);

    private final IdempotencyRecordRepository records;
    private final Duration retention;

    public FenceTombstoneCleanup(IdempotencyRecordRepository records,
                                 @Value("${inventory.fence.retention:7d}") Duration retention) {
        if (retention.compareTo(Duration.ofHours(1)) < 0) {
            throw new IllegalArgumentException("inventory.fence.retention must be at least 1 hour, was " + retention);
        }
        this.records = records;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${inventory.fence.cleanup.interval:1h}",
               initialDelayString = "${inventory.fence.cleanup.initial-delay:5m}")
    public void run() {
        try {
            purgeExpired(Instant.now());
        } catch (RuntimeException e) {
            log.error("fence tombstone cleanup failed", e);
        }
    }

    /** Deletes the tombstones older than the retention, as seen from {@code now}; returns how many. */
    @Transactional
    public int purgeExpired(Instant now) {
        int deleted = records.deleteByOperationCreatedBefore(IdempotencyOperation.FENCED, now.minus(retention));
        if (deleted > 0) {
            log.info("fence tombstone cleanup removed {} tombstone(s) older than {}", deleted, retention);
        }
        return deleted;
    }
}
