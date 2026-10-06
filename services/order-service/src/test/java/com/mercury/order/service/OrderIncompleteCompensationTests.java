package com.mercury.order.service;

import com.mercury.order.client.InventoryClient;
import com.mercury.order.client.ProductClient;
import com.mercury.order.dto.CreateOrderItemRequest;
import com.mercury.order.dto.CreateOrderRequest;
import com.mercury.order.dto.InventoryOperationResult;
import com.mercury.order.dto.ProductDetails;
import com.mercury.order.exception.InventoryServiceException;
import com.mercury.order.exception.OrderConflictException;
import com.mercury.order.exception.OrderProcessingException;
import com.mercury.order.model.ClaimStatus;
import com.mercury.order.model.Order;
import com.mercury.order.model.OrderIdempotencyRecord;
import com.mercury.order.model.OrderStatus;
import com.mercury.order.repository.OrderIdempotencyRecordRepository;
import com.mercury.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * When compensation itself fails the order must NOT be called CANCELLED: it stays PENDING with
 * its key held, so nothing is double-reserved and reconciliation can find it.
 */
@SpringBootTest(properties = "order.idempotency.wait-timeout=300ms")
class OrderIncompleteCompensationTests {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderIdempotencyRecordRepository claimRepository;

    @MockitoBean
    private ProductClient productClient;

    @MockitoBean
    private InventoryClient inventoryClient;

    @MockitoSpyBean
    private OrderTransactions transactions;

    @Test
    void ifReleaseFailsTheOrderStaysPendingAndTheKeyStaysHeld() {
        UUID a = UUID.randomUUID();
        when(productClient.getProduct(a))
                .thenReturn(new ProductDetails(a, "A", "SKU-A", new BigDecimal("10.00")));
        when(inventoryClient.reserve(any(), anyInt(), anyString()))
                .thenReturn(new InventoryOperationResult(false));
        when(inventoryClient.release(any(), anyInt(), anyString()))
                .thenThrow(new InventoryServiceException(HttpStatus.SERVICE_UNAVAILABLE, null));
        String key = "key-" + UUID.randomUUID();
        doThrow(new IllegalStateException("db error")).when(transactions).confirm(any(), eq(key));

        assertThatThrownBy(() -> orderService.createOrder(key,
                new CreateOrderRequest(List.of(new CreateOrderItemRequest(a, 1)))))
                .isInstanceOf(OrderProcessingException.class);

        OrderIdempotencyRecord claim = claimRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.IN_PROGRESS);
        Order order = orderRepository.findById(claim.getOrderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);   // never "CANCELLED" with stock leaked

        // a retry cannot start a second reservation behind the unresolved one
        clearInvocations(inventoryClient);
        assertThatThrownBy(() -> orderService.createOrder(key,
                new CreateOrderRequest(List.of(new CreateOrderItemRequest(a, 1)))))
                .isInstanceOf(OrderConflictException.class);
        verify(inventoryClient, never()).reserve(any(), anyInt(), anyString());
    }
}
