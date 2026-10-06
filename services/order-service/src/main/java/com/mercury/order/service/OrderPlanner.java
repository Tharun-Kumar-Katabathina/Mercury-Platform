package com.mercury.order.service;

import com.mercury.order.client.ProductClient;
import com.mercury.order.dto.CreateOrderItemRequest;
import com.mercury.order.dto.CreateOrderRequest;
import com.mercury.order.dto.ProductDetails;
import com.mercury.order.exception.InvalidOrderException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a client request into an OrderDraft. It has no side effects: it only reads from
 * Product Service, so it is always safe to run before anything is saved or reserved.
 *
 * The client never supplies a price; name, SKU and price always come from Product Service.
 */
@Component
public class OrderPlanner {

    private final ProductClient productClient;

    public OrderPlanner(ProductClient productClient) {
        this.productClient = productClient;
    }

    public OrderDraft plan(CreateOrderRequest request) {
        return draft(validate(request));
    }

    /** Looks up every product and snapshots it. Input must come from {@link #validate}. */
    public OrderDraft draft(List<CreateOrderItemRequest> requested) {

        List<OrderDraft.Item> items = requested.stream()
                .map(this::snapshot)
                .toList();

        BigDecimal total = items.stream()
                .map(OrderDraft.Item::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new OrderDraft(items, total);
    }

    /** Structural checks only, no remote calls. Returns the items in canonical productId order. */
    public List<CreateOrderItemRequest> validate(CreateOrderRequest request) {

        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new InvalidOrderException("an order needs at least one item");
        }

        Set<UUID> seen = new HashSet<>();
        for (CreateOrderItemRequest item : request.items()) {
            if (item == null || item.productId() == null) {
                throw new InvalidOrderException("every item needs a productId");
            }
            if (item.quantity() == null || item.quantity() < 1) {
                throw new InvalidOrderException(
                        "quantity must be at least 1 for product " + item.productId());
            }
            if (!seen.add(item.productId())) {
                throw new InvalidOrderException(
                        "product " + item.productId() + " appears more than once; "
                                + "send one item with the total quantity");
            }
        }

        // Canonical order: the same request always plans, hashes and reserves in the same order.
        return request.items().stream()
                .sorted(Comparator.comparing(CreateOrderItemRequest::productId))
                .toList();
    }

    private OrderDraft.Item snapshot(CreateOrderItemRequest item) {
        ProductDetails product = productClient.getProduct(item.productId());
        return new OrderDraft.Item(
                product.id() != null ? product.id() : item.productId(),
                product.name(),
                product.sku(),
                product.price(),
                item.quantity());
    }
}
