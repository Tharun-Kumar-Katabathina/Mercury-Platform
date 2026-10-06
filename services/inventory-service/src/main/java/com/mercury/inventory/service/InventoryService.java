package com.mercury.inventory.service;

import com.mercury.inventory.dto.CreateInventoryRequest;
import com.mercury.inventory.dto.InventoryResponse;
import com.mercury.inventory.dto.ReservationResponse;
import com.mercury.inventory.dto.UpdateInventoryRequest;
import com.mercury.inventory.exception.DuplicateInventoryException;
import com.mercury.inventory.exception.InsufficientStockException;
import com.mercury.inventory.exception.InventoryNotFoundException;
import com.mercury.inventory.model.Inventory;
import com.mercury.inventory.repository.InventoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final TransactionTemplate transactionTemplate;
    private final int reserveMaxAttempts;

    public InventoryService(
            InventoryRepository inventoryRepository,
            TransactionTemplate transactionTemplate,
            @Value("${inventory.reserve.max-attempts:5}") int reserveMaxAttempts) {
        this.inventoryRepository = inventoryRepository;
        this.transactionTemplate = transactionTemplate;
        this.reserveMaxAttempts = reserveMaxAttempts;
    }

    @Transactional
    public InventoryResponse createInventory(CreateInventoryRequest request) {

        if (inventoryRepository.existsByProductId(request.productId())) {
            throw new DuplicateInventoryException(request.productId());
        }

        Inventory inventory = new Inventory();
        inventory.setProductId(request.productId());
        inventory.setAvailableQuantity(request.availableQuantity());
        inventory.setReservedQuantity(0);

        try {
            return InventoryResponse.from(inventoryRepository.saveAndFlush(inventory));
        } catch (DataIntegrityViolationException e) {
            // concurrent create won the race past the existsByProductId check
            throw new DuplicateInventoryException(request.productId());
        }
    }

    @Transactional(readOnly = true)
    public InventoryResponse getInventory(UUID productId) {

        return InventoryResponse.from(findByProductId(productId));
    }

    @Transactional
    public InventoryResponse updateInventory(
            UUID productId,
            UpdateInventoryRequest request) {

        Inventory inventory = findByProductId(productId);
        inventory.setAvailableQuantity(request.availableQuantity());

        // flush so a version conflict surfaces here, not after the method returns
        return InventoryResponse.from(inventoryRepository.saveAndFlush(inventory));
    }

    /**
     * Moves {@code quantity} units from available to reserved.
     *
     * Not @Transactional on purpose: every attempt runs in its own transaction so a
     * version conflict re-reads the latest stock instead of retrying on a stale row.
     * Insufficient stock is a business answer and is never retried; if the row is still
     * contended after the last attempt the conflict propagates (HTTP 409).
     */
    public ReservationResponse reserveInventory(UUID productId, int quantity) {

        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status ->
                        reserveOnce(productId, quantity));
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= reserveMaxAttempts) {
                    throw e;
                }
                backOff(attempt);
            }
        }
    }

    private ReservationResponse reserveOnce(UUID productId, int quantity) {

        Inventory inventory = findByProductId(productId);

        if (inventory.getAvailableQuantity() < quantity) {
            throw new InsufficientStockException(
                    productId, quantity, inventory.getAvailableQuantity());
        }

        inventory.setAvailableQuantity(inventory.getAvailableQuantity() - quantity);
        inventory.setReservedQuantity(inventory.getReservedQuantity() + quantity);

        // flush so the version check happens inside this attempt's transaction
        return ReservationResponse.from(
                inventoryRepository.saveAndFlush(inventory), quantity);
    }

    private void backOff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 5L * attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying reservation", e);
        }
    }

    private Inventory findByProductId(UUID productId) {
        return inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new InventoryNotFoundException(productId));
    }
}
