package com.mercury.product.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Product Service's own view of an inventory record. Deliberately not shared with
 * Inventory Service's model: fields Product does not need are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryResponse(
        UUID productId,
        int availableQuantity,
        int reservedQuantity,
        Long version
) {
}
