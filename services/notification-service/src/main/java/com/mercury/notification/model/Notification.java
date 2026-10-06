package com.mercury.notification.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A durable record that a customer-facing notification is due. There is no email provider yet:
 * RECORDED means "this is what would be sent". {@code eventId} is unique, which is what makes the
 * consumer idempotent.
 */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 200)
    private String detail;

    @Column(nullable = false)
    private Instant createdAt;

    protected Notification() {
    }

    public static Notification record(UUID eventId, UUID orderId, NotificationType type, String detail, Instant now) {
        Notification notification = new Notification();
        notification.id = UUID.randomUUID();
        notification.eventId = eventId;
        notification.orderId = orderId;
        notification.type = type;
        notification.status = "RECORDED";
        notification.detail = detail == null || detail.length() <= 200 ? detail : detail.substring(0, 200);
        notification.createdAt = now;
        return notification;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public NotificationType getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
