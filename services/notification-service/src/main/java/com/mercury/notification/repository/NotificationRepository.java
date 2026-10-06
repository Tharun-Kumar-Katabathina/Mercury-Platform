package com.mercury.notification.repository;

import com.mercury.notification.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    boolean existsByEventId(UUID eventId);

    List<Notification> findByOrderIdOrderByCreatedAt(UUID orderId);

    long countByEventId(UUID eventId);
}
