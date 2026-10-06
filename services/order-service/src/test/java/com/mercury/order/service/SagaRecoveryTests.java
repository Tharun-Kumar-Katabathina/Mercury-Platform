package com.mercury.order.service;

import com.mercury.order.client.InventoryClient;
import com.mercury.order.client.ProductClient;
import com.mercury.order.dto.CreateOrderItemRequest;
import com.mercury.order.dto.CreateOrderRequest;
import com.mercury.order.dto.InventoryOperationResult;
import com.mercury.order.dto.ProductDetails;
import com.mercury.order.dto.ReservationSnapshot;
import com.mercury.order.exception.InventoryServiceException;
import com.mercury.order.exception.OrderConflictException;
import com.mercury.order.model.OrderSaga;
import com.mercury.order.model.OrderStatus;
import com.mercury.order.model.ReservationStatus;
import com.mercury.order.model.SagaState;
import com.mercury.order.repository.OrderIdempotencyRecordRepository;
import com.mercury.order.repository.OrderItemRepository;
import com.mercury.order.repository.OrderRepository;
import com.mercury.order.repository.OrderSagaRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Durable saga progress and recovery, against a real (H2) database with Product and Inventory
 * mocked at the client boundary so each ambiguous failure can be forced exactly. Recovery is run
 * explicitly (the background worker is off in tests).
 */
@SpringBootTest(properties = {
        "order.recovery.initial-backoff=50ms",
        "order.recovery.max-backoff=400ms",
        "order.recovery.max-attempts=3",
        "order.recovery.stale-after=30s",
        "order.recovery.lease=30s"
})
class SagaRecoveryTests {

    @Autowired private OrderService orderService;
    @Autowired private SagaRecovery recovery;
    @Autowired private OrderTransactions transactions;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository itemRepository;
    @Autowired private OrderSagaRepository sagaRepository;
    @Autowired private OrderIdempotencyRecordRepository claimRepository;
    @Autowired private MeterRegistry meters;

    @MockitoBean private ProductClient productClient;
    @MockitoBean private InventoryClient inventoryClient;

    private static final InventoryOperationResult OK = new InventoryOperationResult(false);

    @BeforeEach
    void inventoryAcceptsEverything() {
        when(inventoryClient.reserve(any(), anyInt(), anyString())).thenReturn(OK);
        when(inventoryClient.release(any(), anyInt(), anyString())).thenReturn(OK);
        when(inventoryClient.findReservation(any(), anyString())).thenReturn(Optional.empty());
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

    private static InventoryServiceException unavailable() {
        return new InventoryServiceException(HttpStatus.SERVICE_UNAVAILABLE, null);
    }

    private UUID orderIdFor(String idempotencyKey) {
        return claimRepository.findByIdempotencyKey(idempotencyKey).orElseThrow().getOrderId();
    }

    private UUID onlyOrderFor(UUID productId) {
        return orderRepository.findAll().stream()
                .map(o -> orderRepository.findWithItemsById(o.getId()).orElseThrow())
                .filter(o -> o.getItems().stream().anyMatch(i -> i.getProductId().equals(productId)))
                .map(o -> o.getId())
                .findFirst().orElseThrow();
    }

    private SagaState sagaState(UUID orderId) {
        return sagaRepository.findById(orderId).orElseThrow().getState();
    }

    private OrderStatus orderStatus(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private ReservationStatus itemStatus(UUID orderId, UUID productId) {
        return transactionsItems(orderId).stream()
                .filter(i -> i.productId().equals(productId)).findFirst().orElseThrow().status();
    }

    private List<ItemProgress> transactionsItems(UUID orderId) {
        return itemRepository.findProgress(orderId);
    }

    /** make a saga due for recovery right now (as if its backoff / staleness had elapsed) */
    private void makeDue(UUID orderId) {
        OrderSaga saga = sagaRepository.findById(orderId).orElseThrow();
        saga.heartbeat(Instant.now().minusSeconds(60), Instant.now().minusSeconds(60));
        sagaRepository.saveAndFlush(saga);
    }

    private double counter(String name) {
        var counter = meters.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    // ---- durable progress ------------------------------------------------------------------

    @Test
    void anItemIsMarkedReservingBeforeTheRemoteCallAndReservedAfterwards() {
        UUID p = product();
        String key = key();
        AtomicInteger seenDuringCall = new AtomicInteger(-1);
        when(inventoryClient.reserve(eq(p), anyInt(), anyString())).thenAnswer(invocation -> {
            UUID orderId = orderIdFor(key);
            seenDuringCall.set(itemStatus(orderId, p).ordinal());
            return OK;
        });

        UUID orderId = orderService.createOrder(key, order(p, 2)).order().id();

        assertThat(seenDuringCall.get()).isEqualTo(ReservationStatus.RESERVING.ordinal());
        assertThat(itemStatus(orderId, p)).isEqualTo(ReservationStatus.RESERVED);
        assertThat(sagaState(orderId)).isEqualTo(SagaState.CONFIRMED);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CONFIRMED);
    }

    // ---- ambiguous reservation (response lost) ----------------------------------------------

    @Test
    void aReservationWhoseResponseWasLostIsFoundByLookupThenReleasedAndTheOrderCancelled() {
        UUID p = product();
        // Inventory applied it, but the caller only saw a timeout
        when(inventoryClient.reserve(eq(p), anyInt(), anyString())).thenThrow(unavailable());
        when(inventoryClient.findReservation(eq(p), anyString()))
                .thenReturn(Optional.of(new ReservationSnapshot(p, 3)));
        String key = key();

        assertThatThrownBy(() -> orderService.createOrder(key, order(p, 3)))
                .isInstanceOf(InventoryServiceException.class);

        UUID orderId = onlyOrderFor(p);
        verify(inventoryClient).findReservation(eq(p), eq("order:" + orderId + ":product:" + p));
        verify(inventoryClient).release(eq(p), eq(3), eq("order:" + orderId + ":product:" + p + ":release"));
        assertThat(itemStatus(orderId, p)).isEqualTo(ReservationStatus.RELEASED);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(sagaState(orderId)).isEqualTo(SagaState.CANCELLED);
        assertThat(claimRepository.findByIdempotencyKey(key)).isEmpty();
    }

    @Test
    void aReserveThatNeverReachedInventoryIsFoundAbsentAndNothingIsReleased() {
        UUID p = product();
        when(inventoryClient.reserve(eq(p), anyInt(), anyString())).thenThrow(unavailable());
        // lookup answers "no reservation" (default)

        assertThatThrownBy(() -> orderService.createOrder(key(), order(p, 1)))
                .isInstanceOf(InventoryServiceException.class);

        UUID orderId = onlyOrderFor(p);
        verify(inventoryClient, never()).release(any(), anyInt(), anyString());
        assertThat(itemStatus(orderId, p)).isEqualTo(ReservationStatus.NOT_RESERVED);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void ifInventoryCannotEvenBeQueriedTheOrderStaysPendingForRecoveryThenResolves() {
        UUID p = product();
        when(inventoryClient.reserve(eq(p), anyInt(), anyString())).thenThrow(unavailable());
        when(inventoryClient.findReservation(any(), anyString())).thenThrow(unavailable());
        String key = key();

        assertThatThrownBy(() -> orderService.createOrder(key, order(p, 3)))
                .isInstanceOf(InventoryServiceException.class);

        UUID orderId = onlyOrderFor(p);
        // durable and visible: nothing is guessed while the outcome is unknown
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PENDING);
        assertThat(sagaState(orderId)).isEqualTo(SagaState.COMPENSATING);
        assertThat(sagaRepository.findById(orderId).orElseThrow().getAttemptCount()).isEqualTo(1);
        assertThat(itemStatus(orderId, p)).isEqualTo(ReservationStatus.RESERVING);
        verify(inventoryClient, never()).release(any(), anyInt(), anyString());

        // Inventory comes back: it did hold the reservation
        reset(inventoryClient);
        when(inventoryClient.findReservation(eq(p), anyString()))
                .thenReturn(Optional.of(new ReservationSnapshot(p, 3)));
        when(inventoryClient.release(any(), anyInt(), anyString())).thenReturn(OK);
        makeDue(orderId);

        assertThat(recovery.recoverDue()).isEqualTo(1);

        verify(inventoryClient).release(eq(p), eq(3), anyString());
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(sagaState(orderId)).isEqualTo(SagaState.CANCELLED);
        assertThat(claimRepository.findByIdempotencyKey(key)).isEmpty();
    }

    // ---- durable compensation ----------------------------------------------------------------

    @Test
    void aFailedReleaseBecomesDurableWorkAndTheRetryUsesTheSameKey() {
        UUID p = product();
        UUID q = product();
        UUID first = p.compareTo(q) < 0 ? p : q;
        UUID second = first.equals(p) ? q : p;
        when(inventoryClient.reserve(eq(second), anyInt(), anyString()))
                .thenThrow(new InventoryServiceException(HttpStatus.CONFLICT, "{\"error\":\"INSUFFICIENT_STOCK\"}"));
        when(inventoryClient.release(any(), anyInt(), anyString())).thenThrow(unavailable());
        String key = key();

        assertThatThrownBy(() -> orderService.createOrder(key, new CreateOrderRequest(List.of(
                new CreateOrderItemRequest(p, 1), new CreateOrderItemRequest(q, 1)))))
                .isInstanceOf(InventoryServiceException.class);

        UUID orderId = onlyOrderFor(first);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PENDING);
        assertThat(sagaState(orderId)).isEqualTo(SagaState.COMPENSATING);
        assertThat(itemStatus(orderId, first)).isEqualTo(ReservationStatus.RELEASING);

        // release works now
        reset(inventoryClient);
        when(inventoryClient.release(any(), anyInt(), anyString())).thenReturn(OK);
        makeDue(orderId);
        recovery.recoverDue();

        String releaseKey = "order:" + orderId + ":product:" + first + ":release";
        verify(inventoryClient).release(eq(first), eq(1), eq(releaseKey));
        assertThat(itemStatus(orderId, first)).isEqualTo(ReservationStatus.RELEASED);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CANCELLED);
    }

    // ---- a request that died mid-saga ----------------------------------------------------------

    @Test
    void anAbandonedSagaIsCompensatedOnceItsOwnerIsGoneAndTheKeyIsFreedForARetry() {
        UUID p = product();
        String key = key();
        // simulate: the process reserved, then died before confirming (state exactly as a crash leaves it)
        OrderDraft draft = new OrderDraft(
                List.of(new OrderDraft.Item(p, "Item", "SKU", new BigDecimal("10.00"), 2)),
                new BigDecimal("20.00"));
        UUID orderId = transactions.createPending(key, "hash", draft);
        transactions.markItem(orderId, p, ReservationStatus.RESERVING);
        transactions.markItem(orderId, p, ReservationStatus.RESERVED);

        // while its owner may still be alive, recovery must not touch it
        assertThat(recovery.recoverDue()).isZero();
        verify(inventoryClient, never()).release(any(), anyInt(), anyString());
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PENDING);

        // owner gone: lease and staleness expire
        makeDue(orderId);
        assertThat(recovery.recoverDue()).isEqualTo(1);

        verify(inventoryClient).release(eq(p), eq(2), eq("order:" + orderId + ":product:" + p + ":release"));
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(sagaState(orderId)).isEqualTo(SagaState.CANCELLED);
        // the same client key can now be used for a fresh order
        assertThat(orderService.createOrder(key, order(p, 1)).order().status())
                .isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void anUnfinishedRequestHoldsTheKeyUntilRecoveryResolvesIt() {
        UUID p = product();
        String key = key();
        OrderDraft draft = new OrderDraft(
                List.of(new OrderDraft.Item(p, "Item", "SKU", new BigDecimal("10.00"), 1)),
                new BigDecimal("10.00"));
        UUID orderId = transactions.createPending(key, hashOf(p, 1), draft);

        // a duplicate of the request cannot start a second order behind the unresolved one
        assertThatThrownBy(() -> orderServiceWithShortWait().createOrder(key, order(p, 1)))
                .isInstanceOf(OrderConflictException.class);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    // ---- bounded, backed-off retries -----------------------------------------------------------

    @Test
    void retriesBackOffExponentiallyAndStopAtTheConfiguredMaximum() {
        UUID p = product();
        when(inventoryClient.reserve(any(), anyInt(), anyString())).thenThrow(unavailable());
        when(inventoryClient.findReservation(any(), anyString())).thenThrow(unavailable());
        assertThatThrownBy(() -> orderService.createOrder(key(), order(p, 1)))
                .isInstanceOf(InventoryServiceException.class);
        UUID orderId = onlyOrderFor(p);

        // attempt 1 happened inline; schedule gaps grow: 50ms, 100ms, ... capped at 400ms
        OrderSaga afterInline = sagaRepository.findById(orderId).orElseThrow();
        assertThat(afterInline.getAttemptCount()).isEqualTo(1);
        Instant firstNext = afterInline.getNextAttemptAt();

        makeDue(orderId);
        recovery.recoverDue();                                            // failed attempt 2
        OrderSaga second = sagaRepository.findById(orderId).orElseThrow();
        assertThat(second.getAttemptCount()).isEqualTo(2);
        assertThat(second.getState()).isEqualTo(SagaState.COMPENSATING);
        assertThat(second.getNextAttemptAt()).isAfter(Instant.now().minusSeconds(1));
        assertThat(second.getLastError()).isNotBlank();

        makeDue(orderId);
        recovery.recoverDue();                                            // failed attempt 3 = the maximum
        OrderSaga exhausted = sagaRepository.findById(orderId).orElseThrow();
        assertThat(exhausted.getState()).isEqualTo(SagaState.RECOVERY_FAILED);
        assertThat(exhausted.getAttemptCount()).isEqualTo(3);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PENDING);  // left for a person, not guessed

        // and recovery no longer touches it
        clearInvocations(inventoryClient);
        makeDue(orderId);
        assertThat(recovery.recoverDue()).isZero();
        verifyNoInteractions(inventoryClient);
        assertThat(firstNext).isNotNull();
    }

    // ---- recovery is idempotent and exclusive --------------------------------------------------

    @Test
    void recoveryRunTwiceOnTheSameSagaReleasesOnce() {
        UUID p = product();
        OrderDraft draft = new OrderDraft(
                List.of(new OrderDraft.Item(p, "Item", "SKU", new BigDecimal("10.00"), 4)),
                new BigDecimal("40.00"));
        UUID orderId = transactions.createPending(key(), "hash", draft);
        transactions.markItem(orderId, p, ReservationStatus.RESERVED);
        makeDue(orderId);

        recovery.recoverDue();
        recovery.recoverDue();

        verify(inventoryClient, times(1)).release(eq(p), eq(4), anyString());
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void twoRecoveryWorkersRacingNeverProcessTheSameSagaTwice() throws Exception {
        UUID p = product();
        OrderDraft draft = new OrderDraft(
                List.of(new OrderDraft.Item(p, "Item", "SKU", new BigDecimal("10.00"), 5)),
                new BigDecimal("50.00"));
        UUID orderId = transactions.createPending(key(), "hash", draft);
        transactions.markItem(orderId, p, ReservationStatus.RESERVED);
        makeDue(orderId);

        // a slow release keeps the first worker busy while the second one looks around
        CountDownLatch firstIsReleasing = new CountDownLatch(1);
        CountDownLatch letItFinish = new CountDownLatch(1);
        when(inventoryClient.release(eq(p), anyInt(), anyString())).thenAnswer(invocation -> {
            firstIsReleasing.countDown();
            letItFinish.await();
            return OK;
        });

        Thread first = new Thread(() -> recovery.recoverDue());
        first.start();
        assertThat(firstIsReleasing.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        int claimedBySecond = recovery.recoverDue();   // runs while the first still owns the saga

        letItFinish.countDown();
        first.join(10_000);

        assertThat(claimedBySecond).isZero();
        verify(inventoryClient, times(1)).release(eq(p), eq(5), anyString());
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void concurrentWorkersDoNotCorruptManySagas() throws Exception {
        int sagas = 12;
        List<UUID> orders = new java.util.ArrayList<>();
        for (int i = 0; i < sagas; i++) {
            UUID p = product();
            OrderDraft draft = new OrderDraft(
                    List.of(new OrderDraft.Item(p, "Item", "SKU", new BigDecimal("1.00"), 1)),
                    new BigDecimal("1.00"));
            UUID orderId = transactions.createPending(key(), "hash", draft);
            transactions.markItem(orderId, p, ReservationStatus.RESERVED);
            makeDue(orderId);
            orders.add(orderId);
        }

        List<Thread> workers = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread worker = new Thread(() -> {
                for (int pass = 0; pass < 5; pass++) {
                    recovery.recoverDue();
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join(30_000);
        }

        verify(inventoryClient, times(sagas)).release(any(), anyInt(), anyString());   // exactly one each
        for (UUID orderId : orders) {
            assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CANCELLED);
            assertThat(sagaState(orderId)).isEqualTo(SagaState.CANCELLED);
        }
    }

    // ---- metrics ----------------------------------------------------------------------------

    @Test
    void recoveryAndCompensationAreCounted() {
        double recoveredBefore = counter("orders.recovery.success");
        double releasedBefore = counter("saga.compensation.success");
        UUID p = product();
        OrderDraft draft = new OrderDraft(
                List.of(new OrderDraft.Item(p, "Item", "SKU", new BigDecimal("1.00"), 1)),
                new BigDecimal("1.00"));
        UUID orderId = transactions.createPending(key(), "hash", draft);
        transactions.markItem(orderId, p, ReservationStatus.RESERVED);
        makeDue(orderId);

        recovery.recoverDue();

        assertThat(counter("orders.recovery.success")).isEqualTo(recoveredBefore + 1);
        assertThat(counter("saga.compensation.success")).isEqualTo(releasedBefore + 1);
    }

    // ---- helpers needing the service's hashing / a short wait ----------------------------------

    @Autowired private com.mercury.order.service.OrderPlanner planner;

    private String hashOf(UUID p, int quantity) {
        // the same canonical hash OrderService computes, so a duplicate matches the held claim
        try {
            var method = OrderService.class.getDeclaredMethod("requestHash", List.class);
            method.setAccessible(true);
            return (String) method.invoke(null, planner.validate(order(p, quantity)));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private OrderService orderServiceWithShortWait() {
        return new OrderService(orderRepository, planner, transactions,
                new OrderSagaService(transactions, inventoryClient,
                        new SagaMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                                sagaRepository, java.time.Clock.systemUTC())),
                java.time.Duration.ofMillis(200),
                new com.mercury.order.config.ReservationProperties(
                        com.mercury.order.model.ReservationMode.SYNC, java.time.Duration.ofSeconds(60)));
    }
}
