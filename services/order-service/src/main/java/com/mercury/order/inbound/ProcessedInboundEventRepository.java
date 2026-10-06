package com.mercury.order.inbound;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedInboundEventRepository extends JpaRepository<ProcessedInboundEvent, UUID> {
}
