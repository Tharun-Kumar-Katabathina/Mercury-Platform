package com.mercury.order.model;

public enum SagaState {
    /** the order is being created: reserving stock, then confirming */
    RESERVING,
    /** the order did not complete; stock is being given back, then the order is cancelled */
    COMPENSATING,
    /** terminal: order confirmed */
    CONFIRMED,
    /** terminal: order cancelled, every reservation given back */
    CANCELLED,
    /** terminal for the worker: retries exhausted, needs a person */
    RECOVERY_FAILED;

    public boolean isActive() {
        return this == RESERVING || this == COMPENSATING;
    }
}
