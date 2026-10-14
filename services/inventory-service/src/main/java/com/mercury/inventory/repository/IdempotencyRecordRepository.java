package com.mercury.inventory.repository;

import com.mercury.inventory.model.IdempotencyOperation;
import com.mercury.inventory.model.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByIdempotencyKey(String idempotencyKey);

    /** Deletes the records of one operation created before {@code before}; returns how many. */
    @Modifying
    @Query("delete from IdempotencyRecord r where r.operation = :operation and r.createdAt < :before")
    int deleteByOperationCreatedBefore(@Param("operation") IdempotencyOperation operation, @Param("before") Instant before);
}
