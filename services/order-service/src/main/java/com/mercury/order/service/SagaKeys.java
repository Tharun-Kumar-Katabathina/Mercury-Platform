package com.mercury.order.service;

import java.util.UUID;

/** Deterministic Idempotency-Keys: one per (order, product), identical on every retry. */
final class SagaKeys {

    private SagaKeys() {
    }

    static String reservation(UUID orderId, UUID productId) {
        return "order:" + orderId + ":product:" + productId;
    }

    static String release(UUID orderId, UUID productId) {
        return reservation(orderId, productId) + ":release";
    }
}
