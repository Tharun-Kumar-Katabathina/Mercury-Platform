package com.mercury.inventory.service;

import com.mercury.inventory.dto.CreateInventoryRequest;
import com.mercury.inventory.dto.InventoryResponse;
import com.mercury.inventory.dto.ReservationResult;
import com.mercury.inventory.exception.InsufficientStockException;
import com.mercury.inventory.service.ConcurrentRunner.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs with the DEFAULT retry configuration. A valid request may legitimately lose the
 * optimistic-lock race every time and be rejected, so the exact number of successes is
 * not asserted here; only the safety invariants are: stock is never oversold or lost.
 */
@SpringBootTest
class InventoryDefaultRetryConcurrencyTests {

    @Autowired
    private InventoryService inventoryService;

    @Test
    void neverOversellsUnderDefaultRetries() throws Exception {
        int stock = 10;
        int requests = 100;
        UUID productId = UUID.randomUUID();
        inventoryService.createInventory(new CreateInventoryRequest(productId, stock));
        List<String> keys = IntStream.range(0, requests)
                .mapToObj(i -> UUID.randomUUID().toString()).toList();

        List<Outcome<ReservationResult>> outcomes = ConcurrentRunner.runAll(requests,
                i -> () -> inventoryService.reserveInventory(productId, 1, keys.get(i)));

        long succeeded = outcomes.stream().filter(Outcome::succeeded).count();

        // the only acceptable failures: out of stock, or lost the lock race after all retries
        assertThat(outcomes.stream().filter(o -> !o.succeeded()))
                .allMatch(o -> o.failure() instanceof InsufficientStockException
                        || o.failure() instanceof ObjectOptimisticLockingFailureException);

        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(succeeded).isLessThanOrEqualTo(stock);
        assertThat(stored.availableQuantity()).isGreaterThanOrEqualTo(0);
        assertThat(stored.reservedQuantity()).isLessThanOrEqualTo(stock);
        assertThat(stored.availableQuantity() + stored.reservedQuantity()).isEqualTo(stock);
        assertThat(stored.reservedQuantity()).isEqualTo((int) succeeded);
    }
}
