package com.mercury.order.service;

import com.mercury.order.model.Order;
import com.mercury.order.model.OrderIdempotencyRecord;
import com.mercury.order.model.OrderItem;
import com.mercury.order.model.OrderSaga;
import com.mercury.order.repository.OrderIdempotencyRecordRepository;
import com.mercury.order.repository.OrderRepository;
import com.mercury.order.repository.OrderSagaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The timestamp columns keep microseconds; the clock is finer on Linux (nanoseconds). A row stamped from the raw clock
 * differs from the entity the caller was handed, because the database rounds what it cannot keep. Each row here is
 * written many times so that the difference would show on any host whose clock is finer than the column.
 */
@SpringBootTest
class TimestampPrecisionTests {

    private static final int ROUNDS = 25;

    @Autowired private OrderRepository orders;
    @Autowired private OrderIdempotencyRecordRepository records;
    @Autowired private OrderSagaRepository sagas;

    private Order newOrder() {
        Order order = Order.pending(new BigDecimal("10.00"));
        order.addItem(new OrderItem(UUID.randomUUID(), "Item", "SKU-" + UUID.randomUUID(), new BigDecimal("10.00"), 1));
        return orders.saveAndFlush(order);
    }

    @Test
    void anOrderIsStoredAsStamped() {
        for (int i = 0; i < ROUNDS; i++) {
            Order written = newOrder();
            Order stored = orders.findById(written.getId()).orElseThrow();
            assertThat(stored.getCreatedAt()).isEqualTo(written.getCreatedAt());
            assertThat(stored.getUpdatedAt()).isEqualTo(written.getUpdatedAt());
        }
    }

    @Test
    void anOrderUpdateIsStoredAsStamped() {
        for (int i = 0; i < ROUNDS; i++) {
            Order order = newOrder();
            order.ownedBy("customer-" + i);
            Order written = orders.saveAndFlush(order);
            Order stored = orders.findById(written.getId()).orElseThrow();
            assertThat(stored.getUpdatedAt()).isEqualTo(written.getUpdatedAt());
        }
    }

    @Test
    void anIdempotencyRecordIsStoredAsStamped() {
        for (int i = 0; i < ROUNDS; i++) {
            Order order = newOrder();
            OrderIdempotencyRecord written = records.saveAndFlush(
                    OrderIdempotencyRecord.claim("key-" + UUID.randomUUID(), "hash", order.getId()));
            OrderIdempotencyRecord stored = records.findById(written.getId()).orElseThrow();
            assertThat(stored.getCreatedAt()).isEqualTo(written.getCreatedAt());
            assertThat(stored.getUpdatedAt()).isEqualTo(written.getUpdatedAt());
        }
    }

    @Test
    void aSagaIsStoredAsStamped() {
        for (int i = 0; i < ROUNDS; i++) {
            Order order = newOrder();
            Instant now = Instant.now();
            OrderSaga written = sagas.saveAndFlush(OrderSaga.start(order.getId(), now, now));
            OrderSaga stored = sagas.findById(written.getOrderId()).orElseThrow();
            assertThat(stored.getCreatedAt()).isEqualTo(written.getCreatedAt());
            assertThat(stored.getUpdatedAt()).isEqualTo(written.getUpdatedAt());
        }
    }
}
