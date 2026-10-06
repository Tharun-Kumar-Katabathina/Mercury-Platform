package com.mercury.order.inbound;

import com.mercury.order.service.InboundResult;
import com.mercury.order.service.OrderTransactions;
import com.mercury.order.service.SagaMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Turns one InventoryReserved / InventoryRejected message into its effect on the order. Every effect and
 * its "already handled" marker commit in one transaction (see OrderTransactions), so the message can be
 * delivered any number of times with the effect happening once.
 */
@Service
public class InventoryEventHandler {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventHandler.class);

    static final String RESERVED = "InventoryReserved";
    static final String REJECTED = "InventoryRejected";

    private final JsonMapper jsonMapper;
    private final OrderTransactions transactions;
    private final InboundMetrics metrics;
    private final SagaMetrics sagaMetrics;

    public InventoryEventHandler(
            JsonMapper jsonMapper, OrderTransactions transactions,
            InboundMetrics metrics, SagaMetrics sagaMetrics) {
        this.jsonMapper = jsonMapper;
        this.transactions = transactions;
        this.metrics = metrics;
        this.sagaMetrics = sagaMetrics;
    }

    /** @throws InvalidInventoryEventException when the payload can never be processed (do not retry) */
    public InboundResult handle(String payload) {

        JsonNode event;
        try {
            event = jsonMapper.readTree(payload);
        } catch (RuntimeException e) {
            throw new InvalidInventoryEventException("payload is not readable JSON", e);
        }

        String type = text(event, "eventType");
        if (!RESERVED.equals(type) && !REJECTED.equals(type)) {
            metrics.ignored();
            log.debug("ignoring inventory event of type {}", type);
            return InboundResult.IGNORED;   // some other event on the topic: not ours to act on
        }

        UUID eventId = uuid(event, "eventId");
        UUID orderId = uuid(event, "orderId");

        InboundResult result = RESERVED.equals(type)
                ? transactions.applyInventoryReserved(eventId, orderId)
                : transactions.applyInventoryRejected(eventId, orderId, text(event, "reason"));

        metrics.consumed();
        switch (result) {
            case CONFIRMED -> sagaMetrics.orderConfirmed();
            case CANCELLED -> sagaMetrics.orderCancelled();
            case RELEASE_NEEDED -> metrics.lateReservation();
            case DUPLICATE -> metrics.duplicate();
            case IGNORED -> metrics.ignored();
        }
        log.info("inventory event eventId={} type={} orderId={} result={}", eventId, type, orderId, result);
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new InvalidInventoryEventException("missing " + field);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidInventoryEventException(field + " is not a UUID", e);
        }
    }
}
