package com.mercury.order.model;

/** How an order's stock is reserved. Fixed per order at creation. */
public enum ReservationMode {
    /** Order Service calls Inventory over REST during the request (Phases 6-9). */
    SYNC,
    /** Order Service asks Inventory by Kafka command and the order is confirmed by its reply (Phase 10). */
    ASYNC
}
