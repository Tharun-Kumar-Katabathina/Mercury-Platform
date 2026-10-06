package com.mercury.inventory.model;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "order_reservation_items")
public class OrderReservationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private OrderReservation reservation;

    @Column(nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    protected OrderReservationItem() {
    }

    OrderReservationItem(OrderReservation reservation, UUID productId, int quantity) {
        this.reservation = reservation;
        this.productId = productId;
        this.quantity = quantity;
    }

    public UUID getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }
}
