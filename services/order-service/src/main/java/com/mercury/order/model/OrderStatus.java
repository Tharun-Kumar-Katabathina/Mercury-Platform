package com.mercury.order.model;

/** PENDING -> CONFIRMED (stock reserved) or PENDING -> CANCELLED (failed, compensated). */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    CANCELLED
}
