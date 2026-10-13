package com.mercury.order.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "order_idempotency_records")
public class OrderIdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClaimStatus status;

    @Column(length = 100000)
    private String responseSnapshot;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected OrderIdempotencyRecord() {
    }

    public static OrderIdempotencyRecord claim(String idempotencyKey, String requestHash, UUID orderId) {
        OrderIdempotencyRecord record = new OrderIdempotencyRecord();
        record.idempotencyKey = idempotencyKey;
        record.requestHash = requestHash;
        record.orderId = orderId;
        record.status = ClaimStatus.IN_PROGRESS;
        return record;
    }

    public void complete(String responseSnapshot) {
        this.status = ClaimStatus.COMPLETED;
        this.responseSnapshot = responseSnapshot;
    }

    /** The timestamp columns keep microseconds; a finer clock value would differ from the row it is stored in. */
    private static Instant stampNow() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @PrePersist
    protected void onCreate() {
        Instant now = stampNow();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = stampNow();
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public String getResponseSnapshot() {
        return responseSnapshot;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
