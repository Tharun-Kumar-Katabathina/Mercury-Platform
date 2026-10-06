package com.mercury.inventory.event;

import java.time.Instant;
import java.util.UUID;

public record InventoryRejectedEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId,
        String reason,
        UUID productId) implements InventoryEvent {

    public static final String TYPE = "InventoryRejected";

    public static InventoryRejectedEvent of(UUID orderId, Instant occurredAt, String reason, UUID productId) {
        return new InventoryRejectedEvent(UUID.randomUUID(), TYPE, occurredAt, orderId, reason, productId);
    }
}
