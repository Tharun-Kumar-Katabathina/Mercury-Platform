package com.mercury.product.controller;

import com.mercury.product.client.InventoryClient;
import com.mercury.product.dto.CreateProductRequest;
import com.mercury.product.dto.InventoryReservationResponse;
import com.mercury.product.dto.InventoryReservationResult;
import com.mercury.product.dto.ProductResponse;
import com.mercury.product.exception.InventoryServiceException;
import com.mercury.product.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-level tests of POST /api/v1/products/{id}/reserve. Inventory Service is replaced by a
 * mock InventoryClient (its HTTP behaviour is covered by InventoryClientTests and by the
 * manual two-service run), so these tests pin down what Product Service itself decides:
 * validation order, product-existence check, forwarding, and error mapping.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductReservationApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductService productService;

    @MockitoBean
    private InventoryClient inventoryClient;

    private UUID productId;

    @BeforeEach
    void createProduct() {
        ProductResponse product = productService.createProduct(new CreateProductRequest(
                "MacBook Pro 16", "SKU-" + UUID.randomUUID(), new BigDecimal("2499.99"), 10));
        productId = product.id();
    }

    private MockHttpServletRequestBuilder reserve(UUID id, String key, String json) {
        MockHttpServletRequestBuilder request = post("/api/v1/products/{id}/reserve", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    private InventoryReservationResult reserved(int quantity, int available, boolean replayed) {
        return new InventoryReservationResult(
                new InventoryReservationResponse(
                        productId, quantity, available, quantity, 1L),
                replayed);
    }

    private static InventoryServiceException inventoryError(
            HttpStatus status, String code, String message) {
        return new InventoryServiceException(status,
                "{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    @Test
    void successfulReservationReturnsInventoryResult() throws Exception {
        when(inventoryClient.reserveInventory(productId, 2, "reserve-001"))
                .thenReturn(reserved(2, 8, false));

        mockMvc.perform(reserve(productId, "reserve-001", "{\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.productId").value(productId.toString()))
                .andExpect(jsonPath("$.quantityReserved").value(2))
                .andExpect(jsonPath("$.availableQuantity").value(8))
                .andExpect(jsonPath("$.reservedQuantity").value(2));

        verify(inventoryClient).reserveInventory(productId, 2, "reserve-001");
    }

    @Test
    void unknownProductIs404AndInventoryIsNeverCalled() throws Exception {
        mockMvc.perform(reserve(UUID.randomUUID(), "reserve-001", "{\"quantity\":2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"));

        verifyNoInteractions(inventoryClient);
    }

    @Test
    void productWithoutInventoryPassesThroughInventory404() throws Exception {
        when(inventoryClient.reserveInventory(any(), anyInt(), anyString()))
                .thenThrow(inventoryError(HttpStatus.NOT_FOUND,
                        "INVENTORY_NOT_FOUND", "Inventory not found for product"));

        mockMvc.perform(reserve(productId, "reserve-001", "{\"quantity\":2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("INVENTORY_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Inventory not found for product"));
    }

    @Test
    void insufficientStockPassesThroughAs409() throws Exception {
        when(inventoryClient.reserveInventory(any(), anyInt(), anyString()))
                .thenThrow(inventoryError(HttpStatus.CONFLICT,
                        "INSUFFICIENT_STOCK", "requested 5, available 2"));

        mockMvc.perform(reserve(productId, "reserve-001", "{\"quantity\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.message").value("requested 5, available 2"));
    }

    @Test
    void missingIdempotencyKeyIs400WithMercuryErrorCodeAndNoCalls() throws Exception {
        mockMvc.perform(reserve(productId, null, "{\"quantity\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("MISSING_IDEMPOTENCY_KEY"))
                .andExpect(jsonPath("$.message").value("Idempotency-Key header is required"));

        verifyNoInteractions(inventoryClient);
    }

    @Test
    void blankIdempotencyKeyIsTreatedAsMissing() throws Exception {
        mockMvc.perform(reserve(productId, "   ", "{\"quantity\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_IDEMPOTENCY_KEY"));

        verifyNoInteractions(inventoryClient);
    }

    @Test
    void missingKeyIsReportedBeforeProductLookup() throws Exception {
        mockMvc.perform(reserve(UUID.randomUUID(), null, "{\"quantity\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    void invalidQuantityIs400AndInventoryIsNeverCalled() throws Exception {
        mockMvc.perform(reserve(productId, "reserve-001", "{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(reserve(productId, "reserve-001", "{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(inventoryClient);
    }

    @Test
    void replayIsReturnedWithReplayHeader() throws Exception {
        when(inventoryClient.reserveInventory(productId, 2, "reserve-001"))
                .thenReturn(reserved(2, 8, false))
                .thenReturn(reserved(2, 8, true));

        mockMvc.perform(reserve(productId, "reserve-001", "{\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));

        mockMvc.perform(reserve(productId, "reserve-001", "{\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.availableQuantity").value(8))
                .andExpect(jsonPath("$.reservedQuantity").value(2));

        // the SAME key both times: Product never invents or changes the key
        verify(inventoryClient, times(2)).reserveInventory(productId, 2, "reserve-001");
    }

    @Test
    void idempotencyKeyMismatchPassesThroughAs422() throws Exception {
        when(inventoryClient.reserveInventory(productId, 5, "reserve-001"))
                .thenThrow(inventoryError(HttpStatus.UNPROCESSABLE_CONTENT,
                        "IDEMPOTENCY_KEY_MISMATCH", "key already used with a different request"));

        mockMvc.perform(reserve(productId, "reserve-001", "{\"quantity\":5}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_MISMATCH"));
    }

    @Test
    void inventoryServerErrorIs502() throws Exception {
        when(inventoryClient.reserveInventory(any(), anyInt(), anyString()))
                .thenThrow(new InventoryServiceException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"boom\"}"));

        mockMvc.perform(reserve(productId, "reserve-001", "{\"quantity\":2}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("INVENTORY_SERVICE_ERROR"));
    }

    @Test
    void unreachableInventoryIs503() throws Exception {
        when(inventoryClient.reserveInventory(any(), anyInt(), anyString()))
                .thenThrow(new InventoryServiceException(HttpStatus.SERVICE_UNAVAILABLE, null));

        mockMvc.perform(reserve(productId, "reserve-001", "{\"quantity\":2}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("INVENTORY_SERVICE_UNAVAILABLE"));
    }

    @Test
    void nonJsonInventoryClientErrorStillGetsAnErrorCode() throws Exception {
        when(inventoryClient.reserveInventory(any(), anyInt(), anyString()))
                .thenThrow(new InventoryServiceException(HttpStatus.BAD_REQUEST, "<html>nope</html>"));

        mockMvc.perform(reserve(productId, "reserve-001", "{\"quantity\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVENTORY_REQUEST_REJECTED"));
    }
}
