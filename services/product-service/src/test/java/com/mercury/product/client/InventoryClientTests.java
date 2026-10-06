package com.mercury.product.client;

import com.mercury.product.dto.InventoryReservationResult;
import com.mercury.product.dto.InventoryResponse;
import com.mercury.product.exception.InventoryServiceException;
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
        client = new InventoryClient(builder, BASE_URL);
    }

    @Test
    void getInventoryCallsInventoryServiceAndIgnoresFieldsProductDoesNotNeed() {
        server.expect(requestTo(BASE_URL + "/api/v1/inventory/" + productId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"7ded1afb-8f45-40cf-8dd9-2cad56290701","productId":"%s",
                         "availableQuantity":8,"reservedQuantity":2,"version":1,
                         "createdAt":"2026-10-06T06:39:26.003284Z",
                         "updatedAt":"2026-10-06T06:39:26.081578Z"}
                        """.formatted(productId), MediaType.APPLICATION_JSON));

        InventoryResponse inventory = client.getInventory(productId);

        assertThat(inventory).isEqualTo(new InventoryResponse(productId, 8, 2, 1L));
        server.verify();
    }

    @Test
    void reserveForwardsIdempotencyKeyAndQuantityUnchanged() {
        server.expect(requestTo(BASE_URL + "/api/v1/inventory/" + productId + "/reserve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "order-123-reservation"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andRespond(withSuccess("""
                        {"productId":"%s","quantityReserved":2,"availableQuantity":8,
                         "reservedQuantity":2,"version":1,"updatedAt":"2026-10-06T07:46:34.090307Z"}
                        """.formatted(productId), MediaType.APPLICATION_JSON));

        InventoryReservationResult result =
                client.reserveInventory(productId, 2, "order-123-reservation");

        assertThat(result.replayed()).isFalse();
        assertThat(result.reservation().quantityReserved()).isEqualTo(2);
        assertThat(result.reservation().availableQuantity()).isEqualTo(8);
        assertThat(result.reservation().reservedQuantity()).isEqualTo(2);
        server.verify();
    }

    @Test
    void reserveReportsReplayWhenInventoryServiceSaysSo() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Idempotent-Replayed", "true");
        server.expect(requestTo(BASE_URL + "/api/v1/inventory/" + productId + "/reserve"))
                .andRespond(withSuccess("""
                        {"productId":"%s","quantityReserved":2,"availableQuantity":8,
                         "reservedQuantity":2,"version":1}
                        """.formatted(productId), MediaType.APPLICATION_JSON)
                        .headers(headers));

        InventoryReservationResult result = client.reserveInventory(productId, 2, "k");

        assertThat(result.replayed()).isTrue();
        assertThat(result.reservation().availableQuantity()).isEqualTo(8);
    }

    @Test
    void inventoryErrorsBecomeInventoryServiceExceptionKeepingStatusAndBody() {
        server.expect(requestTo(BASE_URL + "/api/v1/inventory/" + productId + "/reserve"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"INSUFFICIENT_STOCK\",\"status\":409}"));

        assertThatThrownBy(() -> client.reserveInventory(productId, 50, "k"))
                .isInstanceOfSatisfying(InventoryServiceException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getResponseBody()).contains("INSUFFICIENT_STOCK");
                });
    }

    @Test
    void getInventoryNotFoundBecomesInventoryServiceException() {
        server.expect(requestTo(BASE_URL + "/api/v1/inventory/" + productId))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"INVENTORY_NOT_FOUND\"}"));

        assertThatThrownBy(() -> client.getInventory(productId))
                .isInstanceOfSatisfying(InventoryServiceException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(404);
                    assertThat(e.getResponseBody()).contains("INVENTORY_NOT_FOUND");
                });
    }

    @Test
    void serverErrorBecomesInventoryServiceException() {
        server.expect(requestTo(BASE_URL + "/api/v1/inventory/" + productId + "/reserve"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.reserveInventory(productId, 1, "k"))
                .isInstanceOfSatisfying(InventoryServiceException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(500));
    }

    @Test
    void unreachableInventoryServiceBecomes503() {
        server.expect(requestTo(BASE_URL + "/api/v1/inventory/" + productId + "/reserve"))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });

        assertThatThrownBy(() -> client.reserveInventory(productId, 1, "k"))
                .isInstanceOfSatisfying(InventoryServiceException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(503));
    }
}
