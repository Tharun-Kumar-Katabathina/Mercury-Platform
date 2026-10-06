package com.mercury.order.controller;

import com.mercury.order.client.InventoryClient;
import com.mercury.order.client.ProductClient;
import com.mercury.order.dto.ProductDetails;
import com.mercury.order.inbound.InventoryEventHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract of ASYNC orders: 202 + Location, replays, and the final 200. */
@SpringBootTest(properties = {"order.reservation.mode=ASYNC", "order.reservation.async-deadline=60s"})
@AutoConfigureMockMvc
class AsyncOrderApiTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private InventoryEventHandler handler;
    @Autowired private JsonMapper json;

    @MockitoBean private ProductClient productClient;
    @MockitoBean private InventoryClient inventoryClient;

    private UUID productId;

    @BeforeEach
    void aProduct() {
        productId = UUID.randomUUID();
        when(productClient.getProduct(productId)).thenReturn(
                new ProductDetails(productId, "MacBook Pro 16", "MBP-16-M4", new BigDecimal("2499.00")));
    }

    private MvcResult place(String key, int quantity, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}]}"))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private UUID orderIdOf(MvcResult result) throws Exception {
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).path("id").asString());
    }

    private String reserved(UUID orderId) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"InventoryReserved\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"orderId\":\"" + orderId + "\","
                + "\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":1}]}";
    }

    @Test
    void creatingAnOrderAnswers202WithLocationAndAPendingOrder() throws Exception {
        MvcResult result = place("k-" + UUID.randomUUID(), 1, 202);

        UUID orderId = orderIdOf(result);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/orders/" + orderId);
        assertThat(result.getResponse().getHeader("Idempotent-Replayed")).isNull();
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("status").asString())
                .isEqualTo("PENDING");
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void theLocationShowsPendingThenConfirmed() throws Exception {
        UUID orderId = orderIdOf(place("k-" + UUID.randomUUID(), 1, 202));

        mockMvc.perform(get("/api/v1/orders/" + orderId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));

        handler.handle(reserved(orderId));

        mockMvc.perform(get("/api/v1/orders/" + orderId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void aRetryWhileWaitingAnswers202AgainWithTheSameOrderAndTheReplayHeader() throws Exception {
        String key = "k-" + UUID.randomUUID();
        UUID orderId = orderIdOf(place(key, 1, 202));

        MvcResult retry = place(key, 1, 202);

        assertThat(orderIdOf(retry)).isEqualTo(orderId);
        assertThat(retry.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getResponse().getHeader("Location")).isEqualTo("/api/v1/orders/" + orderId);
    }

    @Test
    void aRetryAfterTheOrderIsFinalAnswers200WithTheReplayHeader() throws Exception {
        String key = "k-" + UUID.randomUUID();
        UUID orderId = orderIdOf(place(key, 1, 202));
        handler.handle(reserved(orderId));

        MvcResult retry = place(key, 1, 200);

        assertThat(orderIdOf(retry)).isEqualTo(orderId);
        assertThat(retry.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(json.readTree(retry.getResponse().getContentAsString()).path("status").asString())
                .isEqualTo("CONFIRMED");
    }

    @Test
    void theSameKeyWithAnotherPayloadIsStill422() throws Exception {
        String key = "k-" + UUID.randomUUID();
        place(key, 1, 202);

        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":2}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_MISMATCH"));
    }

    @Test
    void aMissingKeyIsStill400() throws Exception {
        mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Location"));
    }
}
