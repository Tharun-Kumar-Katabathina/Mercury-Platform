package com.mercury.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Product Service's public contract; independent of Inventory Service's own request. */
public record ReserveProductRequest(

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        Integer quantity
) {
}
