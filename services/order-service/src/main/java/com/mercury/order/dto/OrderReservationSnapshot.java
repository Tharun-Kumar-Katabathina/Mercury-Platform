package com.mercury.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * What Inventory has decided for an order's asynchronous reservation: RESERVED or REJECTED, or FENCED when Order
 * Service fenced the order before anything was decided (no command for it can reserve stock any more).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderReservationSnapshot(UUID orderId, String status, String reason) {

    public boolean reserved() {
        return "RESERVED".equals(status);
    }

    public boolean rejected() {
        return "REJECTED".equals(status);
    }

    public boolean fenced() {
        return "FENCED".equals(status);
    }
}
