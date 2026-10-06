package com.mercury.inventory.repository;

import com.mercury.inventory.model.OrderReservation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrderReservationRepository extends JpaRepository<OrderReservation, UUID> {

    @EntityGraph(attributePaths = "items")
    Optional<OrderReservation> findWithItemsByOrderId(UUID orderId);
}
