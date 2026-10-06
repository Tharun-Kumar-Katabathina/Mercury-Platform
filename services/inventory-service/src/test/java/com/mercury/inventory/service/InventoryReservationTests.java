package com.mercury.inventory.service;

import com.mercury.inventory.dto.CreateInventoryRequest;
import com.mercury.inventory.dto.InventoryResponse;
import com.mercury.inventory.dto.ReservationResponse;
import com.mercury.inventory.exception.InsufficientStockException;
import com.mercury.inventory.exception.InventoryNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Enough attempts that no request can exhaust its retries: every conflict means another
// reservation committed, and at most 10 can (stock = 10), so 15 attempts always suffice.
@SpringBootTest(properties = "inventory.reserve.max-attempts=15")
class InventoryReservationTests {

    @Autowired
    private InventoryService inventoryService;

    private UUID newInventory(int available) {
        UUID productId = UUID.randomUUID();
        inventoryService.createInventory(new CreateInventoryRequest(productId, available));
        return productId;
    }

    @Test
    void reserveMovesQuantityFromAvailableToReserved() {
        UUID productId = newInventory(10);

        ReservationResponse response = inventoryService.reserveInventory(productId, 2);

        assertThat(response.quantityReserved()).isEqualTo(2);
        assertThat(response.availableQuantity()).isEqualTo(8);
        assertThat(response.reservedQuantity()).isEqualTo(2);

        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(stored.availableQuantity()).isEqualTo(8);
        assertThat(stored.reservedQuantity()).isEqualTo(2);
    }

    @Test
    void reservingMoreThanAvailableFailsAndLeavesInventoryUnchanged() {
        UUID productId = newInventory(10);
        inventoryService.reserveInventory(productId, 2);

        assertThatThrownBy(() -> inventoryService.reserveInventory(productId, 10))
                .isInstanceOf(InsufficientStockException.class);

        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(stored.availableQuantity()).isEqualTo(8);
        assertThat(stored.reservedQuantity()).isEqualTo(2);
    }

    @Test
    void canReserveExactlyAllRemainingStock() {
        UUID productId = newInventory(5);

        ReservationResponse response = inventoryService.reserveInventory(productId, 5);

        assertThat(response.availableQuantity()).isZero();
        assertThat(response.reservedQuantity()).isEqualTo(5);
    }

    @Test
    void reservingUnknownProductThrowsNotFound() {
        assertThatThrownBy(() -> inventoryService.reserveInventory(UUID.randomUUID(), 1))
                .isInstanceOf(InventoryNotFoundException.class);
    }

    @Test
    void concurrentReservationsNeverOversell() throws Exception {
        int stock = 10;
        int requests = 50;
        UUID productId = newInventory(stock);

        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    inventoryService.reserveInventory(productId, 1);
                    succeeded.incrementAndGet();
                } catch (InsufficientStockException e) {
                    insufficient.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        InventoryResponse stored = inventoryService.getInventory(productId);

        assertThat(unexpected).isEmpty();
        assertThat(succeeded.get()).isEqualTo(stock);
        assertThat(insufficient.get()).isEqualTo(requests - stock);
        assertThat(stored.availableQuantity()).isZero();
        assertThat(stored.reservedQuantity()).isEqualTo(stock);
    }
}
