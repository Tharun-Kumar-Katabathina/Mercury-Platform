package com.mercury.order.controller;

import com.mercury.order.dto.CreateOrderRequest;
import com.mercury.order.dto.OrderResponse;
import com.mercury.order.security.Caller;
import com.mercury.order.service.OrderCreationResult;
import org.springframework.security.core.Authentication;
import com.mercury.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Synchronous flow: 201 Created for a new order; a retry with the same Idempotency-Key and payload
     * returns the original order, again 201, with {@code Idempotent-Replayed: true}.
     *
     * Asynchronous flow: 202 Accepted + Location while Inventory has not answered yet (the same for a
     * retry, which never waits); once the order is CONFIRMED or CANCELLED a retry returns it with 200
     * and {@code Idempotent-Replayed: true}.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            // optional here so a missing key gets Mercury's own MISSING_IDEMPOTENCY_KEY error
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request,
            Authentication authentication) {

        OrderCreationResult result = orderService.createOrder(idempotencyKey, request, Caller.from(authentication));

        ResponseEntity.BodyBuilder response = ResponseEntity.status(switch (result.kind()) {
            case CREATED -> HttpStatus.CREATED;
            case ACCEPTED -> HttpStatus.ACCEPTED;
            case OK -> HttpStatus.OK;
        });
        if (result.kind() != OrderCreationResult.Kind.CREATED) {
            response.location(URI.create("/api/v1/orders/" + result.order().id()));
        }
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.order());
    }

    @GetMapping("/{orderId}")
    public OrderResponse getOrder(@PathVariable UUID orderId, Authentication authentication) {

        return orderService.getOrder(orderId, Caller.from(authentication));
    }
}
