package com.mercury.order.client;

import com.mercury.order.dto.InventoryOperationResult;
import com.mercury.order.exception.InventoryServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class InventoryClientTests {

    private static final String BASE_URL = "http://inventory.test";

    private final UUID productId = UUID.randomUUID();
    private MockRestServiceServer server;
    private InventoryClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new InventoryClient(builder, BASE_URL, DownstreamGuard.passThrough());
    }

    @Test
    void reserveSendsTheCallersKeyAndQuantityUnchanged() {
        server.expect(requestTo(BASE_URL + "/api/v1/inventory/" + productId + "/reserve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "order:o-1:product:p-1"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andRespond(withSuccess("{\"productId\":\"" + productId + "\",\"quantityReserved\":2}",
                        MediaType.APPLICATION_JSON));

        InventoryOperationResult result = client.reserve(productId, 2, "order:o-1:product:p-1");

        assertThat(result.replayed()).isFalse();
        server.verify();
    }

    @Test
    void releaseUsesTheReleaseEndpointWithItsOwnKey() {
        server.expect(requestTo(BASE_URL + "/api/v1/inventory/" + productId + "/release"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "order:o-1:product:p-1:release"))
                .andExpect(jsonPath("$.quantity").value(3))
                .andRespond(withSuccess("{\"productId\":\"" + productId + "\",\"quantityReleased\":3}",
                        MediaType.APPLICATION_JSON));

        client.release(productId, 3, "order:o-1:product:p-1:release");

        server.verify();
    }

    @Test
    void replayIsReportedWhenInventoryAnswersFromAStoredResult() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Idempotent-Replayed", "true");
        server.expect(requestTo(BASE_URL + "/api/v1/inventory/" + productId + "/reserve"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON).headers(headers));

        assertThat(client.reserve(productId, 1, "k").replayed()).isTrue();
    }

    @Test
    void inventoryErrorsKeepTheirStatusAndBody() {
        server.expect(requestTo(BASE_URL + "/api/v1/inventory/" + productId + "/reserve"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"INSUFFICIENT_STOCK\"}"));

        assertThatThrownBy(() -> client.reserve(productId, 99, "k"))
                .isInstanceOfSatisfying(InventoryServiceException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getResponseBody()).contains("INSUFFICIENT_STOCK");
                });
    }

    @Test
    void serverErrorBecomesInventoryServiceException() {
        server.expect(requestTo(BASE_URL + "/api/v1/inventory/" + productId + "/release"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.release(productId, 1, "k"))
                .isInstanceOfSatisfying(InventoryServiceException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(500));
    }

    @Test
    void unreachableInventoryBecomes503() {
        server.expect(requestTo(BASE_URL + "/api/v1/inventory/" + productId + "/reserve"))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });

        assertThatThrownBy(() -> client.reserve(productId, 1, "k"))
                .isInstanceOfSatisfying(InventoryServiceException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(503));
    }

    @Test
    void findOrderReservationReadsTheOrdersOutcome() {
        UUID orderId = UUID.randomUUID();
        server.expect(requestTo(BASE_URL + "/api/v1/inventory/reservations/orders/" + orderId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"orderId\":\"" + orderId + "\",\"status\":\"REJECTED\","
                        + "\"reason\":\"INSUFFICIENT_STOCK\",\"items\":[]}", MediaType.APPLICATION_JSON));

        var found = client.findOrderReservation(orderId);

        assertThat(found).isPresent();
        assertThat(found.get().reserved()).isFalse();
        assertThat(found.get().reason()).isEqualTo("INSUFFICIENT_STOCK");
        server.verify();
    }

    @Test
    void findOrderReservationIsEmptyWhenInventoryHasDecidedNothing() {
        UUID orderId = UUID.randomUUID();
        server.expect(requestTo(BASE_URL + "/api/v1/inventory/reservations/orders/" + orderId))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":404,\"error\":\"ORDER_RESERVATION_NOT_FOUND\"}"));

        assertThat(client.findOrderReservation(orderId)).isEmpty();
    }

    @Test
    void findOrderReservationFailsLoudlyWhenInventoryIsDown() {
        UUID orderId = UUID.randomUUID();
        server.expect(requestTo(BASE_URL + "/api/v1/inventory/reservations/orders/" + orderId))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.findOrderReservation(orderId))
                .isInstanceOf(InventoryServiceException.class);
    }
}
