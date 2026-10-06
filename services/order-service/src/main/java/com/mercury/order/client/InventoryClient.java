package com.mercury.order.client;

import com.mercury.order.dto.InventoryOperationResult;
import com.mercury.order.dto.ReserveInventoryRequest;
import com.mercury.order.exception.InventoryServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * HTTP client for Inventory Service. The idempotency key is always supplied by the caller and
 * forwarded unchanged; this class never invents one. Every failure leaves it as an
 * InventoryServiceException.
 */
@Component
public class InventoryClient {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

    private final RestClient restClient;

    public InventoryClient(
            RestClient.Builder builder,
            @Value("${inventory.service.url}") String inventoryServiceUrl) {

        this.restClient = builder
                .baseUrl(inventoryServiceUrl)
                .defaultStatusHandler(
                        status -> status.isError(),
                        (request, response) -> {
                            throw new InventoryServiceException(
                                    response.getStatusCode(), readBody(response.getBody()));
                        })
                .build();
    }

    public InventoryOperationResult reserve(UUID productId, int quantity, String idempotencyKey) {
        return post("/api/v1/inventory/{productId}/reserve", productId, quantity, idempotencyKey);
    }

    public InventoryOperationResult release(UUID productId, int quantity, String idempotencyKey) {
        return post("/api/v1/inventory/{productId}/release", productId, quantity, idempotencyKey);
    }

    private InventoryOperationResult post(
            String path, UUID productId, int quantity, String idempotencyKey) {

        try {
            ResponseEntity<Void> response = restClient.post()
                    .uri(path, productId)
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .body(new ReserveInventoryRequest(quantity))
                    .retrieve()
                    .toBodilessEntity();

            return new InventoryOperationResult("true".equalsIgnoreCase(
                    response.getHeaders().getFirst(IDEMPOTENT_REPLAYED_HEADER)));
        } catch (ResourceAccessException e) {
            throw new InventoryServiceException(HttpStatus.SERVICE_UNAVAILABLE, null, e);
        }
    }

    private static String readBody(InputStream body) {
        try {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
