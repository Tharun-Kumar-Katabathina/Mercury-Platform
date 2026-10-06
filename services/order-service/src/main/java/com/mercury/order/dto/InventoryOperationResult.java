package com.mercury.order.dto;

/** Outcome of a reserve or release call. {@code replayed}: Inventory returned a stored result. */
public record InventoryOperationResult(boolean replayed) {
}
