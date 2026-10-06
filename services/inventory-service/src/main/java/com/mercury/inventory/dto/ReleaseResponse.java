package com.mercury.inventory.dto;

import com.mercury.inventory.model.Inventory;

import java.time.Instant;
import java.util.UUID;

public record ReleaseResponse(
        UUID productId,
        Integer quantityReleased,
        Integer availableQuantity,
        Integer reservedQuantity,
        Long version,
        Instant updatedAt
) {

    public static ReleaseResponse from(Inventory inventory, int quantityReleased) {
        return new ReleaseResponse(
                inventory.getProductId(),
                quantityReleased,
                inventory.getAvailableQuantity(),
                inventory.getReservedQuantity(),
                inventory.getVersion(),
                inventory.getUpdatedAt()
        );
    }
}
