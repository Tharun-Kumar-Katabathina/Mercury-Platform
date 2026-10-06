package com.mercury.inventory.dto;

import com.mercury.inventory.model.Inventory;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
        UUID productId,
        Integer quantityReserved,
        Integer availableQuantity,
        Integer reservedQuantity,
        Long version,
        Instant updatedAt
) {

    public static ReservationResponse from(Inventory inventory, int quantityReserved) {
        return new ReservationResponse(
                inventory.getProductId(),
                quantityReserved,
                inventory.getAvailableQuantity(),
                inventory.getReservedQuantity(),
                inventory.getVersion(),
                inventory.getUpdatedAt()
        );
    }
}
