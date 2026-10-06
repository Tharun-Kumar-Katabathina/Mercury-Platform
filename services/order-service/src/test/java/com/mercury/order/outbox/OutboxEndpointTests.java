package com.mercury.order.outbox;

import com.mercury.order.event.OrderConfirmedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "management.endpoints.web.exposure.include=health,metrics,sagas,outbox")
@AutoConfigureMockMvc
class OutboxEndpointTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private OutboxWriter writer;
    @Autowired private TransactionTemplate tx;

    @Test
    void pendingEventsAreVisibleWithoutTheirPayloads() throws Exception {
        UUID orderId = UUID.randomUUID();
        tx.executeWithoutResult(s -> writer.append(OrderConfirmedEvent.of(orderId, Instant.now())));

        String body = mockMvc.perform(get("/actuator/outbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").isNumber())
                .andExpect(jsonPath("$.published").isNumber())
                .andExpect(jsonPath("$.oldestPendingAgeSeconds").isNumber())
                .andExpect(jsonPath("$.pendingEvents[?(@.orderId=='" + orderId + "')].eventType").value("OrderConfirmed"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"payload\"");
    }

    @Test
    void eventMetricsAreExposed() throws Exception {
        for (String metric : new String[]{"events.published", "events.publish.failed", "outbox.pending",
                "outbox.oldest.age.seconds"}) {
            mockMvc.perform(get("/actuator/metrics/" + metric)).andExpect(status().isOk());
        }
    }
}
