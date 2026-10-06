package com.mercury.order.service;

import com.mercury.order.model.ReservationStatus;

import java.util.UUID;

/** One order item's reservation progress, as plain values. */
public record ItemProgress(UUID productId, int quantity, ReservationStatus status) {
}
