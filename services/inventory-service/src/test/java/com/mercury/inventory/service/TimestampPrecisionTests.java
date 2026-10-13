package com.mercury.inventory.service;

import com.mercury.inventory.model.Inventory;
import com.mercury.inventory.repository.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The timestamp columns keep microseconds; the clock is finer on Linux (nanoseconds). A row stamped from the raw clock
 * differs from the entity the caller was handed, because the database rounds what it cannot keep. Each row is written
 * many times so that the difference would show on any host whose clock is finer than the column.
 */
@SpringBootTest
class TimestampPrecisionTests {

    private static final int ROUNDS = 25;

    @Autowired private InventoryRepository inventory;

    private Inventory newInventory() {
        Inventory row = new Inventory();
        row.setProductId(UUID.randomUUID());
        row.setAvailableQuantity(10);
        row.setReservedQuantity(0);
        return inventory.saveAndFlush(row);
    }

    @Test
    void anInventoryRowIsStoredAsStamped() {
        for (int i = 0; i < ROUNDS; i++) {
            Inventory written = newInventory();
            Inventory stored = inventory.findById(written.getId()).orElseThrow();
            assertThat(stored.getCreatedAt()).isEqualTo(written.getCreatedAt());
            assertThat(stored.getUpdatedAt()).isEqualTo(written.getUpdatedAt());
        }
    }

    @Test
    void anInventoryUpdateIsStoredAsStamped() {
        for (int i = 0; i < ROUNDS; i++) {
            Inventory row = newInventory();
            row.setAvailableQuantity(9);
            Inventory written = inventory.saveAndFlush(row);
            Inventory stored = inventory.findById(written.getId()).orElseThrow();
            assertThat(stored.getUpdatedAt()).isEqualTo(written.getUpdatedAt());
        }
    }
}
