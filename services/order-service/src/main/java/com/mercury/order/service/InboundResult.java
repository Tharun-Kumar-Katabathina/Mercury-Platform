package com.mercury.order.service;

/** What handling one Inventory reply did to its order. */
public enum InboundResult {
    /** the order was PENDING and is now CONFIRMED */
    CONFIRMED,
    /** the order was PENDING and is now CANCELLED */
    CANCELLED,
    /** the order was already finished (or being cancelled): the reserved stock must be given back */
    RELEASE_NEEDED,
    /** valid, but nothing to do (order unknown, already in the state the reply would produce, wrong mode) */
    IGNORED,
    /** this event id was already handled */
    DUPLICATE
}
