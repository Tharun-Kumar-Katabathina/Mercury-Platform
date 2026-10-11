package com.mercury.inventory.service;

import com.mercury.inventory.dto.CreateInventoryRequest;
import com.mercury.inventory.dto.FenceResponse;
import com.mercury.inventory.dto.InventoryResponse;
import com.mercury.inventory.exception.IdempotencyKeyMismatchException;
import com.mercury.inventory.exception.ReservationFencedException;
import com.mercury.inventory.exception.ReservationNotFoundException;
import com.mercury.inventory.model.IdempotencyOperation;
import com.mercury.inventory.model.IdempotencyRecord;
import com.mercury.inventory.repository.IdempotencyRecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * Fencing a reservation key: once the caller has given up on a reservation, a reserve that arrives
 * later (retried, delayed, or still in flight) must not hold stock for it.
 */
@SpringBootTest
class InventoryReservationFenceTests {

    @Autowired
    private InventoryService inventoryService;

    @MockitoSpyBean
    private IdempotencyRecordRepository records;

    @PersistenceContext
    private EntityManager entityManager;   // shared, joins the service's transaction

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private UUID productWithStock(int stock) {
        UUID productId = UUID.randomUUID();
        inventoryService.createInventory(new CreateInventoryRequest(productId, stock));
        return productId;
    }

    private void assertStock(UUID productId, int available, int reserved) {
        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(stored.availableQuantity()).isEqualTo(available);
        assertThat(stored.reservedQuantity()).isEqualTo(reserved);
    }

    // ---- fence before reserve ---------------------------------------------------------------

    @Test
    void aReserveAfterTheFenceIsRefusedAndChangesNothing() {
        UUID productId = productWithStock(10);
        String key = key();

        assertThat(inventoryService.fenceReservation(productId, key).status()).isEqualTo("FENCED");

        assertThatThrownBy(() -> inventoryService.reserveInventory(productId, 3, key))
                .isInstanceOf(ReservationFencedException.class);
        assertStock(productId, 10, 0);
        assertThatThrownBy(() -> inventoryService.findReservation(productId, key))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void aReserveRetriedManyTimesAfterTheFenceIsRefusedEveryTime() {
        UUID productId = productWithStock(10);
        String key = key();
        inventoryService.fenceReservation(productId, key);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> inventoryService.reserveInventory(productId, 3, key))
                    .isInstanceOf(ReservationFencedException.class);
        }
        assertStock(productId, 10, 0);
    }

    @Test
    void fencingTwiceIsIdempotent() {
        UUID productId = productWithStock(10);
        String key = key();

        assertThat(inventoryService.fenceReservation(productId, key).status()).isEqualTo("FENCED");
        assertThat(inventoryService.fenceReservation(productId, key).status()).isEqualTo("FENCED");
        assertStock(productId, 10, 0);
    }

    @Test
    void aFenceSurvivesAndOtherKeysAreUnaffected() {
        UUID productId = productWithStock(10);
        inventoryService.fenceReservation(productId, key());

        inventoryService.reserveInventory(productId, 3, key());

        assertStock(productId, 7, 3);
    }

    // ---- reserve before fence ---------------------------------------------------------------

    @Test
    void aFenceAfterTheReserveReportsTheReservationSoTheCallerCanReleaseItExactlyOnce() {
        UUID productId = productWithStock(10);
        String key = key();
        var made = inventoryService.reserveInventory(productId, 3, key).response();

        FenceResponse fence = inventoryService.fenceReservation(productId, key);
        FenceResponse again = inventoryService.fenceReservation(productId, key);

        assertThat(fence.status()).isEqualTo("RESERVED");
        assertThat(fence.reservation()).isEqualTo(made);
        assertThat(again).isEqualTo(fence);
        assertStock(productId, 7, 3);                                     // fencing releases nothing by itself

        String releaseKey = key();
        inventoryService.releaseInventory(productId, 3, releaseKey);
        assertThat(inventoryService.releaseInventory(productId, 3, releaseKey).replayed()).isTrue();
        assertStock(productId, 10, 0);
    }

    @Test
    void aReserveRetryAfterAFenceThatFoundTheReservationStillReplays() {
        UUID productId = productWithStock(10);
        String key = key();
        inventoryService.reserveInventory(productId, 3, key);
        inventoryService.fenceReservation(productId, key);

        assertThat(inventoryService.reserveInventory(productId, 3, key).replayed()).isTrue();
        assertStock(productId, 7, 3);
    }

    // ---- misuse -----------------------------------------------------------------------------

    @Test
    void aKeyUsedForAnotherProductOrForARelease_cannotBeFenced() {
        UUID product = productWithStock(10);
        UUID other = productWithStock(10);
        String reserveKey = key();
        inventoryService.reserveInventory(product, 2, reserveKey);
        String releaseKey = key();
        inventoryService.releaseInventory(product, 1, releaseKey);

        assertThatThrownBy(() -> inventoryService.fenceReservation(other, reserveKey))
                .isInstanceOf(IdempotencyKeyMismatchException.class);
        assertThatThrownBy(() -> inventoryService.fenceReservation(product, releaseKey))
                .isInstanceOf(IdempotencyKeyMismatchException.class);
    }

    @Test
    void aFencedKeyCannotBeUsedForARelease() {
        UUID productId = productWithStock(10);
        String key = key();
        inventoryService.reserveInventory(productId, 2, key());
        inventoryService.fenceReservation(productId, key);

        assertThatThrownBy(() -> inventoryService.releaseInventory(productId, 1, key))
                .isInstanceOf(IdempotencyKeyMismatchException.class);
        assertStock(productId, 8, 2);
    }

    // ---- the in-flight race, made deterministic ----------------------------------------------

    @Test
    void aReserveStalledBeforeItsInsertLosesToAFenceThatCommitsFirstAndRollsBackItsStockChange() throws Exception {
        UUID productId = productWithStock(10);
        String key = key();
        Stall stall = stallInsertOf(IdempotencyOperation.RESERVE, key);

        CompletableFuture<Object> reserve = CompletableFuture.supplyAsync(() -> {
            try {
                return inventoryService.reserveInventory(productId, 3, key);
            } catch (RuntimeException e) {
                return e;
            }
        });
        assertThat(stall.reached.await(30, TimeUnit.SECONDS)).as("reserve reached its insert").isTrue();

        FenceResponse fence = inventoryService.fenceReservation(productId, key);   // commits while the reserve is stalled
        stall.proceed.countDown();

        assertThat(fence.status()).isEqualTo("FENCED");
        assertThat(reserve.get(30, TimeUnit.SECONDS)).isInstanceOf(ReservationFencedException.class);
        assertStock(productId, 10, 0);                                             // the stalled stock change rolled back
    }

    @Test
    void aFenceStalledBeforeItsInsertLosesToAReserveThatCommitsFirstAndReportsIt() throws Exception {
        UUID productId = productWithStock(10);
        String key = key();
        Stall stall = stallInsertOf(IdempotencyOperation.FENCED, key);

        CompletableFuture<FenceResponse> fence = CompletableFuture.supplyAsync(
                () -> inventoryService.fenceReservation(productId, key));
        assertThat(stall.reached.await(30, TimeUnit.SECONDS)).as("fence reached its insert").isTrue();

        inventoryService.reserveInventory(productId, 3, key);                      // commits while the fence is stalled
        stall.proceed.countDown();

        FenceResponse result = fence.get(30, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo("RESERVED");
        assertThat(result.reservation().quantityReserved()).isEqualTo(3);
        assertStock(productId, 7, 3);
    }

    // ---- many at once ---------------------------------------------------------------------

    @Test
    void concurrentReservesAndFencesOnOneKeyAlwaysAgreeOnASingleOutcome() throws Exception {
        for (int round = 0; round < 15; round++) {
            UUID productId = productWithStock(10);
            String key = key();

            List<ConcurrentRunner.Outcome<Object>> outcomes = ConcurrentRunner.runAll(8, i -> () ->
                    i % 2 == 0
                            ? inventoryService.reserveInventory(productId, 3, key)
                            : inventoryService.fenceReservation(productId, key));

            long reserved = outcomes.stream().filter(ConcurrentRunner.Outcome::succeeded)
                    .filter(o -> o.value() instanceof com.mercury.inventory.dto.ReservationResult).count();
            boolean anyFencedAnswer = outcomes.stream().filter(ConcurrentRunner.Outcome::succeeded)
                    .anyMatch(o -> o.value() instanceof FenceResponse f && f.status().equals("FENCED"));
            boolean anyReservedAnswer = outcomes.stream().filter(ConcurrentRunner.Outcome::succeeded)
                    .anyMatch(o -> o.value() instanceof FenceResponse f && f.status().equals("RESERVED"));

            assertThat(outcomes).allSatisfy(o -> assertThat(o.failure() == null
                    || o.failure() instanceof ReservationFencedException).as("%s", o.failure()).isTrue());
            if (reserved > 0) {                       // the reserve won: every fence must say so, and exactly 3 are held
                assertThat(anyFencedAnswer).isFalse();
                assertStock(productId, 7, 3);
            } else {                                  // the fence won: nothing may be held, no fence may claim a reservation
                assertThat(anyReservedAnswer).isFalse();
                assertStock(productId, 10, 0);
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static final class Stall {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);
    }

    /** Holds the first insert of an {@code operation} record for {@code key} until the test lets it go. */
    private Stall stallInsertOf(IdempotencyOperation operation, String key) {
        reset(records);
        Stall stall = new Stall();
        doAnswer((InvocationOnMock call) -> {
            IdempotencyRecord record = call.getArgument(0);
            if (record.getOperation() == operation && key.equals(record.getIdempotencyKey())) {
                stall.reached.countDown();
                if (!stall.proceed.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test never released the stalled insert");
                }
            }
            // a repository proxy has no real method to call on the spy: use an unspied instance on the same EntityManager
            try {
                return new JpaRepositoryFactory(entityManager).getRepository(IdempotencyRecordRepository.class).saveAndFlush(record);
            } catch (jakarta.persistence.PersistenceException e) {
                // that instance is not wrapped by Spring's exception translation, the real repository is
                throw new org.springframework.dao.DataIntegrityViolationException(e.getMessage(), e);
            }
        }).when(records).saveAndFlush(any(IdempotencyRecord.class));
        return stall;
    }
}
