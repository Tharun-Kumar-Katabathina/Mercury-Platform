package com.mercury.order.event;

import java.time.Instant;
import java.util.UUID;

public record OrderCancelledEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId,
        String reason) implements OrderEvent {

    public static final String TYPE = "OrderCancelled";

    public static OrderCancelledEvent of(UUID orderId, Instant occurredAt, String reason) {
        return new OrderCancelledEvent(UUID.randomUUID(), TYPE, occurredAt, orderId, reason);
    }
}
