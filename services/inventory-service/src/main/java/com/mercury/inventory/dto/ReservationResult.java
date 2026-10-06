package com.mercury.inventory.dto;

/**
 * Outcome of a reserve call: the response body plus whether it was replayed from a
 * previously stored result (same Idempotency-Key) rather than executed now.
 */
public record ReservationResult(ReservationResponse response, boolean replayed) {
}
