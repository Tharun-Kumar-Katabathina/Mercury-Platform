package com.mercury.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** What Inventory has decided for an order's asynchronous reservation: RESERVED or REJECTED. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderReservationSnapshot(UUID orderId, String status, String reason) {

    public boolean reserved() {
        return "RESERVED".equals(status);
    }
}
