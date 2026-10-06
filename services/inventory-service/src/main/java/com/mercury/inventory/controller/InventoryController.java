package com.mercury.inventory.controller;

import com.mercury.inventory.dto.CreateInventoryRequest;
import com.mercury.inventory.dto.InventoryResponse;
import com.mercury.inventory.dto.ReservationResponse;
import com.mercury.inventory.dto.ReserveInventoryRequest;
import com.mercury.inventory.dto.UpdateInventoryRequest;
import com.mercury.inventory.service.InventoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
    public ReservationResponse reserveInventory(
            @PathVariable UUID productId,
            @Valid @RequestBody ReserveInventoryRequest request) {

        return inventoryService.reserveInventory(productId, request.quantity());
    }
}
