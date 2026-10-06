package com.mercury.order.service;

import com.mercury.order.client.InventoryClient;
import com.mercury.order.client.ProductClient;
import com.mercury.order.dto.CreateOrderItemRequest;
import com.mercury.order.dto.CreateOrderRequest;
import com.mercury.order.dto.InventoryOperationResult;
import com.mercury.order.dto.ProductDetails;
import com.mercury.order.exception.IdempotencyKeyMismatchException;
import com.mercury.order.exception.StockStillHeldException;
import com.mercury.order.inbound.InventoryEventHandler;
import com.mercury.order.inbound.InvalidInventoryEventException;
import com.mercury.order.model.OrderStatus;
import com.mercury.order.model.ReservationMode;
import com.mercury.order.model.ReservationStatus;
import com.mercury.order.model.SagaState;
import com.mercury.order.outbox.OutboxEvent;
import com.mercury.order.outbox.OutboxRepository;
import com.mercury.order.repository.OrderIdempotencyRecordRepository;
import com.mercury.order.repository.OrderItemRepository;
import com.mercury.order.repository.OrderRepository;
import com.mercury.order.repository.OrderSagaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The ASYNC order path: accept, wait, and finish from Inventory's reply, against a real (H2) database
 * with Product and Inventory mocked at the client boundary. Replies are fed to the same handler the Kafka
 * listener calls. The waiting deadline is long here, so recovery never interferes.
 */
@SpringBootTest(properties = {
        "order.reservation.mode=ASYNC",
        "order.reservation.async-deadline=60s",
        "order.recovery.initial-backoff=50ms",
        "order.recovery.max-backoff=400ms"
})
class AsyncReservationTests {

    @Autowired private OrderService orderService;
    @Autowired private OrderTransactions transactions;
    @Autowired private SagaRecovery recovery;
    @Autowired private InventoryEventHandler handler;
    @Autowired private OutboxRepository outbox;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository itemRepository;
    @Autowired private OrderSagaRepository sagaRepository;
    @Autowired private OrderIdempotencyRecordRepository claims;
    @Autowired private JsonMapper json;

    @MockitoBean private ProductClient productClient;
    @MockitoBean private InventoryClient inventoryClient;

    @BeforeEach
    void inventoryReleasesEverything() {
        when(inventoryClient.release(any(), anyInt(), anyString())).thenReturn(new InventoryOperationResult(false));
    }

    // ---- helpers -------------------------------------------------------------------------

    private UUID product() {
        UUID id = UUID.randomUUID();
        when(productClient.getProduct(id))
                .thenReturn(new ProductDetails(id, "Item", "SKU-" + id, new BigDecimal("10.00")));
        return id;
    }

    private static CreateOrderRequest order(UUID productId, int quantity) {
        return new CreateOrderRequest(List.of(new CreateOrderItemRequest(productId, quantity)));
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private static String reserved(UUID eventId, UUID orderId, UUID productId, int quantity) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"InventoryReserved\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"orderId\":\"" + orderId + "\","
                + "\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}]}";
    }

    private static String rejected(UUID eventId, UUID orderId, UUID productId, String reason) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"InventoryRejected\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"orderId\":\"" + orderId + "\","
                + "\"reason\":\"" + reason + "\",\"productId\":\"" + productId + "\"}";
    }

    private OrderCreationResult accepted(UUID productId, int quantity, String key) {
        OrderCreationResult result = orderService.createOrder(key, order(productId, quantity));
        assertThat(result.kind()).isEqualTo(OrderCreationResult.Kind.ACCEPTED);
        return result;
    }

    private List<String> eventTypes(UUID orderId) {
        return outbox.findByAggregateIdOrderBySeq(orderId).stream().map(OutboxEvent::getEventType).toList();
    }

    private OrderStatus orderStatus(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private SagaState sagaState(UUID orderId) {
        return sagaRepository.findById(orderId).orElseThrow().getState();
    }

    // ---- accepting an order ----------------------------------------------------------------

    @Test
    void anAsyncOrderIsAcceptedPendingWithoutCallingInventory() {
        UUID p = product();

        OrderCreationResult result = accepted(p, 2, key());

        UUID orderId = result.order().id();
        assertThat(result.replayed()).isFalse();
        assertThat(result.order().status()).isEqualTo(OrderStatus.PENDING);
        assertThat(transactions.modeOf(orderId)).contains(ReservationMode.ASYNC);
        assertThat(sagaState(orderId)).isEqualTo(SagaState.AWAITING_INVENTORY);
        assertThat(claims.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(com.mercury.order.model.ClaimStatus.IN_PROGRESS);
        verifyNoInteractions(inventoryClient);   // no REST reservation: Inventory is asked over Kafka
    }

    @Test
    void acceptingWritesOrderCreatedAndTheReservationCommandInOneGo() {
        UUID p = product();

        UUID orderId = accepted(p, 3, key()).order().id();

        assertThat(eventTypes(orderId)).containsExactly("OrderCreated", "InventoryReservationRequested");
        OutboxEvent command = outbox.findByAggregateIdOrderBySeq(orderId).get(1);
        JsonNode payload = json.readTree(command.getPayload());
        assertThat(payload.path("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(payload.path("items").get(0).path("productId").asString()).isEqualTo(p.toString());
        assertThat(payload.path("items").get(0).path("quantity").asInt()).isEqualTo(3);
    }

    // ---- replies -----------------------------------------------------------------------------

    @Test
    void anInventoryReservedReplyConfirmsTheOrder() {
        UUID p = product();
        String key = key();
        UUID orderId = accepted(p, 2, key).order().id();

        InboundResult result = handler.handle(reserved(UUID.randomUUID(), orderId, p, 2));

        assertThat(result).isEqualTo(InboundResult.CONFIRMED);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(sagaState(orderId)).isEqualTo(SagaState.CONFIRMED);
        assertThat(claims.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(com.mercury.order.model.ClaimStatus.COMPLETED);
        assertThat(itemRepository.countHeld(orderId)).isEqualTo(1);   // held for the confirmed order
        assertThat(eventTypes(orderId)).containsExactly(
                "OrderCreated", "InventoryReservationRequested", "OrderConfirmed");
        verify(inventoryClient, never()).release(eq(p), anyInt(), anyString());
    }

    @Test
    void aRedeliveredReplyChangesNothingTheSecondTime() {
        UUID p = product();
        UUID orderId = accepted(p, 1, key()).order().id();
        UUID eventId = UUID.randomUUID();

        assertThat(handler.handle(reserved(eventId, orderId, p, 1))).isEqualTo(InboundResult.CONFIRMED);
        assertThat(handler.handle(reserved(eventId, orderId, p, 1))).isEqualTo(InboundResult.DUPLICATE);

        assertThat(eventTypes(orderId).stream().filter("OrderConfirmed"::equals)).hasSize(1);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void anInventoryRejectedReplyCancelsTheOrderWithInventorysReason() {
        UUID p = product();
        UUID orderId = accepted(p, 99, key()).order().id();

        InboundResult result = handler.handle(rejected(UUID.randomUUID(), orderId, p, "INSUFFICIENT_STOCK"));

        assertThat(result).isEqualTo(InboundResult.CANCELLED);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(sagaState(orderId)).isEqualTo(SagaState.CANCELLED);
        assertThat(itemRepository.countHeld(orderId)).isZero();
        List<OutboxEvent> events = outbox.findByAggregateIdOrderBySeq(orderId);
        assertThat(events).extracting(OutboxEvent::getEventType)
                .containsExactly("OrderCreated", "InventoryReservationRequested", "OrderCancelled");
        assertThat(json.readTree(events.get(2).getPayload()).path("reason").asString())
                .isEqualTo("INSUFFICIENT_STOCK");
        verify(inventoryClient, never()).release(eq(p), anyInt(), anyString());   // nothing was reserved
    }

    @Test
    void aReplyForAnUnknownOrderIsIgnoredAndRemembered() {
        UUID eventId = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();

        assertThat(handler.handle(reserved(eventId, unknown, UUID.randomUUID(), 1))).isEqualTo(InboundResult.IGNORED);
        assertThat(handler.handle(reserved(eventId, unknown, UUID.randomUUID(), 1))).isEqualTo(InboundResult.DUPLICATE);
    }

    @Test
    void aReplyForASynchronousOrderIsIgnored() {
        UUID p = product();
        UUID orderId = transactions.createPending("sync-" + UUID.randomUUID(), "hash",
                new OrderDraft(List.of(new OrderDraft.Item(p, "Item", "SKU", new BigDecimal("10.00"), 1)),
                        new BigDecimal("10.00")));

        assertThat(handler.handle(reserved(UUID.randomUUID(), orderId, p, 1))).isEqualTo(InboundResult.IGNORED);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PENDING);   // the synchronous saga owns it
    }

    @Test
    void unreadableOrIncompleteEventsAreInvalidAndNeverRetried() {
        assertThatThrownBy(() -> handler.handle("not json"))
                .isInstanceOf(InvalidInventoryEventException.class);
        assertThatThrownBy(() -> handler.handle("{\"eventType\":\"InventoryReserved\",\"eventId\":\"" + UUID.randomUUID() + "\"}"))
                .isInstanceOf(InvalidInventoryEventException.class);
        assertThatThrownBy(() -> handler.handle("{\"eventType\":\"InventoryReserved\",\"eventId\":\"x\",\"orderId\":\"" + UUID.randomUUID() + "\"}"))
                .isInstanceOf(InvalidInventoryEventException.class);
    }

    @Test
    void anEventOfAnotherTypeIsIgnored() {
        assertThat(handler.handle("{\"eventType\":\"SomethingElse\",\"eventId\":\"" + UUID.randomUUID()
                + "\",\"orderId\":\"" + UUID.randomUUID() + "\"}")).isEqualTo(InboundResult.IGNORED);
    }

    // ---- replays of the client request -----------------------------------------------------

    @Test
    void sameKeyWhileStillWaitingReturnsTheSamePendingOrderWithoutWaiting() {
        UUID p = product();
        String key = key();
        UUID orderId = accepted(p, 1, key).order().id();

        long start = System.nanoTime();
        OrderCreationResult replay = orderService.createOrder(key, order(p, 1));

        assertThat(replay.kind()).isEqualTo(OrderCreationResult.Kind.ACCEPTED);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.order().id()).isEqualTo(orderId);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1_000);   // did not poll for the reply
        assertThat(orderRepository.findAll().stream().filter(o -> o.getId().equals(orderId))).hasSize(1);
        assertThat(eventTypes(orderId).stream().filter("InventoryReservationRequested"::equals)).hasSize(1);
    }

    @Test
    void sameKeyAfterConfirmationReturnsTheConfirmedOrderAsOk() {
        UUID p = product();
        String key = key();
        UUID orderId = accepted(p, 1, key).order().id();
        handler.handle(reserved(UUID.randomUUID(), orderId, p, 1));

        OrderCreationResult replay = orderService.createOrder(key, order(p, 1));

        assertThat(replay.kind()).isEqualTo(OrderCreationResult.Kind.OK);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.order().id()).isEqualTo(orderId);
        assertThat(replay.order().status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void sameKeyAfterCancellationReturnsTheCancelledOrderAndNeverCreatesANewOne() {
        UUID p = product();
        String key = key();
        UUID orderId = accepted(p, 1, key).order().id();
        handler.handle(rejected(UUID.randomUUID(), orderId, p, "INSUFFICIENT_STOCK"));

        OrderCreationResult replay = orderService.createOrder(key, order(p, 1));

        assertThat(replay.kind()).isEqualTo(OrderCreationResult.Kind.OK);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.order().id()).isEqualTo(orderId);
        assertThat(replay.order().status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void sameKeyWithADifferentPayloadIsRejectedWhileWaiting() {
        UUID p = product();
        String key = key();
        accepted(p, 1, key);

        assertThatThrownBy(() -> orderService.createOrder(key, order(p, 2)))
                .isInstanceOf(IdempotencyKeyMismatchException.class);
    }

    // ---- a reservation that turns up when the order is already finished ----------------------

    @Test
    void aLateReservedReplyAfterCancellationIsReleasedByRecovery() {
        UUID p = product();
        UUID orderId = accepted(p, 2, key()).order().id();
        handler.handle(rejected(UUID.randomUUID(), orderId, p, "RESERVATION_TIMEOUT"));
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CANCELLED);

        InboundResult late = handler.handle(reserved(UUID.randomUUID(), orderId, p, 2));

        assertThat(late).isEqualTo(InboundResult.RELEASE_NEEDED);
        assertThat(sagaState(orderId)).isEqualTo(SagaState.COMPENSATING);   // reopened, durable
        assertThat(itemRepository.countHeld(orderId)).isEqualTo(1);

        recovery.recoverDue();

        verify(inventoryClient, times(1)).release(eq(p), eq(2), eq(SagaKeys.release(orderId, p)));
        assertThat(sagaState(orderId)).isEqualTo(SagaState.CANCELLED);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(itemRepository.countHeld(orderId)).isZero();
        assertThat(eventTypes(orderId).stream().filter("OrderCancelled"::equals)).hasSize(1);   // announced once
    }

    @Test
    void aLateReservedReplyIsReleasedAgainOnRetryUntilInventoryAnswers() {
        UUID p = product();
        UUID orderId = accepted(p, 1, key()).order().id();
        handler.handle(rejected(UUID.randomUUID(), orderId, p, "RESERVATION_TIMEOUT"));
        handler.handle(reserved(UUID.randomUUID(), orderId, p, 1));
        // only for this order's product: the database is shared with other tests, whose leftover work a recovery
        // pass may also pick up, and it must not consume this one-shot failure
        when(inventoryClient.release(eq(p), anyInt(), anyString()))
                .thenThrow(new com.mercury.order.exception.InventoryServiceException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, null))
                .thenReturn(new InventoryOperationResult(false));

        recovery.recoverDue();
        assertThat(sagaState(orderId)).isEqualTo(SagaState.COMPENSATING);   // not finished, still owed
        assertThat(itemRepository.countHeld(orderId)).isEqualTo(1);

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            recovery.recoverDue();
            assertThat(sagaState(orderId)).isEqualTo(SagaState.CANCELLED);
        });
        assertThat(itemRepository.countHeld(orderId)).isZero();
    }

    @Test
    void aSecondReservedReplyForAConfirmedOrderIsIgnoredAndNothingIsReleased() {
        UUID p = product();
        UUID orderId = accepted(p, 1, key()).order().id();
        handler.handle(reserved(UUID.randomUUID(), orderId, p, 1));

        InboundResult again = handler.handle(reserved(UUID.randomUUID(), orderId, p, 1));

        assertThat(again).isEqualTo(InboundResult.IGNORED);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        verify(inventoryClient, never()).release(eq(p), anyInt(), anyString());
    }

    @Test
    void aRejectedReplyAfterConfirmationIsIgnored() {
        UUID p = product();
        UUID orderId = accepted(p, 1, key()).order().id();
        handler.handle(reserved(UUID.randomUUID(), orderId, p, 1));

        assertThat(handler.handle(rejected(UUID.randomUUID(), orderId, p, "INSUFFICIENT_STOCK")))
                .isEqualTo(InboundResult.IGNORED);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void anOrderCannotBeCancelledWhileInventoryMayStillHoldItsStock() {
        UUID p = product();
        UUID orderId = accepted(p, 1, key()).order().id();
        transactions.markItem(orderId, p, ReservationStatus.RESERVED);

        assertThatThrownBy(() -> transactions.cancel(orderId)).isInstanceOf(StockStillHeldException.class);

        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void cancellingAnAlreadyCancelledOrderIsANoOp() {
        UUID p = product();
        UUID orderId = accepted(p, 1, key()).order().id();
        handler.handle(rejected(UUID.randomUUID(), orderId, p, "INSUFFICIENT_STOCK"));

        transactions.cancel(orderId);

        assertThat(eventTypes(orderId).stream().filter("OrderCancelled"::equals)).hasSize(1);
    }

    @Test
    void theModeIsFixedPerOrderSoSwitchingTheConfigurationCannotChangeInFlightOrders() {
        UUID p = product();
        UUID orderId = accepted(p, 1, key()).order().id();

        assertThat(transactions.modeOf(orderId)).isEqualTo(Optional.of(ReservationMode.ASYNC));
        assertThat(transactions.modeOf(UUID.randomUUID())).isEmpty();
    }
}
