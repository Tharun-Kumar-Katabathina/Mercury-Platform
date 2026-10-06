package com.mercury.inventory.controller;

import com.mercury.inventory.dto.CreateInventoryRequest;
import com.mercury.inventory.dto.InventoryResponse;
import com.mercury.inventory.dto.ReleaseInventoryRequest;
import com.mercury.inventory.dto.ReleaseResponse;
import com.mercury.inventory.dto.ReleaseResult;
import com.mercury.inventory.dto.ReservationResponse;
import com.mercury.inventory.dto.ReservationResult;
import com.mercury.inventory.dto.ReserveInventoryRequest;
import com.mercury.inventory.dto.UpdateInventoryRequest;
import com.mercury.inventory.service.InventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryResponse createInventory(
            @Valid @RequestBody CreateInventoryRequest request) {

        return inventoryService.createInventory(request);
    }

    @GetMapping("/{productId}")
    public InventoryResponse getInventory(@PathVariable UUID productId) {

        return inventoryService.getInventory(productId);
    }

    @PutMapping("/{productId}")
    public InventoryResponse updateInventory(
            @PathVariable UUID productId,
            @Valid @RequestBody UpdateInventoryRequest request) {

        return inventoryService.updateInventory(productId, request);
    }

    @PostMapping("/{productId}/reserve")
    public ResponseEntity<ReservationResponse> reserveInventory(
            @PathVariable UUID productId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody ReserveInventoryRequest request) {

        ReservationResult result = inventoryService.reserveInventory(
                productId, request.quantity(), idempotencyKey);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.response());
    }

    @PostMapping("/{productId}/release")
    public ResponseEntity<ReleaseResponse> releaseInventory(
            @PathVariable UUID productId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody ReleaseInventoryRequest request) {

        ReleaseResult result = inventoryService.releaseInventory(
                productId, request.quantity(), idempotencyKey);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.response());
    }

    /** Read-only: what, if anything, was reserved for this product under this idempotency key. */
    @GetMapping("/{productId}/reservations/{idempotencyKey}")
    public ReservationResponse getReservation(
            @PathVariable UUID productId,
            @PathVariable String idempotencyKey) {

        return inventoryService.findReservation(productId, idempotencyKey);
    }
}
