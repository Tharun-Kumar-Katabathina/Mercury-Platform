package com.mercury.inventory.model;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The outcome of reserving a whole order, all items or none. The id is assigned (the order id), so
 * this implements Persistable: a save is always an INSERT, and two racing commands for the same
 * order hit the primary key instead of one silently merging over the other.
 */
@Entity
@Table(name = "order_reservations")
public class OrderReservation implements Persistable<UUID> {

    @Id
    private UUID orderId;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReservationOutcome status;

    @Column(length = 100)
    private String reason;

    private UUID rejectedProductId;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderReservationItem> items = new ArrayList<>();

    @Column(nullable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected OrderReservation() {
    }

    /** the idempotency key of an order-level reservation: one per order */
    public static String keyFor(UUID orderId) {
        return "order:" + orderId;
    }

    public static OrderReservation reserved(UUID orderId, List<ItemQuantity> items, Instant now) {
        OrderReservation reservation = new OrderReservation();
        reservation.orderId = orderId;
        reservation.idempotencyKey = keyFor(orderId);
        reservation.status = ReservationOutcome.RESERVED;
        reservation.createdAt = now;
        items.forEach(i -> reservation.items.add(
                new OrderReservationItem(reservation, i.productId(), i.quantity())));
        return reservation;
    }

    public static OrderReservation rejected(UUID orderId, String reason, UUID productId, Instant now) {
        OrderReservation reservation = new OrderReservation();
        reservation.orderId = orderId;
        reservation.idempotencyKey = keyFor(orderId);
        reservation.status = ReservationOutcome.REJECTED;
        reservation.reason = reason;
        reservation.rejectedProductId = productId;
        reservation.createdAt = now;
        return reservation;
    }

    public record ItemQuantity(UUID productId, int quantity) {
    }

    @Override
    public UUID getId() {
        return orderId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public ReservationOutcome getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public UUID getRejectedProductId() {
        return rejectedProductId;
    }

    public List<OrderReservationItem> getItems() {
        return items;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
