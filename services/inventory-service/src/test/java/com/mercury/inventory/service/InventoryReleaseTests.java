package com.mercury.inventory.service;

import com.mercury.inventory.dto.CreateInventoryRequest;
import com.mercury.inventory.dto.InventoryResponse;
import com.mercury.inventory.dto.ReleaseResult;
import com.mercury.inventory.exception.IdempotencyKeyMismatchException;
import com.mercury.inventory.exception.InsufficientReservedStockException;
import com.mercury.inventory.exception.InventoryNotFoundException;
import com.mercury.inventory.repository.IdempotencyRecordRepository;
import com.mercury.inventory.service.ConcurrentRunner.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 15 attempts keeps the exact-count assertions deterministic (see InventoryIdempotencyTests).
@SpringBootTest(properties = "inventory.reserve.max-attempts=15")
class InventoryReleaseTests {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    private static String key() {
        return UUID.randomUUID().toString();
    }

    /** product with {@code stock} units of which {@code reserved} are reserved */
    private UUID productWithReserved(int stock, int reserved) {
        UUID productId = UUID.randomUUID();
        inventoryService.createInventory(new CreateInventoryRequest(productId, stock));
        if (reserved > 0) {
            inventoryService.reserveInventory(productId, reserved, key());
        }
        return productId;
    }

    @Test
    void releaseMovesQuantityFromReservedBackToAvailable() {
        UUID productId = productWithReserved(10, 5);   // available 5, reserved 5

        ReleaseResult result = inventoryService.releaseInventory(productId, 2, key());

        assertThat(result.replayed()).isFalse();
        assertThat(result.response().quantityReleased()).isEqualTo(2);
        assertThat(result.response().availableQuantity()).isEqualTo(7);
        assertThat(result.response().reservedQuantity()).isEqualTo(3);

        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(stored.availableQuantity()).isEqualTo(7);
        assertThat(stored.reservedQuantity()).isEqualTo(3);
    }

    @Test
    void releasingEverythingRestoresTheOriginalStock() {
        UUID productId = productWithReserved(10, 4);

        inventoryService.releaseInventory(productId, 4, key());

        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(stored.availableQuantity()).isEqualTo(10);
        assertThat(stored.reservedQuantity()).isZero();
    }

    @Test
    void cannotReleaseMoreThanIsReservedAndNothingChanges() {
        UUID productId = productWithReserved(10, 2);

        assertThatThrownBy(() -> inventoryService.releaseInventory(productId, 3, key()))
                .isInstanceOf(InsufficientReservedStockException.class);

        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(stored.availableQuantity()).isEqualTo(8);
        assertThat(stored.reservedQuantity()).isEqualTo(2);
    }

    @Test
    void cannotReleaseWhenNothingIsReserved() {
        UUID productId = productWithReserved(10, 0);

        assertThatThrownBy(() -> inventoryService.releaseInventory(productId, 1, key()))
                .isInstanceOf(InsufficientReservedStockException.class);

        assertThat(inventoryService.getInventory(productId).availableQuantity()).isEqualTo(10);
    }

    @Test
    void unknownProductIsNotFound() {
        assertThatThrownBy(() -> inventoryService.releaseInventory(UUID.randomUUID(), 1, key()))
                .isInstanceOf(InventoryNotFoundException.class);
    }

    @Test
    void sameKeyReplaysAndDoesNotReleaseTwice() {
        UUID productId = productWithReserved(10, 6);   // available 4, reserved 6
        String key = key();

        ReleaseResult first = inventoryService.releaseInventory(productId, 2, key);
        ReleaseResult second = inventoryService.releaseInventory(productId, 2, key);
        ReleaseResult third = inventoryService.releaseInventory(productId, 2, key);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(third.replayed()).isTrue();
        assertThat(second.response()).isEqualTo(first.response());

        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(stored.availableQuantity()).isEqualTo(6);   // released once: 4 -> 6, not 4 -> 10
        assertThat(stored.reservedQuantity()).isEqualTo(4);
    }

    @Test
    void sameKeyWithDifferentQuantityIsRejected() {
        UUID productId = productWithReserved(10, 6);
        String key = key();
        inventoryService.releaseInventory(productId, 2, key);

        assertThatThrownBy(() -> inventoryService.releaseInventory(productId, 3, key))
                .isInstanceOf(IdempotencyKeyMismatchException.class);

        assertThat(inventoryService.getInventory(productId).reservedQuantity()).isEqualTo(4);
    }

    @Test
    void aReservationKeyCannotBeReusedAsAReleaseEvenForTheSameProductAndQuantity() {
        UUID productId = UUID.randomUUID();
        inventoryService.createInventory(new CreateInventoryRequest(productId, 10));
        String key = key();
        inventoryService.reserveInventory(productId, 3, key);

        // identical productId + quantity, same key: still a different operation
        assertThatThrownBy(() -> inventoryService.releaseInventory(productId, 3, key))
                .isInstanceOf(IdempotencyKeyMismatchException.class);

        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(stored.availableQuantity()).isEqualTo(7);
        assertThat(stored.reservedQuantity()).isEqualTo(3);
    }

    @Test
    void aReleaseKeyCannotBeReusedAsAReservation() {
        UUID productId = productWithReserved(10, 5);
        String key = key();
        inventoryService.releaseInventory(productId, 2, key);

        assertThatThrownBy(() -> inventoryService.reserveInventory(productId, 2, key))
                .isInstanceOf(IdempotencyKeyMismatchException.class);
    }

    @Test
    void failedReleaseIsNotCached() {
        UUID productId = productWithReserved(10, 0);
        String key = key();

        assertThatThrownBy(() -> inventoryService.releaseInventory(productId, 2, key))
                .isInstanceOf(InsufficientReservedStockException.class);
        assertThat(idempotencyRecordRepository.findByIdempotencyKey(key)).isEmpty();

        inventoryService.reserveInventory(productId, 5, key());
        ReleaseResult retry = inventoryService.releaseInventory(productId, 2, key);

        assertThat(retry.replayed()).isFalse();
        assertThat(retry.response().reservedQuantity()).isEqualTo(3);
    }

    @Test
    void sameKeyConcurrentReleasesReleaseExactlyOnce() throws Exception {
        UUID productId = productWithReserved(10, 10);
        String key = key();

        List<Outcome<ReleaseResult>> outcomes = ConcurrentRunner.runAll(100,
                i -> () -> inventoryService.releaseInventory(productId, 1, key));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.stream().filter(o -> !o.value().replayed())).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o.value().replayed())).hasSize(99);

        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(stored.availableQuantity()).isEqualTo(1);
        assertThat(stored.reservedQuantity()).isEqualTo(9);
    }

    @Test
    void concurrentReleasesNeverGoBelowZeroReserved() throws Exception {
        int reserved = 10;
        UUID productId = productWithReserved(10, reserved);

        List<Outcome<ReleaseResult>> outcomes = ConcurrentRunner.runAll(50,
                i -> () -> inventoryService.releaseInventory(productId, 1, key()));

        long succeeded = outcomes.stream().filter(Outcome::succeeded).count();
        long rejected = outcomes.stream()
                .filter(o -> o.failure() instanceof InsufficientReservedStockException).count();

        assertThat(succeeded).isEqualTo(reserved);
        assertThat(rejected).isEqualTo(50 - reserved);

        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(stored.reservedQuantity()).isZero();
        assertThat(stored.availableQuantity()).isEqualTo(10);
    }

    @Test
    void mixedConcurrentReservesAndReleasesKeepStockConserved() throws Exception {
        int stock = 20;
        UUID productId = productWithReserved(stock, 10);   // available 10, reserved 10

        List<Outcome<Object>> outcomes = ConcurrentRunner.runAll(60, i -> () -> {
            if (i % 2 == 0) {
                return inventoryService.reserveInventory(productId, 1, key());
            }
            return inventoryService.releaseInventory(productId, 1, key());
        });

        // only business refusals are acceptable failures
        assertThat(outcomes.stream().filter(o -> !o.succeeded()))
                .allMatch(o -> o.failure() instanceof com.mercury.inventory.exception.InsufficientStockException
                        || o.failure() instanceof InsufficientReservedStockException);

        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(stored.availableQuantity()).isGreaterThanOrEqualTo(0);
        assertThat(stored.reservedQuantity()).isGreaterThanOrEqualTo(0);
        assertThat(stored.availableQuantity() + stored.reservedQuantity()).isEqualTo(stock);
    }
}
