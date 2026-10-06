package com.mercury.order.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /** fixed when the order is created: switching the configuration never changes an order in flight */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReservationMode reservationMode = ReservationMode.SYNC;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Order() {
    }

    public static Order pending(BigDecimal totalAmount) {
        return pending(totalAmount, ReservationMode.SYNC);
    }

    public static Order pending(BigDecimal totalAmount, ReservationMode mode) {
        Order order = new Order();
        order.status = OrderStatus.PENDING;
        order.totalAmount = totalAmount;
        order.reservationMode = mode;
        return order;
    }

    public void addItem(OrderItem item) {
        item.setOrder(this);
        items.add(item);
    }

    /** Only a PENDING order can be decided; CONFIRMED and CANCELLED are final. */
    public void confirm() {
        transitionFromPendingTo(OrderStatus.CONFIRMED);
    }

    public void cancel() {
        transitionFromPendingTo(OrderStatus.CANCELLED);
    }

    private void transitionFromPendingTo(OrderStatus target) {
        if (status != OrderStatus.PENDING) {
            throw new IllegalStateException(
                    "Order " + id + " is " + status + " and cannot become " + target);
        }
        status = target;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public ReservationMode getReservationMode() {
        return reservationMode;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
