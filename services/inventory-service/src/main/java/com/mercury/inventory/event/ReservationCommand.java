package com.mercury.inventory.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inventory's own reading of the InventoryReservationRequested command from Order Service: only the
 * fields it needs, unknown ones ignored. Not shared with Order Service, so the two evolve independently.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservationCommand(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId,
        List<Item> items) {

    public static final String TYPE = "InventoryReservationRequested";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(UUID productId, Integer quantity) {
    }
}
