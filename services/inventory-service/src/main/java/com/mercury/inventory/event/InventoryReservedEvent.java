package com.mercury.inventory.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InventoryReservedEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId,
        List<Item> items) implements InventoryEvent {

    public static final String TYPE = "InventoryReserved";

    public record Item(UUID productId, int quantity) {
    }

    public static InventoryReservedEvent of(UUID orderId, Instant occurredAt, List<Item> items) {
        return new InventoryReservedEvent(UUID.randomUUID(), TYPE, occurredAt, orderId, items);
    }
}
