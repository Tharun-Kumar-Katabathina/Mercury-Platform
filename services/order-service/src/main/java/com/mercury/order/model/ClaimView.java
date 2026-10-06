package com.mercury.order.model;

import java.util.UUID;

/**
 * A plain-value read of an idempotency claim. Deliberately NOT an entity: it is loaded straight
 * from the database every time, so it can never be served from a (possibly long-lived)
 * persistence context. Callers that poll a claim until it completes depend on that.
 */
public record ClaimView(ClaimStatus status, String requestHash, UUID orderId, String responseSnapshot) {
}
