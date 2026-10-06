package com.mercury.inventory.outbox;

import com.mercury.inventory.event.InventoryReservedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OutboxEndpointTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private OutboxWriter writer;
    @Autowired private TransactionTemplate tx;

    @Test
    void pendingRepliesAreVisibleWithoutTheirPayloads() throws Exception {
        UUID orderId = UUID.randomUUID();
        tx.executeWithoutResult(s -> writer.append(InventoryReservedEvent.of(orderId, Instant.now(), List.of())));

        String body = mockMvc.perform(get("/actuator/outbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").isNumber())
                .andExpect(jsonPath("$.pendingEvents[?(@.orderId=='" + orderId + "')].eventType").value("InventoryReserved"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"payload\"");
    }

    @Test
    void metricsAreExposed() throws Exception {
        for (String metric : new String[]{"events.published", "events.publish.failed", "outbox.pending",
                "inventory.commands.consumed", "inventory.commands.duplicate", "inventory.commands.dlq",
                "inventory.order.reserved", "inventory.order.rejected"}) {
            mockMvc.perform(get("/actuator/metrics/" + metric)).andExpect(status().isOk());
        }
    }
}
