package com.mercury.order.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable record of where an order's saga is. The live request owns it while it runs (a lease);
 * if that request dies, the lease and the next-attempt time expire and recovery takes over.
 * {@code version} makes two writers fail loudly instead of overwriting each other.
 */
@Entity
@Table(name = "order_saga")
public class OrderSaga {

    @Id
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SagaState state;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    private Instant lockedUntil;

    @Column(length = 1000)
    private String lastError;

    /** why this saga was cancelled; set once, when compensation begins */
    @Column(length = 100)
    private String failureReason;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected OrderSaga() {
    }

    public static OrderSaga start(UUID orderId, Instant nextAttemptAt, Instant lockedUntil) {
        OrderSaga saga = new OrderSaga();
        saga.orderId = orderId;
        saga.state = SagaState.RESERVING;
        saga.nextAttemptAt = nextAttemptAt;
        saga.lockedUntil = lockedUntil;
        return saga;
    }

    /** The owner is still alive: push out both the lease and the point where it counts as abandoned. */
    public void heartbeat(Instant lockedUntil, Instant nextAttemptAt) {
        this.lockedUntil = lockedUntil;
        this.nextAttemptAt = nextAttemptAt;
    }

    public void lease(Instant until) {
        this.lockedUntil = until;
    }

    /** Idempotent: an already-compensating saga stays as it is; the first reason given is kept. */
    public void beginCompensation(String reason) {
        if (state == SagaState.RESERVING) {
            state = SagaState.COMPENSATING;
        }
        if (failureReason == null) {
            failureReason = reason;
        }
    }

    public void confirmed() {
        state = SagaState.CONFIRMED;
        lockedUntil = null;
        lastError = null;
    }

    public void cancelled() {
        state = SagaState.CANCELLED;
        lockedUntil = null;
        lastError = null;
    }

    /** Compensation did not finish; try again later. */
    public void retryAt(Instant next, String error) {
        state = SagaState.COMPENSATING;
        attemptCount++;
        nextAttemptAt = next;
        lockedUntil = null;
        lastError = truncate(error);
    }

    public void recoveryFailed(String error) {
        state = SagaState.RECOVERY_FAILED;
        attemptCount++;
        lockedUntil = null;
        lastError = truncate(error);
    }

    private static String truncate(String text) {
        return text == null || text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getOrderId() {
        return orderId;
    }

    public SagaState getState() {
        return state;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
