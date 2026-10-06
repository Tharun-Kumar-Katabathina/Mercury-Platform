package com.mercury.inventory.service;

import com.mercury.inventory.dto.CreateInventoryRequest;
import com.mercury.inventory.dto.InventoryResponse;
import com.mercury.inventory.dto.ReservationResponse;
import com.mercury.inventory.exception.InsufficientStockException;
import com.mercury.inventory.exception.ReservationNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A lost reserve response can be resolved by asking "was there a reservation under this key?". */
@SpringBootTest
class InventoryReservationLookupTests {

    @Autowired
    private InventoryService inventoryService;

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private UUID productWithStock(int stock) {
        UUID productId = UUID.randomUUID();
        inventoryService.createInventory(new CreateInventoryRequest(productId, stock));
        return productId;
    }

    @Test
    void findsTheReservationMadeUnderTheKey() {
        UUID productId = productWithStock(10);
        String key = key();
        ReservationResponse made = inventoryService.reserveInventory(productId, 3, key).response();

        ReservationResponse found = inventoryService.findReservation(productId, key);

        assertThat(found).isEqualTo(made);
        assertThat(found.quantityReserved()).isEqualTo(3);
    }

    @Test
    void lookupChangesNothing() {
        UUID productId = productWithStock(10);
        String key = key();
        inventoryService.reserveInventory(productId, 3, key);

        inventoryService.findReservation(productId, key);
        inventoryService.findReservation(productId, key);

        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(stored.availableQuantity()).isEqualTo(7);
        assertThat(stored.reservedQuantity()).isEqualTo(3);
    }

    @Test
    void unknownKeyIsNotFound() {
        UUID productId = productWithStock(10);

        assertThatThrownBy(() -> inventoryService.findReservation(productId, key()))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void aFailedReservationLeavesNothingToFind() {
        UUID productId = productWithStock(1);
        String key = key();
        assertThatThrownBy(() -> inventoryService.reserveInventory(productId, 5, key))
                .isInstanceOf(InsufficientStockException.class);

        assertThatThrownBy(() -> inventoryService.findReservation(productId, key))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void aReleaseKeyIsNotAReservation() {
        UUID productId = productWithStock(10);
        inventoryService.reserveInventory(productId, 4, key());
        String releaseKey = key();
        inventoryService.releaseInventory(productId, 2, releaseKey);

        assertThatThrownBy(() -> inventoryService.findReservation(productId, releaseKey))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void aReservationForAnotherProductIsNotFound() {
        UUID productA = productWithStock(10);
        UUID productB = productWithStock(10);
        String key = key();
        inventoryService.reserveInventory(productA, 1, key);

        assertThatThrownBy(() -> inventoryService.findReservation(productB, key))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void staysFindableAfterTheStockIsReleased() {
        // the record answers "did this reservation happen", not "is it still held"
        UUID productId = productWithStock(10);
        String key = key();
        inventoryService.reserveInventory(productId, 3, key);
        inventoryService.releaseInventory(productId, 3, key());

        assertThat(inventoryService.findReservation(productId, key).quantityReserved()).isEqualTo(3);
    }
}
