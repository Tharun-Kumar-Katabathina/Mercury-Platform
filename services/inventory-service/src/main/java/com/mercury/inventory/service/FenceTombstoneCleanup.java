package com.mercury.inventory.service;

import com.mercury.inventory.model.IdempotencyOperation;
import com.mercury.inventory.model.ReservationOutcome;
import com.mercury.inventory.repository.IdempotencyRecordRepository;
import com.mercury.inventory.repository.OrderReservationRepository;
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
 * Removes fence tombstones that are old enough to be of no use: idempotency records with operation FENCED (the
 * per-item fence of the synchronous path) and order reservations with status FENCED (the whole-order fence of the
 * ASYNC path).
 *
 * A tombstone exists to refuse a reserve that arrives after the caller gave up on its key. That reserve is a request
 * that was in flight or retried when the order was cancelled, so it can only arrive within the caller's timeouts and
 * retries of that moment, minutes at the very most. The retention is far beyond that (7 days by default), so deleting
 * an expired tombstone cannot let a late reserve through; it only stops the table growing by one row per cancelled
 * order for ever. RESERVE and RELEASE records, and RESERVED or REJECTED order reservations, are never touched: they
 * are what makes a retry return the original answer, and they have no such bound. (An ASYNC command that somehow
 * reached Inventory after its tombstone expired would still not strand stock: Order releases a late reservation
 * through its compensation, see docs/async-reservation.md.)
 *
 * Switch off with inventory.fence.cleanup.enabled=false.
 */
@Component
@ConditionalOnProperty(name = "inventory.fence.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class FenceTombstoneCleanup {

    private static final Logger log = LoggerFactory.getLogger(FenceTombstoneCleanup.class);

    private final IdempotencyRecordRepository records;
    private final OrderReservationRepository orderReservations;
    private final Duration retention;

    public FenceTombstoneCleanup(IdempotencyRecordRepository records, OrderReservationRepository orderReservations,
                                 @Value("${inventory.fence.retention:7d}") Duration retention) {
        if (retention.compareTo(Duration.ofHours(1)) < 0) {
            throw new IllegalArgumentException("inventory.fence.retention must be at least 1 hour, was " + retention);
        }
        this.records = records;
        this.orderReservations = orderReservations;
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
        Instant cutoff = now.minus(retention);
        int deleted = records.deleteByOperationCreatedBefore(IdempotencyOperation.FENCED, cutoff)
                + orderReservations.deleteByStatusCreatedBefore(ReservationOutcome.FENCED, cutoff);
        if (deleted > 0) {
            log.info("fence tombstone cleanup removed {} tombstone(s) older than {}", deleted, retention);
        }
        return deleted;
    }
}
