package com.mercury.order.service;

import com.mercury.order.dto.OrderResponse;

/**
 * The order, whether it came from a stored result (same Idempotency-Key), and which HTTP answer fits:
 * CREATED (201, the synchronous flow), ACCEPTED (202, an ASYNC order that is still waiting for Inventory)
 * or OK (200, an ASYNC order replayed after it reached its final state).
 */
public record OrderCreationResult(OrderResponse order, boolean replayed, Kind kind) {

    public enum Kind { CREATED, ACCEPTED, OK }

    /** The synchronous flow's answer: 201. */
    public OrderCreationResult(OrderResponse order, boolean replayed) {
        this(order, replayed, Kind.CREATED);
    }
}
