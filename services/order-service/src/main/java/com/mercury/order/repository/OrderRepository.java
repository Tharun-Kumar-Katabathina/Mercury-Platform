package com.mercury.order.repository;

import com.mercury.order.model.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import com.mercury.order.model.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);

    @Query("select o.reservationMode from Order o where o.id = :id")
    Optional<com.mercury.order.model.ReservationMode> findModeById(@Param("id") UUID id);

    /** The current status straight from the database, never from a cached entity. */
    @Query("select o.status from Order o where o.id = :id")
    Optional<OrderStatus> findStatusById(@Param("id") UUID id);
}
