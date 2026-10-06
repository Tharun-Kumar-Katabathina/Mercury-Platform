package com.mercury.inventory.service;

import com.mercury.inventory.dto.CreateInventoryRequest;
import com.mercury.inventory.dto.InventoryResponse;
import com.mercury.inventory.dto.ReservationResponse;
import com.mercury.inventory.dto.ReservationResult;
import com.mercury.inventory.dto.UpdateInventoryRequest;
import com.mercury.inventory.exception.IdempotencyKeyMismatchException;
import com.mercury.inventory.exception.InsufficientStockException;
import com.mercury.inventory.repository.IdempotencyRecordRepository;
import com.mercury.inventory.service.ConcurrentRunner.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 15 attempts: every optimistic-lock conflict means another reservation committed, and
// at most `stock` can, so no valid request can run out of retries. That keeps the
// exact-count assertions below deterministic.
@SpringBootTest(properties = "inventory.reserve.max-attempts=15")
class InventoryIdempotencyTests {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    private UUID newInventory(int available) {
        UUID productId = UUID.randomUUID();
        inventoryService.createInventory(new CreateInventoryRequest(productId, available));
        return productId;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void firstRequestIsNotReplayed() {
        UUID productId = newInventory(10);

        ReservationResult result = inventoryService.reserveInventory(productId, 2, key());

        assertThat(result.replayed()).isFalse();
        assertThat(result.response().availableQuantity()).isEqualTo(8);
    }

    @Test
    void sameKeyReplaysOriginalResultAndDoesNotReserveAgain() {
        UUID productId = newInventory(10);
        String key = key();

        ReservationResult first = inventoryService.reserveInventory(productId, 2, key);
        ReservationResult second = inventoryService.reserveInventory(productId, 2, key);
        ReservationResult third = inventoryService.reserveInventory(productId, 2, key);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(third.replayed()).isTrue();
        assertThat(second.response()).isEqualTo(first.response());
        assertThat(third.response()).isEqualTo(first.response());

        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(stored.availableQuantity()).isEqualTo(8);   // 10 -> 8, not 10 -> 6 -> 4
        assertThat(stored.reservedQuantity()).isEqualTo(2);
    }

    @Test
    void replayReturnsSnapshotOfOriginalResponseEvenAfterStockChanged() {
        UUID productId = newInventory(10);
        String key = key();
        ReservationResponse original =
                inventoryService.reserveInventory(productId, 2, key).response();

        inventoryService.reserveInventory(productId, 3, key());   // stock moves on: 5 available

        ReservationResult replay = inventoryService.reserveInventory(productId, 2, key);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response()).isEqualTo(original);
        assertThat(replay.response().availableQuantity()).isEqualTo(8);
        assertThat(inventoryService.getInventory(productId).availableQuantity()).isEqualTo(5);
    }

    @Test
    void sameKeyWithDifferentQuantityIsRejectedAndChangesNothing() {
        UUID productId = newInventory(10);
        String key = key();
        inventoryService.reserveInventory(productId, 2, key);

        assertThatThrownBy(() -> inventoryService.reserveInventory(productId, 5, key))
                .isInstanceOf(IdempotencyKeyMismatchException.class);

        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(stored.availableQuantity()).isEqualTo(8);
        assertThat(stored.reservedQuantity()).isEqualTo(2);
    }

    @Test
    void sameKeyWithDifferentProductIsRejected() {
        UUID productA = newInventory(10);
        UUID productB = newInventory(10);
        String key = key();
        inventoryService.reserveInventory(productA, 1, key);

        assertThatThrownBy(() -> inventoryService.reserveInventory(productB, 1, key))
                .isInstanceOf(IdempotencyKeyMismatchException.class);

        assertThat(inventoryService.getInventory(productB).availableQuantity()).isEqualTo(10);
    }

    @Test
    void insufficientStockIsNotCached() {
        UUID productId = newInventory(5);
        String key = key();

        assertThatThrownBy(() -> inventoryService.reserveInventory(productId, 10, key))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(idempotencyRecordRepository.findByIdempotencyKey(key)).isEmpty();

        // stock is replenished; the same request with the same key must now be evaluated afresh
        inventoryService.updateInventory(productId, new UpdateInventoryRequest(20));
        ReservationResult retry = inventoryService.reserveInventory(productId, 10, key);

        assertThat(retry.replayed()).isFalse();
        assertThat(retry.response().availableQuantity()).isEqualTo(10);
    }

    @Test
    void sameKeyConcurrentRequestsReserveExactlyOnce() throws Exception {
        UUID productId = newInventory(10);
        String key = key();

        List<Outcome<ReservationResult>> outcomes = ConcurrentRunner.runAll(100,
                i -> () -> inventoryService.reserveInventory(productId, 1, key));

        List<ReservationResult> results = outcomes.stream()
                .peek(o -> assertThat(o.failure()).as("no request may fail").isNull())
                .map(Outcome::value)
                .toList();

        // one request actually reserved; the other 99 got that same result back
        assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1);
        assertThat(results.stream().filter(ReservationResult::replayed)).hasSize(99);
        assertThat(results.stream().map(ReservationResult::response).distinct()).hasSize(1);

        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(stored.availableQuantity()).isEqualTo(9);
        assertThat(stored.reservedQuantity()).isEqualTo(1);
        assertThat(idempotencyRecordRepository.findByIdempotencyKey(key)).isPresent();
    }

    @Test
    void differentKeysConcurrentRequestsNeverOversell() throws Exception {
        int stock = 10;
        UUID productId = newInventory(stock);
        List<String> keys = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> key()).toList();

        List<Outcome<ReservationResult>> outcomes = ConcurrentRunner.runAll(100,
                i -> () -> inventoryService.reserveInventory(productId, 1, keys.get(i)));

        long succeeded = outcomes.stream().filter(Outcome::succeeded).count();
        long insufficient = outcomes.stream()
                .filter(o -> o.failure() instanceof InsufficientStockException).count();

        assertThat(succeeded).isEqualTo(stock);
        assertThat(insufficient).isEqualTo(100 - stock);
        assertThat(outcomes.stream().filter(Outcome::succeeded)
                .allMatch(o -> !o.value().replayed())).isTrue();

        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(stored.availableQuantity()).isZero();
        assertThat(stored.reservedQuantity()).isEqualTo(stock);

        // exactly the successful reservations were recorded, failures were not
        long recorded = keys.stream()
                .filter(k -> idempotencyRecordRepository.findByIdempotencyKey(k).isPresent())
                .count();
        assertThat(recorded).isEqualTo(stock);
    }
}
