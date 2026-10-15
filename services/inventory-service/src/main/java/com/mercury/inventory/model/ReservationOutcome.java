package com.mercury.inventory.model;

public enum ReservationOutcome {
    RESERVED,
    REJECTED,
    /** Order Service gave up waiting and cancelled the order before anything was decided: refuse the command */
    FENCED
}
