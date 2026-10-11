package com.mercury.inventory.service;

import com.mercury.inventory.dto.CreateInventoryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/v1/inventory/{productId}/reservations/{key}/fence and what a reserve under a fenced key gets back. */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationFenceEndpointTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private InventoryService inventoryService;

    private UUID product(int stock) {
        UUID id = UUID.randomUUID();
        inventoryService.createInventory(new CreateInventoryRequest(id, stock));
        return id;
    }

    @Test
    void aFencedKeyRefusesTheReserveWith409AndHoldsNothing() throws Exception {
        UUID p = product(10);
        String key = "order-1:" + p;

        mockMvc.perform(post("/api/v1/inventory/{p}/reservations/{k}/fence", p, key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FENCED"))
                .andExpect(jsonPath("$.reservation").doesNotExist());
        mockMvc.perform(post("/api/v1/inventory/{p}/reserve", p).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key).content("{\"quantity\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RESERVATION_FENCED"));
        mockMvc.perform(get("/api/v1/inventory/{p}", p))
                .andExpect(jsonPath("$.availableQuantity").value(10))
                .andExpect(jsonPath("$.reservedQuantity").value(0));
        mockMvc.perform(get("/api/v1/inventory/{p}/reservations/{k}", p, key))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    void fencingAKeyThatWasReservedReturnsTheReservation() throws Exception {
        UUID p = product(10);
        String key = "order-2:" + p;
        mockMvc.perform(post("/api/v1/inventory/{p}/reserve", p).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key).content("{\"quantity\":3}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/inventory/{p}/reservations/{k}/fence", p, key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.reservation.quantityReserved").value(3));
    }

    @Test
    void aKeyOfAnotherProductIsAMismatch() throws Exception {
        UUID p = product(10);
        UUID other = product(10);
        String key = "order-3:" + p;
        mockMvc.perform(post("/api/v1/inventory/{p}/reserve", p).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content("{\"quantity\":1}")).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/inventory/{p}/reservations/{k}/fence", other, key))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_MISMATCH"));
    }
}
