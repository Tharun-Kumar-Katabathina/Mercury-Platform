package com.mercury.order.service;

import com.mercury.order.dto.OrderResponse;
import com.mercury.order.exception.OrderNotFoundException;
import com.mercury.order.model.Order;
import com.mercury.order.model.OrderItem;
import com.mercury.order.model.OrderStatus;
import com.mercury.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Order model, Flyway schema and retrieval, against H2 in PostgreSQL mode. */
@SpringBootTest
class OrderPersistenceTests {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderService orderService;

    private static OrderItem item(UUID productId, String name, String price, int quantity) {
        return new OrderItem(productId, name, "SKU-" + productId, new BigDecimal(price), quantity);
    }

    private Order savedOrder(OrderItem... items) {
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItem item : items) {
            total = total.add(item.subtotal());
        }
        Order order = Order.pending(total);
        for (OrderItem item : items) {
            order.addItem(item);
        }
        return orderRepository.saveAndFlush(order);
    }

    @Test
    void persistsOrderWithItemSnapshotsAndReadsThemBack() {
        UUID productId = UUID.randomUUID();
        Order order = savedOrder(item(productId, "MacBook Pro 16", "2499.00", 2));

        OrderResponse response = orderService.getOrder(order.getId());

        assertThat(response.id()).isEqualTo(order.getId());
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.totalAmount()).isEqualByComparingTo("4998.00");
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).productId()).isEqualTo(productId);
        assertThat(response.items().get(0).productName()).isEqualTo("MacBook Pro 16");
        assertThat(response.items().get(0).unitPrice()).isEqualByComparingTo("2499.00");
        assertThat(response.items().get(0).quantity()).isEqualTo(2);
        assertThat(response.items().get(0).subtotal()).isEqualByComparingTo("4998.00");
    }

    @Test
    void orderHistoryIsASnapshotOwnedByTheOrder() {
        // the order carries its own copy of name and price; nothing here refers to Product
        UUID productId = UUID.randomUUID();
        Order order = savedOrder(item(productId, "MacBook Pro 16", "2499.00", 1));

        OrderResponse response = orderService.getOrder(order.getId());

        assertThat(response.items().get(0).unitPrice()).isEqualByComparingTo("2499.00");
        assertThat(response.items().get(0).sku()).isEqualTo("SKU-" + productId);
    }

    @Test
    void unknownOrderIsNotFound() {
        assertThatThrownBy(() -> orderService.getOrder(UUID.randomUUID()))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void statusStartsPendingAndMovesToConfirmedOnce() {
        Order order = savedOrder(item(UUID.randomUUID(), "Item", "10.00", 1));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);

        order.confirm();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);

        assertThatThrownBy(order::cancel).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(order::confirm).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelledOrderIsFinal() {
        Order order = savedOrder(item(UUID.randomUUID(), "Item", "10.00", 1));

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThatThrownBy(order::confirm).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void databaseRejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> savedOrder(item(UUID.randomUUID(), "Item", "10.00", 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNegativeTotal() {
        Order order = Order.pending(new BigDecimal("-1.00"));

        assertThatThrownBy(() -> orderRepository.saveAndFlush(order))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsSameProductTwiceInOneOrder() {
        UUID productId = UUID.randomUUID();

        assertThatThrownBy(() -> savedOrder(
                item(productId, "Item", "10.00", 1),
                item(productId, "Item", "10.00", 2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
