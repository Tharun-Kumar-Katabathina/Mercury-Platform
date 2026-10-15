package com.mercury.inventory.service;

import com.mercury.inventory.model.IdempotencyOperation;
import com.mercury.inventory.model.IdempotencyRecord;
import com.mercury.inventory.repository.IdempotencyRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fence tombstones expire after the retention; nothing else in idempotency_records is ever deleted. */
@SpringBootTest
class FenceTombstoneCleanupTests {

    private static final Duration RETENTION = Duration.ofDays(7);

    @Autowired private FenceTombstoneCleanup cleanup;
    @Autowired private IdempotencyRecordRepository records;
    @Autowired private com.mercury.inventory.repository.OrderReservationRepository orderReservations;
    @Autowired private JdbcTemplate jdbc;

    private String store(IdempotencyOperation operation, Duration age) {
        IdempotencyRecord record = new IdempotencyRecord();
        String key = "cleanup:" + UUID.randomUUID();
        record.setIdempotencyKey(key);
        record.setOperation(operation);
        record.setRequestHash(operation == IdempotencyOperation.FENCED ? "FENCED:x" : "hash");
        record.setProductId(UUID.randomUUID());
        record.setResponseBody("{}");
        records.saveAndFlush(record);
        jdbc.update("update idempotency_records set created_at = ? where idempotency_key = ?",
                Timestamp.from(Instant.now().minus(age)), key);
        return key;
    }

    private boolean exists(String key) {
        return records.findByIdempotencyKey(key).isPresent();
    }

    @Test
    void anExpiredTombstoneIsRemovedAndAFreshOneIsKept() {
        String expired = store(IdempotencyOperation.FENCED, RETENTION.plusHours(1));
        String fresh = store(IdempotencyOperation.FENCED, RETENTION.minusHours(1));

        cleanup.purgeExpired(Instant.now());

        assertThat(exists(expired)).isFalse();
        assertThat(exists(fresh)).isTrue();
    }

    @Test
    void reserveAndReleaseRecordsAreNeverRemovedHoweverOldTheyAre() {
        String reserve = store(IdempotencyOperation.RESERVE, Duration.ofDays(400));
        String release = store(IdempotencyOperation.RELEASE, Duration.ofDays(400));

        cleanup.purgeExpired(Instant.now());

        assertThat(exists(reserve)).isTrue();
        assertThat(exists(release)).isTrue();
    }

    @Test
    void itReportsHowManyTombstonesItRemoved() {
        cleanup.purgeExpired(Instant.now());                       // clear whatever other tests left behind
        store(IdempotencyOperation.FENCED, RETENTION.plusDays(1));
        store(IdempotencyOperation.FENCED, RETENTION.plusDays(2));

        assertThat(cleanup.purgeExpired(Instant.now())).isEqualTo(2);
        assertThat(cleanup.purgeExpired(Instant.now())).isZero();
    }

    private java.util.UUID fencedOrder(Duration age) {
        java.util.UUID orderId = java.util.UUID.randomUUID();
        orderReservations.saveAndFlush(com.mercury.inventory.model.OrderReservation.fenced(orderId, Instant.now().minus(age)));
        return orderId;
    }

    @Test
    void anExpiredOrderFenceIsRemovedAndAFreshOneAndDecidedOrdersAreKept() {
        java.util.UUID expired = fencedOrder(RETENTION.plusHours(1));
        java.util.UUID fresh = fencedOrder(RETENTION.minusHours(1));
        java.util.UUID rejected = java.util.UUID.randomUUID();
        orderReservations.saveAndFlush(com.mercury.inventory.model.OrderReservation.rejected(
                rejected, "INSUFFICIENT_STOCK", java.util.UUID.randomUUID(), Instant.now().minus(Duration.ofDays(400))));

        cleanup.purgeExpired(Instant.now());

        assertThat(orderReservations.existsById(expired)).isFalse();
        assertThat(orderReservations.existsById(fresh)).isTrue();
        assertThat(orderReservations.existsById(rejected)).isTrue();
    }

    @Test
    void aRetentionShorterThanAnHourIsRefused() {
        assertThatThrownBy(() -> new FenceTombstoneCleanup(records, orderReservations, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1 hour");
    }
}
