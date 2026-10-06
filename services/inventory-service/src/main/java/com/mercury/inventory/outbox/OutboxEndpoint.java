package com.mercury.inventory.outbox;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /actuator/outbox: is anything waiting to be published, for how long, after how many attempts.
 * Read-only. Shows event ids, types and order ids only; payloads are never exposed.
 */
@Component
@Endpoint(id = "outbox")
public class OutboxEndpoint {

    private final OutboxRepository outbox;
    private final Clock clock;

    public OutboxEndpoint(OutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @ReadOperation
    public Map<String, Object> overview() {
        Instant now = Instant.now(clock);

        List<Map<String, Object>> waiting = outbox.findAll(PageRequest.of(0, 5000)).stream()
                .filter(e -> e.getPublishedAt() == null)
                .limit(50)
                .map(e -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("eventId", e.getEventId());
                    entry.put("eventType", e.getEventType());
                    entry.put("orderId", e.getAggregateId());
                    entry.put("attempts", e.getAttemptCount());
                    entry.put("ageSeconds", Duration.between(e.getCreatedAt(), now).toSeconds());
                    entry.put("nextAttemptAt", e.getNextAttemptAt());
                    entry.put("lastError", e.getLastError());
                    return entry;
                }).toList();

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("pending", outbox.countByPublishedAtIsNull());
        overview.put("published", outbox.countByPublishedAtIsNotNull());
        overview.put("oldestPendingAgeSeconds", outbox.oldestUnpublished()
                .map(oldest -> Duration.between(oldest, now).toSeconds()).orElse(0L));
        overview.put("maxAttemptsOfPending", outbox.maxAttemptsOfUnpublished());
        overview.put("pendingEvents", waiting);
        return overview;
    }
}
