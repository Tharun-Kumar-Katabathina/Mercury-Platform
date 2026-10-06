package com.mercury.notification.service;

import com.mercury.notification.event.InvalidEventException;
import com.mercury.notification.event.OrderEventMessage;
import com.mercury.notification.model.NotificationType;
import com.mercury.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns an order event into a notification, exactly once per event id.
 *
 * Kafka delivers at least once, so the same event can arrive again (a publisher retry, a consumer that
 * crashed before committing its offset). The unique event id makes that harmless: the first delivery
 * writes the row, every later one is recognised and ignored.
 */
@Service
public class NotificationEventHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventHandler.class);

    public enum Outcome { RECORDED, DUPLICATE, IGNORED }

    private final JsonMapper jsonMapper;
    private final NotificationRepository notifications;
    private final NotificationRecorder recorder;
    private final NotificationMetrics metrics;

    public NotificationEventHandler(
            JsonMapper jsonMapper,
            NotificationRepository notifications,
            NotificationRecorder recorder,
            NotificationMetrics metrics) {
        this.jsonMapper = jsonMapper;
        this.notifications = notifications;
        this.recorder = recorder;
        this.metrics = metrics;
    }

    /** @throws InvalidEventException when the payload can never be processed (do not retry) */
    public Outcome handle(String payload) {

        OrderEventMessage event = parse(payload);

        NotificationType type = switch (event.eventType()) {
            case "OrderConfirmed" -> NotificationType.ORDER_CONFIRMED;
            case "OrderCancelled" -> NotificationType.ORDER_CANCELLED;
            default -> null;   // OrderCreated and any future type: valid, but nothing to notify
        };
        if (type == null) {
            metrics.ignored();
            metrics.consumed();
            log.info("event eventId={} type={} result=IGNORED", event.eventId(), event.eventType());
            return Outcome.IGNORED;
        }

        if (notifications.existsByEventId(event.eventId())) {
            return duplicate(event);
        }
        try {
            recorder.record(event.eventId(), event.orderId(), type, event.reason());
        } catch (DataIntegrityViolationException raced) {
            // a concurrent delivery of the same event won between the check and the insert
            return duplicate(event);
        }
        metrics.consumed();
        log.info("event eventId={} type={} orderId={} result=RECORDED", event.eventId(), event.eventType(), event.orderId());
        return Outcome.RECORDED;
    }

    private Outcome duplicate(OrderEventMessage event) {
        metrics.duplicate();
        log.info("event eventId={} type={} orderId={} result=DUPLICATE", event.eventId(), event.eventType(), event.orderId());
        return Outcome.DUPLICATE;
    }

    private OrderEventMessage parse(String payload) {
        OrderEventMessage event;
        try {
            event = jsonMapper.readValue(payload, OrderEventMessage.class);
        } catch (RuntimeException e) {
            throw new InvalidEventException("payload is not a readable order event", e);
        }
        if (event == null || event.eventId() == null || event.orderId() == null
                || event.eventType() == null || event.eventType().isBlank()) {
            throw new InvalidEventException("order event is missing eventId, orderId or eventType");
        }
        return event;
    }
}
