package com.mercury.order.service;

import com.mercury.order.client.InventoryClient;
import com.mercury.order.client.ProductClient;
import com.mercury.order.dto.CreateOrderItemRequest;
import com.mercury.order.dto.CreateOrderRequest;
import com.mercury.order.dto.InventoryOperationResult;
import com.mercury.order.dto.OrderReservationSnapshot;
import com.mercury.order.dto.ProductDetails;
import com.mercury.order.exception.InventoryServiceException;
import com.mercury.order.inbound.InventoryEventHandler;
import com.mercury.order.model.OrderSaga;
import com.mercury.order.model.OrderStatus;
import com.mercury.order.model.SagaState;
import com.mercury.order.outbox.OutboxEvent;
import com.mercury.order.outbox.OutboxRepository;
import com.mercury.order.repository.OrderItemRepository;
import com.mercury.order.repository.OrderRepository;
import com.mercury.order.repository.OrderSagaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An ASYNC order whose reply never came: after the waiting deadline recovery asks Inventory what it decided
 * (the deadline is 1ms here, so it is due almost immediately; recovery is driven explicitly).
 */
@SpringBootTest(properties = {
        "order.reservation.mode=ASYNC",
        "order.reservation.async-deadline=1ms",
        "order.recovery.initial-backoff=50ms",
        "order.recovery.max-backoff=200ms",
        "order.recovery.max-attempts=3"
})
class AsyncReservationRecoveryTests {

    @Autowired private OrderService orderService;
    @Autowired private SagaRecovery recovery;
    @Autowired private InventoryEventHandler handler;
    @Autowired private OutboxRepository outbox;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository itemRepository;
    @Autowired private OrderSagaRepository sagaRepository;
    @Autowired private JsonMapper json;

    @MockitoBean private ProductClient productClient;
    @MockitoBean private InventoryClient inventoryClient;

    @BeforeEach
    void inventoryReleasesEverything() {
        when(inventoryClient.release(any(), anyInt(), anyString())).thenReturn(new InventoryOperationResult(false));
    }

    private UUID product() {
        UUID id = UUID.randomUUID();
        when(productClient.getProduct(id))
                .thenReturn(new ProductDetails(id, "Item", "SKU-" + id, new BigDecimal("10.00")));
        return id;
    }

    private UUID acceptedOrder(UUID productId, int quantity) {
        return orderService.createOrder("key-" + UUID.randomUUID(),
                new CreateOrderRequest(List.of(new CreateOrderItemRequest(productId, quantity)))).order().id();
    }

    private void recoverUntil(UUID orderId, SagaState expected) {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20)).untilAsserted(() -> {
            recovery.recoverDue();
            assertThat(sagaRepository.findById(orderId).orElseThrow().getState()).isEqualTo(expected);
        });
    }

    private OrderStatus orderStatus(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private List<String> eventTypes(UUID orderId) {
        return outbox.findByAggregateIdOrderBySeq(orderId).stream().map(OutboxEvent::getEventType).toList();
    }

    @Test
    void whenInventoryHasReservedTheOrderAfterTheDeadlineItIsConfirmed() {
        UUID p = product();
        UUID orderId = acceptedOrder(p, 2);
        when(inventoryClient.findOrderReservation(orderId))
                .thenReturn(Optional.of(new OrderReservationSnapshot(orderId, "RESERVED", null)));

        recoverUntil(orderId, SagaState.CONFIRMED);

        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(itemRepository.countHeld(orderId)).isEqualTo(1);
        assertThat(eventTypes(orderId)).contains("OrderConfirmed");
        verify(inventoryClient, never()).release(any(), anyInt(), anyString());
    }

    @Test
    void whenInventoryRejectedTheOrderAfterTheDeadlineItIsCancelledWithTheReason() {
        UUID p = product();
        UUID orderId = acceptedOrder(p, 50);
        when(inventoryClient.findOrderReservation(orderId))
                .thenReturn(Optional.of(new OrderReservationSnapshot(orderId, "REJECTED", "INSUFFICIENT_STOCK")));

        recoverUntil(orderId, SagaState.CANCELLED);

        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CANCELLED);
        OutboxEvent cancelled = outbox.findByAggregateIdOrderBySeq(orderId).stream()
                .filter(e -> e.getEventType().equals("OrderCancelled")).findFirst().orElseThrow();
        assertThat(json.readTree(cancelled.getPayload()).path("reason").asString()).isEqualTo("INSUFFICIENT_STOCK");
        verify(inventoryClient, never()).release(any(), anyInt(), anyString());
    }

    @Test
    void whenInventoryHasDecidedNothingTheOrderIsCancelledAsATimeout() {
        UUID p = product();
        UUID orderId = acceptedOrder(p, 1);
        when(inventoryClient.findOrderReservation(orderId)).thenReturn(Optional.empty());

        recoverUntil(orderId, SagaState.CANCELLED);

        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CANCELLED);
        OutboxEvent cancelled = outbox.findByAggregateIdOrderBySeq(orderId).stream()
                .filter(e -> e.getEventType().equals("OrderCancelled")).findFirst().orElseThrow();
        assertThat(json.readTree(cancelled.getPayload()).path("reason").asString()).isEqualTo("RESERVATION_TIMEOUT");
    }

    @Test
    void aReservationThatShowsUpAfterATimeoutCancellationIsGivenBack() {
        UUID p = product();
        UUID orderId = acceptedOrder(p, 4);
        when(inventoryClient.findOrderReservation(orderId)).thenReturn(Optional.empty());
        recoverUntil(orderId, SagaState.CANCELLED);

        String late = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"InventoryReserved\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"orderId\":\"" + orderId + "\","
                + "\"items\":[{\"productId\":\"" + p + "\",\"quantity\":4}]}";
        assertThat(handler.handle(late)).isEqualTo(InboundResult.RELEASE_NEEDED);
        recoverUntil(orderId, SagaState.CANCELLED);   // reopened, released, cancelled again

        verify(inventoryClient).release(eq(p), eq(4), eq(SagaKeys.release(orderId, p)));
        assertThat(itemRepository.countHeld(orderId)).isZero();
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(eventTypes(orderId).stream().filter("OrderCancelled"::equals)).hasSize(1);
    }

    @Test
    void whenInventoryCannotBeAskedTheOrderStaysWaitingAndIsRetriedWithBackoff() {
        UUID p = product();
        UUID orderId = acceptedOrder(p, 1);
        when(inventoryClient.findOrderReservation(orderId))
                .thenThrow(new InventoryServiceException(HttpStatus.SERVICE_UNAVAILABLE, null));
        await().atMost(Duration.ofSeconds(2)).until(() -> {
            recovery.recoverDue();
            return sagaRepository.findById(orderId).orElseThrow().getAttemptCount() >= 1;
        });

        OrderSaga saga = sagaRepository.findById(orderId).orElseThrow();
        assertThat(saga.getState()).isEqualTo(SagaState.AWAITING_INVENTORY);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PENDING);   // never cancelled just because Inventory is down
        assertThat(saga.getLastError()).isNotBlank();

        // once Inventory answers again, the order resolves
        org.mockito.Mockito.doReturn(Optional.of(new OrderReservationSnapshot(orderId, "RESERVED", null)))
                .when(inventoryClient).findOrderReservation(orderId);
        recoverUntil(orderId, SagaState.CONFIRMED);
    }

    @Test
    void afterTheConfiguredAttemptsAnUnresolvableOrderBecomesRecoveryFailed() {
        UUID p = product();
        UUID orderId = acceptedOrder(p, 1);
        when(inventoryClient.findOrderReservation(orderId))
                .thenThrow(new InventoryServiceException(HttpStatus.SERVICE_UNAVAILABLE, null));

        recoverUntil(orderId, SagaState.RECOVERY_FAILED);

        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PENDING);   // needs a person; nothing guessed
        assertThat(itemRepository.countHeld(orderId)).isZero();
    }

    @Test
    void aReplyThatArrivesBeforeRecoveryWinsAndRecoveryLeavesTheOrderAlone() {
        UUID p = product();
        UUID orderId = acceptedOrder(p, 1);
        String reply = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"InventoryReserved\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"orderId\":\"" + orderId + "\","
                + "\"items\":[{\"productId\":\"" + p + "\",\"quantity\":1}]}";
        handler.handle(reply);

        recovery.recoverDue();

        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        verify(inventoryClient, never()).findOrderReservation(orderId);
    }
}
