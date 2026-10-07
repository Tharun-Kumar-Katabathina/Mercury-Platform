package com.mercury.order.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A validated order that has not been saved or reserved yet: authoritative product data
 * (name, sku, price) already snapshotted, total already calculated.
 */
public record OrderDraft(List<Item> items, BigDecimal totalAmount, String customerId) {

    /** A draft with no owner (security off, tests). */
    public OrderDraft(List<Item> items, BigDecimal totalAmount) {
        this(items, totalAmount, null);
    }

    public OrderDraft withCustomer(String customerId) {
        return new OrderDraft(items, totalAmount, customerId);
    }


    public record Item(
            UUID productId,
            String productName,
            String sku,
            BigDecimal unitPrice,
            int quantity
    ) {
        public BigDecimal subtotal() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }
}
