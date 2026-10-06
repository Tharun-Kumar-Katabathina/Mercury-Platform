package com.mercury.order.controller;

import com.mercury.order.dto.CreateOrderRequest;
import com.mercury.order.dto.OrderResponse;
import com.mercury.order.service.OrderCreationResult;
import com.mercury.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 201 Created for a new order. A retry with the same Idempotency-Key and payload returns the
     * original order, again 201, with {@code Idempotent-Replayed: true}.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            // optional here so a missing key gets Mercury's own MISSING_IDEMPOTENCY_KEY error
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {

        OrderCreationResult result = orderService.createOrder(idempotencyKey, request);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.order());
    }

    @GetMapping("/{orderId}")
    public OrderResponse getOrder(@PathVariable UUID orderId) {

        return orderService.getOrder(orderId);
    }
}
