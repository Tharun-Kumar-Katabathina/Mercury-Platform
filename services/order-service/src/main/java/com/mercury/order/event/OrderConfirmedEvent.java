package com.mercury.order.event;

import java.time.Instant;
import java.util.UUID;

public record OrderConfirmedEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId) implements OrderEvent {

    public static final String TYPE = "OrderConfirmed";

    public static OrderConfirmedEvent of(UUID orderId, Instant occurredAt) {
        return new OrderConfirmedEvent(UUID.randomUUID(), TYPE, occurredAt, orderId);
    }
}
