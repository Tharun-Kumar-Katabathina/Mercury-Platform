package com.mercury.inventory.controller;

import com.mercury.inventory.dto.OrderReservationResponse;
import com.mercury.inventory.service.OrderReservationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Read-only: what was decided for an order's asynchronous reservation. Changes nothing. */
@RestController
@RequestMapping("/api/v1/inventory/reservations/orders")
public class OrderReservationController {

    private final OrderReservationService reservations;

    public OrderReservationController(OrderReservationService reservations) {
        this.reservations = reservations;
    }

    @GetMapping("/{orderId}")
    public OrderReservationResponse getOutcome(@PathVariable UUID orderId) {
        return OrderReservationResponse.from(reservations.findOutcome(orderId));
    }
}
