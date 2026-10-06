package com.mercury.inventory.exception;

import java.util.UUID;

public class InventoryNotFoundException extends RuntimeException {

    public InventoryNotFoundException(UUID productId) {
        super("Inventory not found for product: " + productId);
    }
}
