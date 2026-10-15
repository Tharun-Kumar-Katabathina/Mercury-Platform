package com.mercury.inventory.controller;

import com.mercury.inventory.dto.OrderReservationResponse;
import com.mercury.inventory.service.OrderReservationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * What was decided for an order's asynchronous reservation. GET only reads. POST .../fence settles an undecided
 * order as FENCED (Order Service, about to cancel it at its waiting deadline) and reports the outcome either way.
 */
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

    /** 200 with the order's outcome: RESERVED or REJECTED if Inventory had decided, else FENCED (idempotent). */
    @PostMapping("/{orderId}/fence")
    public OrderReservationResponse fence(@PathVariable UUID orderId) {
        return OrderReservationResponse.from(reservations.fence(orderId));
    }
}
