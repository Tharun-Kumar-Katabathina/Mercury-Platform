package com.mercury.product.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** Result of reserving stock, as reported by Inventory Service. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryReservationResponse(
        UUID productId,
        int quantityReserved,
        int availableQuantity,
        int reservedQuantity,
        Long version
) {
}
