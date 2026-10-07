package com.mercury.product.client;

import com.mercury.product.dto.InventoryReservationResponse;
import com.mercury.product.dto.InventoryReservationResult;
import com.mercury.product.dto.InventoryResponse;
import com.mercury.product.dto.ReserveInventoryRequest;
import com.mercury.product.exception.InventoryServiceException;
import com.mercury.product.security.ServiceTokenProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * HTTP client for Inventory Service. Product Service never touches the inventory
 * database; everything goes through this API.
 *
 * Every failure leaves this class as an {@link InventoryServiceException}: a non-2xx answer
 * keeps its status and body, and an unreachable service becomes a 503. No retries or
 * timeouts yet.
 */
@Component
public class InventoryClient {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

    private final RestClient restClient;

    /** Without service authentication (unit tests). */
    public InventoryClient(RestClient.Builder builder, String inventoryServiceUrl) {
        this(builder, inventoryServiceUrl, null);
    }

    @Autowired
    public InventoryClient(
            RestClient.Builder builder,
            @Value("${inventory.service.url}") String inventoryServiceUrl,
            ObjectProvider<ServiceTokenProvider> tokens) {

        this.restClient = builder
                .baseUrl(inventoryServiceUrl)
                .requestInterceptor((request, body, execution) -> {
                    // service-to-service authentication: a short-lived SERVICE token from the user-service
                    ServiceTokenProvider provider = tokens == null ? null : tokens.getIfAvailable();
                    if (provider != null) {
                        try {
                            request.getHeaders().setBearerAuth(provider.token());
                        } catch (ServiceTokenProvider.UnavailableException e) {
                            throw new java.io.IOException(e.getMessage(), e);   // surfaces as "service unreachable"
                        }
                    }
                    return execution.execute(request, body);
                })
                .defaultStatusHandler(
                        status -> status.isError(),
                        (request, response) -> {
                            throw new InventoryServiceException(
                                    response.getStatusCode(), readBody(response.getBody()));
                        })
                .build();
    }

    public InventoryResponse getInventory(UUID productId) {

        try {
            return restClient.get()
                    .uri("/api/v1/inventory/{productId}", productId)
                    .retrieve()
                    .body(InventoryResponse.class);
        } catch (ResourceAccessException e) {
            throw unreachable(e);
        }
    }

    /**
     * @param idempotencyKey the caller's key, forwarded unchanged so a retry of the same
     *                       logical operation is recognised by Inventory Service
     */
    public InventoryReservationResult reserveInventory(
            UUID productId, int quantity, String idempotencyKey) {

        ResponseEntity<InventoryReservationResponse> response;
        try {
            response = restClient.post()
                    .uri("/api/v1/inventory/{productId}/reserve", productId)
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .body(new ReserveInventoryRequest(quantity))
                    .retrieve()
                    .toEntity(InventoryReservationResponse.class);
        } catch (ResourceAccessException e) {
            throw unreachable(e);
        }

        boolean replayed = "true".equalsIgnoreCase(
                response.getHeaders().getFirst(IDEMPOTENT_REPLAYED_HEADER));

        return new InventoryReservationResult(response.getBody(), replayed);
    }

    private static InventoryServiceException unreachable(ResourceAccessException e) {
        return new InventoryServiceException(HttpStatus.SERVICE_UNAVAILABLE, null, e);
    }

    private static String readBody(java.io.InputStream body) {
        try {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
