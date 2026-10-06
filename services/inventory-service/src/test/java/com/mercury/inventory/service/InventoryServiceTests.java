package com.mercury.inventory.service;

import com.mercury.inventory.dto.CreateInventoryRequest;
import com.mercury.inventory.dto.InventoryResponse;
import com.mercury.inventory.dto.UpdateInventoryRequest;
import com.mercury.inventory.exception.DuplicateInventoryException;
import com.mercury.inventory.exception.InventoryNotFoundException;
import com.mercury.inventory.model.Inventory;
import com.mercury.inventory.repository.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class InventoryServiceTests {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Test
    void createsInventoryWithZeroReserved() {
        UUID productId = UUID.randomUUID();

        InventoryResponse created = inventoryService.createInventory(
                new CreateInventoryRequest(productId, 10));

        assertThat(created.productId()).isEqualTo(productId);
        assertThat(created.availableQuantity()).isEqualTo(10);
        assertThat(created.reservedQuantity()).isZero();
        assertThat(created.version()).isNotNull();
    }

    @Test
    void rejectsDuplicateProductInventory() {
        UUID productId = UUID.randomUUID();
        inventoryService.createInventory(new CreateInventoryRequest(productId, 10));

        assertThatThrownBy(() ->
                inventoryService.createInventory(new CreateInventoryRequest(productId, 5)))
                .isInstanceOf(DuplicateInventoryException.class);
    }

    @Test
    void updateChangesQuantityAndBumpsVersion() {
        UUID productId = UUID.randomUUID();
        InventoryResponse created = inventoryService.createInventory(
                new CreateInventoryRequest(productId, 10));

        InventoryResponse updated = inventoryService.updateInventory(
                productId, new UpdateInventoryRequest(25));

        assertThat(updated.availableQuantity()).isEqualTo(25);
        assertThat(updated.version()).isGreaterThan(created.version());
    }

    @Test
    void missingInventoryThrowsNotFound() {
        UUID productId = UUID.randomUUID();

        assertThatThrownBy(() -> inventoryService.getInventory(productId))
                .isInstanceOf(InventoryNotFoundException.class);
        assertThatThrownBy(() ->
                inventoryService.updateInventory(productId, new UpdateInventoryRequest(1)))
                .isInstanceOf(InventoryNotFoundException.class);
    }

    @Test
    void staleWriteIsRejectedByOptimisticLock() {
        UUID productId = UUID.randomUUID();
        inventoryService.createInventory(new CreateInventoryRequest(productId, 10));

        // two writers read the same version of the row
        Inventory first = inventoryRepository.findByProductId(productId).orElseThrow();
        Inventory second = inventoryRepository.findByProductId(productId).orElseThrow();

        first.setAvailableQuantity(9);
        inventoryRepository.saveAndFlush(first);

        second.setAvailableQuantity(8);
        assertThatThrownBy(() -> inventoryRepository.saveAndFlush(second))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(inventoryService.getInventory(productId).availableQuantity())
                .isEqualTo(9);
    }
}
