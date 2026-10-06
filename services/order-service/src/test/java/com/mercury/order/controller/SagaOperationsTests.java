package com.mercury.order.controller;

import com.mercury.order.service.OrderDraft;
import com.mercury.order.service.OrderTransactions;
import com.mercury.order.client.InventoryClient;
import com.mercury.order.client.ProductClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stuck orders must be observable: the sagas overview and the recovery metrics. */
@SpringBootTest(properties = "management.endpoints.web.exposure.include=health,metrics,sagas")
@AutoConfigureMockMvc
class SagaOperationsTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderTransactions transactions;

    @MockitoBean
    private ProductClient productClient;

    @MockitoBean
    private InventoryClient inventoryClient;

    @Test
    void anUnfinishedSagaShowsUpWithItsStateAndAttempts() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID orderId = transactions.createPending("key-" + UUID.randomUUID(), "hash",
                new OrderDraft(List.of(new OrderDraft.Item(
                        productId, "Item", "SKU", new BigDecimal("1.00"), 1)), new BigDecimal("1.00")));
        transactions.scheduleRetry(orderId, "inventory unavailable");

        mockMvc.perform(get("/actuator/sagas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countsByState.COMPENSATING").isNumber())
                .andExpect(jsonPath("$.oldestUnfinishedAgeSeconds").isNumber())
                .andExpect(jsonPath("$.unfinished[?(@.orderId=='" + orderId + "')].state").value("COMPENSATING"))
                .andExpect(jsonPath("$.unfinished[?(@.orderId=='" + orderId + "')].attempts").value(1))
                .andExpect(jsonPath("$.unfinished[?(@.orderId=='" + orderId + "')].lastError")
                        .value("inventory unavailable"));
    }

    @Test
    void theEndpointNeverExposesIdempotencyKeys() throws Exception {
        String secretKey = "customer-" + UUID.randomUUID();
        transactions.createPending(secretKey, "hash", new OrderDraft(List.of(new OrderDraft.Item(
                UUID.randomUUID(), "Item", "SKU", new BigDecimal("1.00"), 1)), new BigDecimal("1.00")));

        String body = mockMvc.perform(get("/actuator/sagas"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain(secretKey);
    }

    @Test
    void recoveryMetricsAreExposed() throws Exception {
        for (String metric : List.of("orders.created", "orders.confirmed", "orders.cancelled",
                "orders.recovery.pending", "orders.recovery.success", "orders.recovery.failed",
                "saga.compensation.success", "saga.compensation.failure", "orders.recovery.exhausted")) {
            mockMvc.perform(get("/actuator/metrics/" + metric)).andExpect(status().isOk());
        }
    }

    @Test
    void circuitBreakerStateIsExposedPerService() throws Exception {
        mockMvc.perform(get("/actuator/metrics/downstream.circuit.state").param("tag", "service:inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(0.0));
    }
}
