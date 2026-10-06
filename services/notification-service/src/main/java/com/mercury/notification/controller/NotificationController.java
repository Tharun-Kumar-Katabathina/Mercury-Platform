package com.mercury.notification.controller;

import com.mercury.notification.model.Notification;
import com.mercury.notification.repository.NotificationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    public record NotificationResponse(
            UUID id, UUID eventId, UUID orderId, String type, String status, String detail, Instant createdAt) {

        static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.getId(), n.getEventId(), n.getOrderId(),
                    n.getType().name(), n.getStatus(), n.getDetail(), n.getCreatedAt());
        }
    }

    private final NotificationRepository notifications;

    public NotificationController(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /** the notifications recorded for one order, oldest first */
    @GetMapping("/orders/{orderId}")
    public List<NotificationResponse> forOrder(@PathVariable UUID orderId) {
        return notifications.findByOrderIdOrderByCreatedAt(orderId).stream()
                .map(NotificationResponse::from).toList();
    }
}
