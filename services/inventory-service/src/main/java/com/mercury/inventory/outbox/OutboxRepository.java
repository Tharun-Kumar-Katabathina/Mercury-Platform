package com.mercury.inventory.outbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Events ready to publish, row-locked and skipping rows another publisher holds. An event is
     * only eligible when no OLDER event of the same order is still unpublished, so events for one
     * order can never overtake each other, even across failures and retries.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select e from OutboxEvent e
            where e.publishedAt is null
              and e.nextAttemptAt <= :now
              and (e.lockedUntil is null or e.lockedUntil <= :now)
              and not exists (
                    select 1 from OutboxEvent older
                    where older.aggregateId = e.aggregateId
                      and older.seq < e.seq
                      and older.publishedAt is null)
            order by e.seq
            """)
    List<OutboxEvent> lockDue(@Param("now") Instant now, Pageable batch);

    long countByPublishedAtIsNull();

    long countByPublishedAtIsNotNull();

    @Query("select min(e.createdAt) from OutboxEvent e where e.publishedAt is null")
    Optional<Instant> oldestUnpublished();

    @Query("select coalesce(max(e.attemptCount), 0) from OutboxEvent e where e.publishedAt is null")
    int maxAttemptsOfUnpublished();

    List<OutboxEvent> findByAggregateIdOrderBySeq(java.util.UUID aggregateId);
}
