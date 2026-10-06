package com.mercury.inventory.dto;

import com.mercury.inventory.model.Inventory;

import java.time.Instant;
import java.util.UUID;

public record InventoryResponse(
        UUID id,
        UUID productId,
        Integer availableQuantity,
        Integer reservedQuantity,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static InventoryResponse from(Inventory inventory) {
        return new InventoryResponse(
                inventory.getId(),
                inventory.getProductId(),
                inventory.getAvailableQuantity(),
                inventory.getReservedQuantity(),
                inventory.getVersion(),
                inventory.getCreatedAt(),
                inventory.getUpdatedAt()
        );
    }
}
