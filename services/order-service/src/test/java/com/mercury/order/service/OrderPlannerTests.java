package com.mercury.order.service;

import com.mercury.order.client.ProductClient;
import com.mercury.order.dto.CreateOrderItemRequest;
import com.mercury.order.dto.CreateOrderRequest;
import com.mercury.order.dto.ProductDetails;
import com.mercury.order.exception.InvalidOrderException;
import com.mercury.order.exception.ProductServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderPlannerTests {

    private ProductClient productClient;
    private OrderPlanner planner;

    @BeforeEach
    void setUp() {
        productClient = mock(ProductClient.class);
        planner = new OrderPlanner(productClient);
    }

    private UUID productWithPrice(String name, String sku, String price) {
        UUID id = UUID.randomUUID();
        when(productClient.getProduct(id))
                .thenReturn(new ProductDetails(id, name, sku, new BigDecimal(price)));
        return id;
    }

    private static CreateOrderRequest order(CreateOrderItemRequest... items) {
        return new CreateOrderRequest(List.of(items));
    }

    private static CreateOrderItemRequest item(UUID productId, Integer quantity) {
        return new CreateOrderItemRequest(productId, quantity);
    }

    @Test
    void snapshotsNameSkuAndPriceFromProductService() {
        UUID id = productWithPrice("MacBook Pro 16", "MBP-16-M4", "2499.00");

        OrderDraft draft = planner.plan(order(item(id, 2)));

        assertThat(draft.items()).hasSize(1);
        OrderDraft.Item snapshot = draft.items().get(0);
        assertThat(snapshot.productId()).isEqualTo(id);
        assertThat(snapshot.productName()).isEqualTo("MacBook Pro 16");
        assertThat(snapshot.sku()).isEqualTo("MBP-16-M4");
        assertThat(snapshot.unitPrice()).isEqualByComparingTo("2499.00");
        assertThat(snapshot.quantity()).isEqualTo(2);
    }

    @Test
    void calculatesTotalAcrossItems() {
        UUID laptop = productWithPrice("Laptop", "L-1", "2499.00");
        UUID mouse = productWithPrice("Mouse", "M-1", "19.99");

        OrderDraft draft = planner.plan(order(item(laptop, 2), item(mouse, 3)));

        // 2 x 2499.00 + 3 x 19.99 = 4998.00 + 59.97
        assertThat(draft.totalAmount()).isEqualByComparingTo("5057.97");
    }

    @Test
    void usesThePriceProductServiceReportsNow() {
        UUID id = UUID.randomUUID();
        when(productClient.getProduct(id))
                .thenReturn(new ProductDetails(id, "Laptop", "L-1", new BigDecimal("2699.00")));

        OrderDraft draft = planner.plan(order(item(id, 1)));

        assertThat(draft.totalAmount()).isEqualByComparingTo("2699.00");
    }

    @Test
    void plansItemsInCanonicalProductIdOrder() {
        UUID a = productWithPrice("A", "A-1", "1.00");
        UUID b = productWithPrice("B", "B-1", "1.00");
        UUID c = productWithPrice("C", "C-1", "1.00");

        List<UUID> forward = planner.plan(order(item(a, 1), item(b, 1), item(c, 1)))
                .items().stream().map(OrderDraft.Item::productId).toList();
        List<UUID> shuffled = planner.plan(order(item(c, 1), item(a, 1), item(b, 1)))
                .items().stream().map(OrderDraft.Item::productId).toList();

        assertThat(shuffled).isEqualTo(forward);
        assertThat(forward).isSorted();
    }

    @Test
    void rejectsEmptyOrderWithoutCallingProductService() {
        assertThatThrownBy(() -> planner.plan(new CreateOrderRequest(List.of())))
                .isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> planner.plan(new CreateOrderRequest(null)))
                .isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> planner.plan(null))
                .isInstanceOf(InvalidOrderException.class);

        verifyNoInteractions(productClient);
    }

    @Test
    void rejectsNonPositiveOrMissingQuantityWithoutCallingProductService() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> planner.plan(order(item(id, 0))))
                .isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> planner.plan(order(item(id, -3))))
                .isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> planner.plan(order(item(id, null))))
                .isInstanceOf(InvalidOrderException.class);

        verifyNoInteractions(productClient);
    }

    @Test
    void rejectsMissingProductId() {
        assertThatThrownBy(() -> planner.plan(order(item(null, 1))))
                .isInstanceOf(InvalidOrderException.class);

        verifyNoInteractions(productClient);
    }

    @Test
    void rejectsSameProductTwice() {
        UUID id = productWithPrice("Laptop", "L-1", "10.00");

        assertThatThrownBy(() -> planner.plan(order(item(id, 1), item(id, 2))))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining(id.toString());

        verifyNoInteractions(productClient);
    }

    @Test
    void invalidItemAnywhereStopsBeforeAnyProductLookup() {
        UUID good = productWithPrice("Good", "G-1", "10.00");

        assertThatThrownBy(() -> planner.plan(order(item(good, 1), item(UUID.randomUUID(), 0))))
                .isInstanceOf(InvalidOrderException.class);

        verify(productClient, never()).getProduct(any());
    }

    @Test
    void unknownProductPropagatesProductNotFound() {
        UUID id = UUID.randomUUID();
        when(productClient.getProduct(id)).thenThrow(new ProductServiceException(
                HttpStatus.NOT_FOUND, "{\"error\":\"PRODUCT_NOT_FOUND\"}"));

        assertThatThrownBy(() -> planner.plan(order(item(id, 1))))
                .isInstanceOfSatisfying(ProductServiceException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }

    @Test
    void productServiceDownPropagates() {
        UUID id = UUID.randomUUID();
        when(productClient.getProduct(id))
                .thenThrow(new ProductServiceException(HttpStatus.SERVICE_UNAVAILABLE, null));

        assertThatThrownBy(() -> planner.plan(order(item(id, 1))))
                .isInstanceOfSatisfying(ProductServiceException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(503));
    }
}
