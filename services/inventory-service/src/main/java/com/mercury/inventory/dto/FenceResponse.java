package com.mercury.inventory.dto;

/**
 * The answer to "fence this reservation key". Exactly one of two things is true afterwards:
 * RESERVED (the reservation exists, here it is: the caller must release it) or FENCED (it does not
 * exist and never will under this key).
 */
public record FenceResponse(String status, ReservationResponse reservation) {

    public static FenceResponse reserved(ReservationResponse reservation) {
        return new FenceResponse("RESERVED", reservation);
    }

    public static FenceResponse fenced() {
        return new FenceResponse("FENCED", null);
    }
}
