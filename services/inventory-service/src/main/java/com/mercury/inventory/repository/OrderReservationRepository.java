package com.mercury.inventory.repository;

import com.mercury.inventory.model.OrderReservation;
import com.mercury.inventory.model.ReservationOutcome;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OrderReservationRepository extends JpaRepository<OrderReservation, UUID> {

    @EntityGraph(attributePaths = "items")
    Optional<OrderReservation> findWithItemsByOrderId(UUID orderId);

    /** Deletes the order reservations with this status created before {@code before}; returns how many. Only for FENCED (no items). */
    @Modifying
    @Query("delete from OrderReservation r where r.status = :status and r.createdAt < :before")
    int deleteByStatusCreatedBefore(@Param("status") ReservationOutcome status, @Param("before") Instant before);
}
