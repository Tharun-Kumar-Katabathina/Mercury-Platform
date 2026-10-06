package com.mercury.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** What Inventory reports for a reservation that exists under a given key. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservationSnapshot(UUID productId, Integer quantityReserved) {
}
