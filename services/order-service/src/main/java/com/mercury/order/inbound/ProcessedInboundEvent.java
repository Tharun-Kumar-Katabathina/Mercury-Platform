package com.mercury.order.inbound;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/** Proof that an Inventory event was handled; written in the same transaction as its effect. */
@Entity
@Table(name = "order_processed_events")
public class ProcessedInboundEvent implements Persistable<UUID> {

    @Id
    private UUID eventId;

    @Column(nullable = false, length = 50)
    private String consumer;

    @Column(nullable = false)
    private Instant processedAt;

    @Transient
    private boolean isNew = true;

    protected ProcessedInboundEvent() {
    }

    public ProcessedInboundEvent(UUID eventId, String consumer, Instant processedAt) {
        this.eventId = eventId;
        this.consumer = consumer;
        this.processedAt = processedAt;
    }

    @Override
    public UUID getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}
