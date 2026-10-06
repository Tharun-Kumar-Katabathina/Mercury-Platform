package com.mercury.order.client;

import com.mercury.order.dto.InventoryOperationResult;
import com.mercury.order.dto.OrderReservationSnapshot;
import com.mercury.order.dto.ReservationSnapshot;
import com.mercury.order.dto.ReserveInventoryRequest;
import com.mercury.order.exception.InventoryServiceException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
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
    private final DownstreamGuard guard;

    public InventoryClient(
            RestClient.Builder builder,
            @Value("${inventory.service.url}") String inventoryServiceUrl,
            @Qualifier("inventoryGuard") DownstreamGuard guard) {

        this.guard = guard;
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

    /**
     * Was a reservation made for this product under this key? Read-only. This is how a reserve call
     * whose response was lost gets resolved: empty means Inventory holds nothing under the key.
     */
    public Optional<ReservationSnapshot> findReservation(UUID productId, String idempotencyKey) {
        return guard.execute(() -> {
            try {
                return Optional.ofNullable(restClient.get()
                        .uri("/api/v1/inventory/{productId}/reservations/{key}", productId, idempotencyKey)
                        .retrieve()
                        .body(ReservationSnapshot.class));
            } catch (InventoryServiceException e) {
                if (e.getStatus().value() == 404 && e.getResponseBody() != null
                        && e.getResponseBody().contains("RESERVATION_NOT_FOUND")) {
                    return Optional.<ReservationSnapshot>empty();
                }
                throw e;
            } catch (ResourceAccessException e) {
                throw new InventoryServiceException(HttpStatus.SERVICE_UNAVAILABLE, null, e);
            }
        }, InventoryClient::neverSent);
    }

    /**
     * What did Inventory decide for this ASYNC order? Read-only; empty means it has decided nothing (yet),
     * for example because the command is still queued or was dead-lettered.
     */
    public Optional<OrderReservationSnapshot> findOrderReservation(UUID orderId) {
        return guard.execute(() -> {
            try {
                return Optional.ofNullable(restClient.get()
                        .uri("/api/v1/inventory/reservations/orders/{orderId}", orderId)
                        .retrieve()
                        .body(OrderReservationSnapshot.class));
            } catch (InventoryServiceException e) {
                if (e.getStatus().value() == 404 && e.getResponseBody() != null
                        && e.getResponseBody().contains("ORDER_RESERVATION_NOT_FOUND")) {
                    return Optional.<OrderReservationSnapshot>empty();
                }
                throw e;
            } catch (ResourceAccessException e) {
                throw new InventoryServiceException(HttpStatus.SERVICE_UNAVAILABLE, null, e);
            }
        }, InventoryClient::neverSent);
    }

    private InventoryOperationResult post(
            String path, UUID productId, int quantity, String idempotencyKey) {

        return guard.execute(() -> {
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
        }, InventoryClient::neverSent);
    }

    private static InventoryServiceException neverSent(Throwable rejection) {
        return new InventoryServiceException(HttpStatus.SERVICE_UNAVAILABLE, null, rejection, true);
    }

    private static String readBody(InputStream body) {
        try {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
