package com.mercury.notification.service;

import com.mercury.notification.model.Notification;
import com.mercury.notification.model.NotificationType;
import com.mercury.notification.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** The single database write of the consumer, in its own short transaction. */
@Service
public class NotificationRecorder {

    private final NotificationRepository notifications;
    private final Clock clock;

    public NotificationRecorder(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    /** @throws org.springframework.dao.DataIntegrityViolationException if this event was already recorded */
    @Transactional
    public void record(UUID eventId, UUID orderId, NotificationType type, String detail) {
        notifications.saveAndFlush(Notification.record(eventId, orderId, type, detail, Instant.now(clock)));
    }
}
