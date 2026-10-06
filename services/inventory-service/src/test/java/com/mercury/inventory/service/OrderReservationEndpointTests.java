package com.mercury.inventory.service;

import com.mercury.inventory.dto.CreateInventoryRequest;
import com.mercury.inventory.event.ReservationCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/v1/inventory/reservations/orders/{orderId}: what Order Service asks when a reply is overdue. */
@SpringBootTest
@AutoConfigureMockMvc
class OrderReservationEndpointTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private InventoryService inventoryService;
    @Autowired private OrderReservationService reservations;

    private UUID product(int stock) {
        UUID id = UUID.randomUUID();
        inventoryService.createInventory(new CreateInventoryRequest(id, stock));
        return id;
    }

    private ReservationCommand command(UUID orderId, UUID productId, int quantity) {
        return new ReservationCommand(UUID.randomUUID(), ReservationCommand.TYPE, Instant.now(), orderId,
                List.of(new ReservationCommand.Item(productId, quantity)));
    }

    @Test
    void reportsAReservedOrderWithItsItems() throws Exception {
        UUID p = product(10);
        UUID orderId = UUID.randomUUID();
        reservations.reserve(command(orderId, p, 3));

        mockMvc.perform(get("/api/v1/inventory/reservations/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.items[0].productId").value(p.toString()))
                .andExpect(jsonPath("$.items[0].quantity").value(3));
    }

    @Test
    void reportsARejectedOrderWithItsReason() throws Exception {
        UUID p = product(1);
        UUID orderId = UUID.randomUUID();
        reservations.reserve(command(orderId, p, 5));

        mockMvc.perform(get("/api/v1/inventory/reservations/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.rejectedProductId").value(p.toString()));
    }

    @Test
    void anOrderWithNoOutcomeIs404() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/reservations/orders/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORDER_RESERVATION_NOT_FOUND"));
    }

    @Test
    void theExistingPerItemEndpointsAreUnaffected() throws Exception {
        UUID p = product(5);

        mockMvc.perform(get("/api/v1/inventory/{id}", p))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(5));
    }
}
