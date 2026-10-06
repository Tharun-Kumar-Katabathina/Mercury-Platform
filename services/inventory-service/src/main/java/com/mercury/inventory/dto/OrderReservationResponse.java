package com.mercury.inventory.dto;

import com.mercury.inventory.model.OrderReservation;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** The stored outcome of reserving a whole order. */
public record OrderReservationResponse(
        UUID orderId,
        String status,
        String reason,
        UUID rejectedProductId,
        List<Item> items) {

    public record Item(UUID productId, int quantity) {
    }

    public static OrderReservationResponse from(OrderReservation reservation) {
        return new OrderReservationResponse(
                reservation.getOrderId(),
                reservation.getStatus().name(),
                reservation.getReason(),
                reservation.getRejectedProductId(),
                reservation.getItems().stream()
                        .sorted(Comparator.comparing(i -> i.getProductId().toString()))
                        .map(i -> new Item(i.getProductId(), i.getQuantity()))
                        .toList());
    }
}
