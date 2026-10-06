package com.mercury.order.service;

import com.mercury.order.dto.OrderResponse;

/** The created order, and whether it was returned from a stored result (same Idempotency-Key). */
public record OrderCreationResult(OrderResponse order, boolean replayed) {
}
