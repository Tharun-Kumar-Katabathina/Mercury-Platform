package com.mercury.order.service;

import com.mercury.order.model.OrderSaga;
import com.mercury.order.model.SagaState;
import com.mercury.order.repository.OrderSagaRepository;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /actuator/sagas: which orders are not finished, how long they have been waiting and how many
 * recovery attempts they have had. Read-only on purpose; recovery itself is never triggered from
 * here. Shows order ids and states only, never idempotency keys.
 */
@Component
@Endpoint(id = "sagas")
public class SagasEndpoint {

    private static final List<SagaState> UNFINISHED =
            List.of(SagaState.RESERVING, SagaState.AWAITING_INVENTORY, SagaState.COMPENSATING, SagaState.RECOVERY_FAILED);

    private final OrderSagaRepository sagas;
    private final Clock clock;

    public SagasEndpoint(OrderSagaRepository sagas, Clock clock) {
        this.sagas = sagas;
        this.clock = clock;
    }

    @ReadOperation
    public Map<String, Object> overview() {

        Instant now = Instant.now(clock);

        Map<SagaState, Long> counts = new EnumMap<>(SagaState.class);
        for (SagaState state : SagaState.values()) {
            counts.put(state, sagas.countByState(state));
        }

        List<OrderSaga> unfinished = sagas.findTop100ByStateInOrderByCreatedAtAsc(UNFINISHED);
        List<Map<String, Object>> entries = unfinished.stream().map(saga -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("orderId", saga.getOrderId());
            entry.put("state", saga.getState());
            entry.put("attempts", saga.getAttemptCount());
            entry.put("ageSeconds", Duration.between(saga.getCreatedAt(), now).toSeconds());
            entry.put("nextAttemptAt", saga.getNextAttemptAt());
            entry.put("lastError", saga.getLastError());
            return entry;
        }).toList();

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("countsByState", counts);
        overview.put("dueForRecovery", sagas.countDue(
                List.of(SagaState.RESERVING, SagaState.AWAITING_INVENTORY, SagaState.COMPENSATING), now));
        overview.put("oldestUnfinishedAgeSeconds", unfinished.stream()
                .mapToLong(saga -> Duration.between(saga.getCreatedAt(), now).toSeconds())
                .max().orElse(0));
        overview.put("unfinished", entries);
        return overview;
    }
}
