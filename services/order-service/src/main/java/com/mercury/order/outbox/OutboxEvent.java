package com.mercury.order.outbox;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One event waiting to be (or already) published. Events of the same order are published strictly
 * in {@code seq} order. Delivery is at-least-once: a crash after Kafka accepted the event but before
 * {@code publishedAt} was saved publishes it again, which consumers must tolerate (eventId).
 */
@Entity
@Table(name = "order_outbox")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long seq;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID aggregateId;

    @Column(nullable = false, length = 40)
    private String eventType;

    @Column(nullable = false, length = 100000)
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant publishedAt;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    private Instant lockedUntil;

    @Column(length = 1000)
    private String lastError;

    /** W3C traceparent of the writing request, or null */
    @Column(length = 80)
    private String traceParent;

    protected OutboxEvent() {
    }

    public static OutboxEvent of(UUID eventId, UUID aggregateId, String eventType, String payload, Instant now) {
        return of(eventId, aggregateId, eventType, payload, now, null);
    }

    public static OutboxEvent of(
            UUID eventId, UUID aggregateId, String eventType, String payload, Instant now, String traceParent) {
        OutboxEvent event = new OutboxEvent();
        event.traceParent = traceParent;
        event.eventId = eventId;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.payload = payload;
        event.createdAt = now;
        event.nextAttemptAt = now;
        return event;
    }

    public void lease(Instant until) {
        this.lockedUntil = until;
    }

    public void published(Instant now) {
        this.publishedAt = now;
        this.lockedUntil = null;
        this.lastError = null;
    }

    public void failed(Instant nextAttempt, String error) {
        this.attemptCount++;
        this.nextAttemptAt = nextAttempt;
        this.lockedUntil = null;
        this.lastError = error == null || error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    public String getTraceParent() {
        return traceParent;
    }

    public Long getSeq() {
        return seq;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }
}
