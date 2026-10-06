package com.mercury.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/** Order Service's own view of a product: only what an order snapshot needs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductDetails(
        UUID id,
        String name,
        String sku,
        BigDecimal price
) {
}
