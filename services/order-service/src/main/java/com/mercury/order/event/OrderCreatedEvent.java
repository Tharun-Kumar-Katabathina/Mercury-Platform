package com.mercury.order.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderCreatedEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId,
        List<Item> items,
        BigDecimal totalAmount,
        /** who placed the order (the token subject); null when security is off. Used by the recommendation service. */
        String customerId) implements OrderEvent {

    public static final String TYPE = "OrderCreated";

    public record Item(UUID productId, int quantity, String name, String sku, BigDecimal unitPrice) {
    }

    public static OrderCreatedEvent of(UUID orderId, Instant occurredAt, List<Item> items, BigDecimal total) {
        return of(orderId, occurredAt, items, total, null);
    }

    public static OrderCreatedEvent of(UUID orderId, Instant occurredAt, List<Item> items, BigDecimal total, String customerId) {
        return new OrderCreatedEvent(UUID.randomUUID(), TYPE, occurredAt, orderId, items, total, customerId);
    }
}
