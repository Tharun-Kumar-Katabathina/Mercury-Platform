package com.mercury.order.repository;

import com.mercury.order.model.OrderItem;
import com.mercury.order.model.ReservationStatus;
import com.mercury.order.service.ItemProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    /** Straight UPDATE: saga progress is written without loading or locking the whole order. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OrderItem i set i.reservationStatus = :status "
            + "where i.order.id = :orderId and i.productId = :productId")
    int updateStatus(@Param("orderId") UUID orderId,
                     @Param("productId") UUID productId,
                     @Param("status") ReservationStatus status);

    /** Plain values, always read from the database. Canonical (productId) order. */
    @Query("select new com.mercury.order.service.ItemProgress(i.productId, i.quantity, i.reservationStatus) "
            + "from OrderItem i where i.order.id = :orderId order by i.productId")
    List<ItemProgress> findProgress(@Param("orderId") UUID orderId);
}
