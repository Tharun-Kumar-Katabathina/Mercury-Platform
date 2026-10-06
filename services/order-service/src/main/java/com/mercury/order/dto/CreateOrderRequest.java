package com.mercury.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateOrderRequest(

        @NotEmpty(message = "an order needs at least one item")
        List<@NotNull(message = "item must not be null") @Valid CreateOrderItemRequest> items
) {
}
