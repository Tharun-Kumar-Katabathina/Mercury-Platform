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
        BigDecimal totalAmount) implements OrderEvent {

    public static final String TYPE = "OrderCreated";

    public record Item(UUID productId, int quantity, String name, String sku, BigDecimal unitPrice) {
    }

    public static OrderCreatedEvent of(UUID orderId, Instant occurredAt, List<Item> items, BigDecimal total) {
        return new OrderCreatedEvent(UUID.randomUUID(), TYPE, occurredAt, orderId, items, total);
    }
}
