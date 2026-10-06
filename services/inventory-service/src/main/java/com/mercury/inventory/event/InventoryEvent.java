package com.mercury.inventory.event;

import java.time.Instant;
import java.util.UUID;

/** Events Inventory publishes about an order's reservation. No secrets, no idempotency keys. */
public sealed interface InventoryEvent permits InventoryReservedEvent, InventoryRejectedEvent {

    UUID eventId();

    String eventType();

    Instant occurredAt();

    UUID orderId();
}
