package com.mercury.product.dto;

/**
 * The reservation plus whether Inventory Service answered it from a stored result
 * (same Idempotency-Key) instead of reserving again.
 */
public record InventoryReservationResult(
        InventoryReservationResponse reservation,
        boolean replayed
) {
}
