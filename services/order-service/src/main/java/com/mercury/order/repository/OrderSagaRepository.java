package com.mercury.order.repository;

import com.mercury.order.model.OrderSaga;
import com.mercury.order.model.SagaState;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderSagaRepository extends JpaRepository<OrderSaga, UUID> {

    /**
     * Picks due sagas and row-locks them (FOR UPDATE SKIP LOCKED). A second worker running the same
     * query at the same moment gets different rows instead of waiting for, or doubling up on, these.
     * The lock timeout hint -2 is "skip locked".
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select s from OrderSaga s
            where s.state in :states
              and s.nextAttemptAt <= :now
              and (s.lockedUntil is null or s.lockedUntil <= :now)
            order by s.nextAttemptAt
            """)
    List<OrderSaga> lockDue(@Param("states") Collection<SagaState> states,
                            @Param("now") Instant now,
                            Pageable batch);

    long countByState(SagaState state);

    @Query("select count(s) from OrderSaga s where s.state in :states and s.nextAttemptAt <= :now")
    long countDue(@Param("states") Collection<SagaState> states, @Param("now") Instant now);

    List<OrderSaga> findTop100ByStateInOrderByCreatedAtAsc(Collection<SagaState> states);
}
