package com.mercury.recommendation.service;

import com.mercury.recommendation.exception.InvalidEventException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Learns from order events. OrderCreated is remembered; only OrderConfirmed turns it into a purchase (a cancelled order
 * teaches nothing). The "event handled" marker is written in the same transaction as the learning, so a redelivered
 * event changes nothing the second time.
 */
@Service
public class OrderEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderEventHandler.class);

    public enum Result { LEARNED, REMEMBERED, FORGOTTEN, DUPLICATE, IGNORED }

    private final JsonMapper json;
    private final FeatureStore store;

    public OrderEventHandler(JsonMapper json, FeatureStore store) {
        this.json = json;
        this.store = store;
    }

    @Transactional
    public Result handle(String payload) {
        JsonNode event;
        try {
            event = json.readTree(payload);
        } catch (RuntimeException e) {
            throw new InvalidEventException("not JSON", e);
        }
        String type = text(event, "eventType");
        if (!List.of("OrderCreated", "OrderConfirmed", "OrderCancelled").contains(type)) {
            return Result.IGNORED;
        }
        UUID eventId = uuid(event, "eventId");
        UUID orderId = uuid(event, "orderId");
        if (!store.markProcessed(eventId)) {
            return Result.DUPLICATE;
        }
        switch (type) {
            case "OrderCreated" -> {
                List<String> products = new ArrayList<>();
                for (JsonNode item : event.path("items")) {
                    products.add(item.path("productId").asString());
                }
                store.savePending(orderId, text(event, "customerId"), String.join(",", products));
                return Result.REMEMBERED;
            }
            case "OrderConfirmed" -> {
                Optional<FeatureStore.Pending> pending = store.takePending(orderId);
                if (pending.isEmpty()) {
                    log.warn("OrderConfirmed for order {} that was never seen as created: nothing to learn", orderId);
                    return Result.IGNORED;
                }
                List<UUID> products = java.util.Arrays.stream(pending.get().itemsJson().split(","))
                        .filter(s -> !s.isBlank()).map(UUID::fromString).toList();
                store.recordPurchase(pending.get().customerId(), products, Instant.now());
                return Result.LEARNED;
            }
            default -> {
                store.takePending(orderId);
                return Result.FORGOTTEN;
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static UUID uuid(JsonNode node, String field) {
        String v = text(node, field);
        if (v == null) {
            throw new InvalidEventException("missing " + field, null);
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException(field + " is not a UUID", e);
        }
    }
}
