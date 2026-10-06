package com.mercury.order.model;

/** How far the stock reservation for ONE order item got. Written before each remote call. */
public enum ReservationStatus {
    /** nothing attempted */
    NOT_STARTED,
    /** a reserve call was started and its outcome is not known: Inventory may or may not hold it */
    RESERVING,
    /** Inventory confirmed the reservation */
    RESERVED,
    /** Inventory confirmed it holds no reservation for this item */
    NOT_RESERVED,
    /** a release call was started and its outcome is not known (releasing again is safe) */
    RELEASING,
    /** Inventory confirmed the stock was given back */
    RELEASED
}
