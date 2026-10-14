package com.mercury.inventory.service;

import com.mercury.inventory.dto.CreateInventoryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An idempotency key is stored in a VARCHAR(255) column, so a longer one can never be honoured. Every endpoint that takes
 * one must refuse it with a 400 in the platform's error body, not let the database refuse it as a 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyKeyLengthTests {

    private static final String TOO_LONG = "k".repeat(256);

    @Autowired private MockMvc mockMvc;
    @Autowired private InventoryService inventoryService;

    /** a key of exactly 255 characters, new in every test: the database is shared, and a key stays claimed once it is used */
    private static String longestKey() {
        return UUID.randomUUID() + "k".repeat(255 - 36);
    }

    private UUID product(int stock) {
        UUID id = UUID.randomUUID();
        inventoryService.createInventory(new CreateInventoryRequest(id, stock));
        return id;
    }

    /** the platform's error body: {timestamp, status, error, message} */
    private static void isAValidationError(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void aFenceKeyOf256CharactersIsRefusedWith400AndThePlatformBody() throws Exception {
        UUID p = product(10);

        isAValidationError(mockMvc.perform(post("/api/v1/inventory/{p}/reservations/{k}/fence", p, TOO_LONG)));
    }

    @Test
    void aFenceKeyOf255CharactersStillWorks() throws Exception {
        UUID p = product(10);
        String longest = longestKey();

        mockMvc.perform(post("/api/v1/inventory/{p}/reservations/{k}/fence", p, longest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FENCED"));
    }

    @Test
    void aLookupKeyOf256CharactersIsRefusedWith400AndAKeyOf255IsJustNotFound() throws Exception {
        UUID p = product(10);

        isAValidationError(mockMvc.perform(get("/api/v1/inventory/{p}/reservations/{k}", p, TOO_LONG)));
        mockMvc.perform(get("/api/v1/inventory/{p}/reservations/{k}", p, longestKey()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    void aReserveKeyOf256CharactersIsRefusedWith400AndHoldsNothing() throws Exception {
        UUID p = product(10);

        isAValidationError(mockMvc.perform(post("/api/v1/inventory/{p}/reserve", p).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", TOO_LONG).content("{\"quantity\":3}")));
        mockMvc.perform(get("/api/v1/inventory/{p}", p))
                .andExpect(jsonPath("$.availableQuantity").value(10))
                .andExpect(jsonPath("$.reservedQuantity").value(0));
    }

    @Test
    void aReserveKeyOf255CharactersStillWorks() throws Exception {
        UUID p = product(10);

        mockMvc.perform(post("/api/v1/inventory/{p}/reserve", p).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", longestKey()).content("{\"quantity\":3}"))
                .andExpect(status().isOk());
    }

    @Test
    void aReleaseKeyOf256CharactersIsRefusedWith400() throws Exception {
        UUID p = product(10);

        isAValidationError(mockMvc.perform(post("/api/v1/inventory/{p}/release", p).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", TOO_LONG).content("{\"quantity\":1}")));
    }
}
