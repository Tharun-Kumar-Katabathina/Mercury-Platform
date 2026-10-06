package com.mercury.order.controller;

import com.mercury.order.client.InventoryClient;
import com.mercury.order.client.ProductClient;
import com.mercury.order.dto.InventoryOperationResult;
import com.mercury.order.dto.ProductDetails;
import com.mercury.order.exception.InventoryServiceException;
import com.mercury.order.exception.ProductServiceException;
import com.mercury.order.service.OrderTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/v1/orders and GET /api/v1/orders/{id}: status codes and the Mercury error shape. */
@SpringBootTest(properties = "order.idempotency.wait-timeout=300ms")
@AutoConfigureMockMvc
class OrderApiTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductClient productClient;

    @MockitoBean
    private InventoryClient inventoryClient;

    @MockitoSpyBean
    private OrderTransactions transactions;

    private UUID productId;

    @BeforeEach
    void aProductAndAcceptingInventory() {
        productId = UUID.randomUUID();
        when(productClient.getProduct(productId)).thenReturn(
                new ProductDetails(productId, "MacBook Pro 16", "MBP-16-M4", new BigDecimal("2499.00")));
        when(inventoryClient.reserve(any(), anyInt(), anyString()))
                .thenReturn(new InventoryOperationResult(false));
        when(inventoryClient.release(any(), anyInt(), anyString()))
                .thenReturn(new InventoryOperationResult(false));
    }

    private MockHttpServletRequestBuilder createOrder(String key, String json) {
        MockHttpServletRequestBuilder request = post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    private String body(UUID id, int quantity) {
        return "{\"items\":[{\"productId\":\"" + id + "\",\"quantity\":" + quantity + "}]}";
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    // ---- success -------------------------------------------------------------------------

    @Test
    void createsAnOrderAndReturnsItsSnapshot() throws Exception {
        mockMvc.perform(createOrder(key(), body(productId, 2)))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalAmount").value(4998.00))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$.items[0].productName").value("MacBook Pro 16"))
                .andExpect(jsonPath("$.items[0].sku").value("MBP-16-M4"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(2499.00))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].subtotal").value(4998.00))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void theCreatedOrderCanBeRetrieved() throws Exception {
        String response = mockMvc.perform(createOrder(key(), body(productId, 1)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/v1/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[0].productName").value("MacBook Pro 16"));
    }

    @Test
    void retryWithTheSameKeyReturnsTheSameOrderWithTheReplayHeader() throws Exception {
        String key = key();
        String first = mockMvc.perform(createOrder(key, body(productId, 2)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(createOrder(key, body(productId, 2)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);
        verify(inventoryClient, times(1)).reserve(any(), anyInt(), anyString());
    }

    @Test
    void sameKeyWithADifferentPayloadIs422() throws Exception {
        String key = key();
        mockMvc.perform(createOrder(key, body(productId, 2))).andExpect(status().isCreated());

        mockMvc.perform(createOrder(key, body(productId, 5)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_MISMATCH"));
    }

    // ---- 400: the request itself is wrong -------------------------------------------------

    @Test
    void missingOrBlankIdempotencyKeyIs400() throws Exception {
        mockMvc.perform(createOrder(null, body(productId, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_IDEMPOTENCY_KEY"));
        mockMvc.perform(createOrder("  ", body(productId, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_IDEMPOTENCY_KEY"));

        verifyNoInteractions(productClient, inventoryClient);
    }

    @Test
    void invalidOrdersAreInvalidOrder400AndNothingElseIsCalled() throws Exception {
        String[] invalid = {
                "{\"items\":[]}",                                                      // empty
                "{}",                                                                  // no items
                "{\"items\":[{\"productId\":null,\"quantity\":1}]}",                   // no productId
                "{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":0}]}",  // quantity 0
                "{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":-2}]}", // negative
                "{\"items\":[{\"productId\":\"" + productId + "\"}]}",                 // no quantity
                "{\"items\":[null]}",                                                  // null item
                "{\"items\":[{\"productId\":\"not-a-uuid\",\"quantity\":1}]}",         // bad uuid
                "{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":1},"
                        + "{\"productId\":\"" + productId + "\",\"quantity\":2}]}",    // duplicate
                "{not json"                                                            // malformed
        };

        for (String json : invalid) {
            mockMvc.perform(createOrder(key(), json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("INVALID_ORDER"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        verifyNoInteractions(productClient, inventoryClient);
    }

    // ---- not found ------------------------------------------------------------------------

    @Test
    void unknownOrderIs404() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORDER_NOT_FOUND"));
    }

    @Test
    void malformedOrderIdIsInvalidOrder400() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ORDER"));
    }

    // ---- Product failures -------------------------------------------------------------------

    @Test
    void unknownProductIs404ProductNotFoundAndNothingIsReserved() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(productClient.getProduct(unknown)).thenThrow(new ProductServiceException(
                HttpStatus.NOT_FOUND,
                "{\"error\":\"PRODUCT_NOT_FOUND\",\"message\":\"Product not found: " + unknown + "\"}"));

        mockMvc.perform(createOrder(key(), body(unknown, 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Product not found: " + unknown));

        verifyNoInteractions(inventoryClient);
    }

    @Test
    void productServiceUnavailableIs503AndFailingIs502() throws Exception {
        UUID down = UUID.randomUUID();
        UUID broken = UUID.randomUUID();
        when(productClient.getProduct(down))
                .thenThrow(new ProductServiceException(HttpStatus.SERVICE_UNAVAILABLE, null));
        when(productClient.getProduct(broken))
                .thenThrow(new ProductServiceException(HttpStatus.INTERNAL_SERVER_ERROR, "boom"));

        mockMvc.perform(createOrder(key(), body(down, 1)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("PRODUCT_SERVICE_UNAVAILABLE"));
        mockMvc.perform(createOrder(key(), body(broken, 1)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("PRODUCT_SERVICE_ERROR"));
    }

    // ---- Inventory failures -----------------------------------------------------------------

    private void inventoryAnswers(HttpStatus status, String body) {
        doThrow(new InventoryServiceException(status, body))
                .when(inventoryClient).reserve(any(), anyInt(), anyString());
    }

    @Test
    void inventoryNotFoundPassesThroughAs404() throws Exception {
        inventoryAnswers(HttpStatus.NOT_FOUND,
                "{\"error\":\"INVENTORY_NOT_FOUND\",\"message\":\"no stock record\"}");

        mockMvc.perform(createOrder(key(), body(productId, 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("INVENTORY_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("no stock record"));
    }

    @Test
    void insufficientStockPassesThroughAs409() throws Exception {
        inventoryAnswers(HttpStatus.CONFLICT,
                "{\"error\":\"INSUFFICIENT_STOCK\",\"message\":\"requested 5, available 2\"}");

        mockMvc.perform(createOrder(key(), body(productId, 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.message").value("requested 5, available 2"));
    }

    @Test
    void inventoryKeyMismatchPassesThroughAs422() throws Exception {
        inventoryAnswers(HttpStatus.UNPROCESSABLE_CONTENT,
                "{\"error\":\"IDEMPOTENCY_KEY_MISMATCH\",\"message\":\"x\"}");

        mockMvc.perform(createOrder(key(), body(productId, 1)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_MISMATCH"));
    }

    @Test
    void inventoryUnavailableIs503AndFailingIs502() throws Exception {
        inventoryAnswers(HttpStatus.SERVICE_UNAVAILABLE, null);
        mockMvc.perform(createOrder(key(), body(productId, 1)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("INVENTORY_SERVICE_UNAVAILABLE"));

        inventoryAnswers(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"boom\"}");
        mockMvc.perform(createOrder(key(), body(productId, 1)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("INVENTORY_SERVICE_ERROR"));
    }

    @Test
    void inventory4xxWithoutAnErrorCodeStillGetsOne() throws Exception {
        inventoryAnswers(HttpStatus.BAD_REQUEST, "<html>nope</html>");

        mockMvc.perform(createOrder(key(), body(productId, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVENTORY_REQUEST_REJECTED"));
    }

    // ---- the Order side failing --------------------------------------------------------------

    @Test
    void orderThatCannotBeConfirmedIs500OrderProcessingFailedAndStockIsReleased() throws Exception {
        String key = key();
        doThrow(new IllegalStateException("db error")).when(transactions).confirm(any(), eq(key));

        mockMvc.perform(createOrder(key, body(productId, 1)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("ORDER_PROCESSING_FAILED"));

        verify(inventoryClient).release(eq(productId), eq(1), anyString());
    }

    @Test
    void sameKeyStillBeingProcessedIs409OrderConflict() throws Exception {
        String key = key();
        CountDownLatch reservationStarted = new CountDownLatch(1);
        CountDownLatch finishReservation = new CountDownLatch(1);
        when(inventoryClient.reserve(any(), anyInt(), anyString())).thenAnswer(invocation -> {
            reservationStarted.countDown();
            finishReservation.await(10, TimeUnit.SECONDS);   // hold request #1 mid-flight
            return new InventoryOperationResult(false);
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> inFlight = pool.submit(() -> mockMvc.perform(createOrder(key, body(productId, 1)))
                    .andExpect(status().isCreated()));
            org.assertj.core.api.Assertions.assertThat(
                    reservationStarted.await(10, TimeUnit.SECONDS)).isTrue();

            // identical request while #1 still holds the key: told to retry, never a second order
            mockMvc.perform(createOrder(key, body(productId, 1)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.error").value("ORDER_CONFLICT"));

            finishReservation.countDown();
            inFlight.get(10, TimeUnit.SECONDS);   // #1 still completes normally
        } finally {
            finishReservation.countDown();
            pool.shutdownNow();
        }

        // and once #1 is done the same key replays its order
        mockMvc.perform(createOrder(key, body(productId, 1)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        verify(inventoryClient, times(1)).reserve(any(), anyInt(), anyString());
    }
}
