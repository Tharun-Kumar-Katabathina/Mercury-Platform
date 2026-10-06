package com.mercury.order.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The command that asks Inventory to reserve a whole order, sent only for ASYNC orders. A dedicated
 * command, NOT OrderCreated: OrderCreated is a fact other consumers rely on, and reusing it would make
 * Inventory reserve a second time for orders the synchronous path already reserved.
 */
public record InventoryReservationRequestedEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId,
        List<Item> items) implements OrderEvent {

    public static final String TYPE = "InventoryReservationRequested";

    public record Item(UUID productId, int quantity) {
    }

    public static InventoryReservationRequestedEvent of(UUID orderId, Instant occurredAt, List<Item> items) {
        return new InventoryReservationRequestedEvent(UUID.randomUUID(), TYPE, occurredAt, orderId, items);
    }
}
