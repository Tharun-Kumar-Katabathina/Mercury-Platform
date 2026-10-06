package com.mercury.order.dto;

/** Body for both Inventory's reserve and release endpoints. */
public record ReserveInventoryRequest(int quantity) {
}
