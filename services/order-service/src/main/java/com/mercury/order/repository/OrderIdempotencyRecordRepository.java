package com.mercury.order.repository;

import com.mercury.order.model.OrderIdempotencyRecord;
import com.mercury.order.model.ClaimView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OrderIdempotencyRecordRepository extends JpaRepository<OrderIdempotencyRecord, UUID> {

    Optional<OrderIdempotencyRecord> findByIdempotencyKey(String idempotencyKey);

    /** Always reads the database; see {@link ClaimView}. */
    @Query("""
            select new com.mercury.order.model.ClaimView(
                r.status, r.requestHash, r.orderId, r.responseSnapshot)
            from OrderIdempotencyRecord r
            where r.idempotencyKey = :idempotencyKey
            """)
    Optional<ClaimView> findClaimView(@Param("idempotencyKey") String idempotencyKey);

    void deleteByIdempotencyKey(String idempotencyKey);

    void deleteByOrderId(UUID orderId);
}
