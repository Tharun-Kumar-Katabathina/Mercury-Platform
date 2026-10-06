package com.mercury.order.service;

import com.mercury.order.config.RecoveryProperties;
import com.mercury.order.dto.OrderResponse;
import com.mercury.order.event.OrderCancelledEvent;
import com.mercury.order.event.OrderConfirmedEvent;
import com.mercury.order.event.InventoryReservationRequestedEvent;
import com.mercury.order.event.OrderCreatedEvent;
import com.mercury.order.exception.StockStillHeldException;
import com.mercury.order.inbound.ProcessedInboundEvent;
import com.mercury.order.inbound.ProcessedInboundEventRepository;
import com.mercury.order.model.ReservationMode;
import com.mercury.order.model.SagaState;
import com.mercury.order.outbox.OutboxWriter;
import com.mercury.order.model.ClaimStatus;
import com.mercury.order.model.Order;
import com.mercury.order.model.OrderIdempotencyRecord;
import com.mercury.order.model.OrderItem;
import com.mercury.order.model.OrderSaga;
import com.mercury.order.model.OrderStatus;
import com.mercury.order.model.ReservationStatus;
import com.mercury.order.repository.OrderIdempotencyRecordRepository;
import com.mercury.order.repository.OrderItemRepository;
import com.mercury.order.repository.OrderSagaRepository;
import com.mercury.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every LOCAL database transaction the order flow needs, each small and self-contained.
 *
 * Order Service's database can be made consistent by @Transactional; Product and Inventory
 * are other systems and are never inside these transactions. OrderService calls them
 * between these steps and undoes them by compensation if a later step fails.
 */
@Service
public class OrderTransactions {

    /** What is known about a client Idempotency-Key. */
    public record Claim(ClaimStatus status, String requestHash, UUID orderId, String snapshot) {
    }

    private final OrderRepository orderRepository;
    private final OrderItemRepository itemRepository;
    private final OrderIdempotencyRecordRepository claimRepository;
    private final OrderSagaRepository sagaRepository;
    private final JsonMapper jsonMapper;
    private final RecoveryProperties recovery;
    private final Clock clock;
    private final OutboxWriter outbox;
    private final ProcessedInboundEventRepository processedEvents;

    public OrderTransactions(
            OrderRepository orderRepository,
            OrderItemRepository itemRepository,
            OrderIdempotencyRecordRepository claimRepository,
            OrderSagaRepository sagaRepository,
            JsonMapper jsonMapper,
            RecoveryProperties recovery,
            Clock clock,
            OutboxWriter outbox,
            ProcessedInboundEventRepository processedEvents) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.claimRepository = claimRepository;
        this.sagaRepository = sagaRepository;
        this.jsonMapper = jsonMapper;
        this.recovery = recovery;
        this.clock = clock;
        this.outbox = outbox;
        this.processedEvents = processedEvents;
    }

    private Instant now() {
        return Instant.now(clock);
    }

    /**
     * Saves the order as PENDING and claims the Idempotency-Key in ONE transaction. If another
     * request already holds the key, the unique constraint fails the insert and this whole
     * transaction (including the order) rolls back, so a losing request leaves nothing behind.
     */
    @Transactional
    public UUID createPending(String idempotencyKey, String requestHash, OrderDraft draft) {

        Order order = Order.pending(draft.totalAmount());
        for (OrderDraft.Item item : draft.items()) {
            order.addItem(new OrderItem(
                    item.productId(), item.productName(), item.sku(),
                    item.unitPrice(), item.quantity()));
        }
        order = orderRepository.saveAndFlush(order);

        claimRepository.saveAndFlush(
                OrderIdempotencyRecord.claim(idempotencyKey, requestHash, order.getId()));

        // the live request owns the saga for a lease; if it dies, recovery takes over afterwards
        sagaRepository.saveAndFlush(OrderSaga.start(
                order.getId(), now().plus(recovery.staleAfter()), now().plus(recovery.lease())));

        // the event commits (or rolls back) together with the order it describes
        outbox.append(OrderCreatedEvent.of(order.getId(), now(),
                draft.items().stream()
                        .map(i -> new OrderCreatedEvent.Item(
                                i.productId(), i.quantity(), i.productName(), i.sku(), i.unitPrice()))
                        .toList(),
                draft.totalAmount()));

        return order.getId();
    }

    /** PENDING -> CONFIRMED and key IN_PROGRESS -> COMPLETED with the original response. */
    @Transactional
    public OrderResponse confirm(UUID orderId, String idempotencyKey) {

        OrderIdempotencyRecord claim = claimRepository.findByIdempotencyKey(idempotencyKey).orElseThrow();
        return confirmOrder(orderId, claim);
    }

    private OrderResponse confirmOrder(UUID orderId, OrderIdempotencyRecord claim) {

        Order order = orderRepository.findWithItemsById(orderId).orElseThrow();
        order.confirm();
        order = orderRepository.saveAndFlush(order);   // flush so updatedAt is final

        OrderResponse response = OrderResponse.from(order);

        claim.complete(jsonMapper.writeValueAsString(response));
        claimRepository.saveAndFlush(claim);

        OrderSaga saga = sagaRepository.findById(orderId).orElseThrow();
        saga.confirmed();
        sagaRepository.saveAndFlush(saga);

        outbox.append(OrderConfirmedEvent.of(orderId, now()));

        return response;
    }

    /**
     * PENDING -> CANCELLED and saga CANCELLED. SYNC: the key is freed so the client can retry the same
     * request and have it evaluated afresh (failures are never replayed). ASYNC: the client already holds an
     * order id, so the claim is completed with the CANCELLED order and a replay returns it.
     *
     * Refuses while any item may still be held at Inventory (StockStillHeldException), and is a no-op for
     * an order that is already CANCELLED (the late-reservation release path finishing its work).
     */
    @Transactional
    public void cancel(UUID orderId) {
        cancelOrder(orderId);
    }

    private void cancelOrder(UUID orderId) {

        Order order = orderRepository.findById(orderId).orElseThrow();

        if (itemRepository.countHeld(orderId) > 0) {
            throw new StockStillHeldException(orderId);
        }

        if (order.getStatus() == OrderStatus.CANCELLED) {
            OrderSaga saga = sagaRepository.findById(orderId).orElseThrow();
            if (saga.getState() != SagaState.CANCELLED) {
                saga.cancelled();
                sagaRepository.saveAndFlush(saga);
            }
            return;   // already cancelled and announced: nothing more to do, and no second event
        }

        order.cancel();
        orderRepository.saveAndFlush(order);

        if (order.getReservationMode() == ReservationMode.ASYNC) {
            OrderResponse cancelled = OrderResponse.from(
                    orderRepository.findWithItemsById(orderId).orElseThrow());
            OrderIdempotencyRecord claim = claimRepository.findByOrderId(orderId).orElseThrow();
            claim.complete(jsonMapper.writeValueAsString(cancelled));
            claimRepository.saveAndFlush(claim);
        } else {
            claimRepository.deleteByOrderId(orderId);
        }

        OrderSaga saga = sagaRepository.findById(orderId).orElseThrow();
        String reason = saga.getFailureReason() == null ? "UNKNOWN" : saga.getFailureReason();
        saga.cancelled();
        sagaRepository.saveAndFlush(saga);

        outbox.append(OrderCancelledEvent.of(orderId, now(), reason));
    }

    // ---- ASYNC reservation (Phase 10) --------------------------------------------------------

    /**
     * ASYNC sibling of createPending: ONE transaction saves the PENDING order (mode ASYNC), claims the
     * Idempotency-Key, starts the saga as AWAITING_INVENTORY with the waiting deadline, and writes both
     * OrderCreated (a fact for other consumers) and InventoryReservationRequested (the command for
     * Inventory) to the outbox. Either everything exists or nothing does.
     */
    @Transactional
    public UUID createPendingAsync(
            String idempotencyKey, String requestHash, OrderDraft draft, java.time.Duration deadline) {

        Order order = Order.pending(draft.totalAmount(), ReservationMode.ASYNC);
        for (OrderDraft.Item item : draft.items()) {
            order.addItem(new OrderItem(
                    item.productId(), item.productName(), item.sku(),
                    item.unitPrice(), item.quantity()));
        }
        order = orderRepository.saveAndFlush(order);

        claimRepository.saveAndFlush(
                OrderIdempotencyRecord.claim(idempotencyKey, requestHash, order.getId()));
        sagaRepository.saveAndFlush(OrderSaga.awaitingInventory(order.getId(), now().plus(deadline)));

        outbox.append(OrderCreatedEvent.of(order.getId(), now(),
                draft.items().stream()
                        .map(i -> new OrderCreatedEvent.Item(
                                i.productId(), i.quantity(), i.productName(), i.sku(), i.unitPrice()))
                        .toList(),
                draft.totalAmount()));
        outbox.append(InventoryReservationRequestedEvent.of(order.getId(), now(),
                draft.items().stream()
                        .map(i -> new InventoryReservationRequestedEvent.Item(i.productId(), i.quantity()))
                        .toList()));

        return order.getId();
    }

    /**
     * InventoryReserved arrived. Pending and waiting: reserve all items and CONFIRM. Anything else: the
     * stock Inventory holds must go back. A confirmed order is left alone, because that stock is its own.
     * The "event handled" marker is in the same transaction, so a crash cannot lose or repeat the effect.
     */
    @Transactional
    public InboundResult applyInventoryReserved(UUID eventId, UUID orderId) {

        if (processedEvents.existsById(eventId)) {
            return InboundResult.DUPLICATE;
        }
        processedEvents.saveAndFlush(new ProcessedInboundEvent(eventId, "inventory-event", now()));

        Optional<Order> found = orderRepository.findById(orderId);
        if (found.isEmpty() || found.get().getReservationMode() != ReservationMode.ASYNC) {
            return InboundResult.IGNORED;
        }
        Order order = found.get();
        if (order.getStatus() == OrderStatus.CONFIRMED) {
            return InboundResult.IGNORED;
        }
        SagaState sagaState = sagaRepository.findById(orderId).orElseThrow().getState();

        itemRepository.updateAllStatus(orderId, ReservationStatus.RESERVED);   // from here these items are held

        if (order.getStatus() == OrderStatus.PENDING && sagaState == SagaState.AWAITING_INVENTORY) {
            confirmOrder(orderId, claimRepository.findByOrderId(orderId).orElseThrow());
            return InboundResult.CONFIRMED;
        }

        // finished or being cancelled: reopen the saga so the durable compensation gives the stock back
        OrderSaga saga = sagaRepository.findById(orderId).orElseThrow();
        saga.reopenForRelease(now());
        sagaRepository.saveAndFlush(saga);
        return InboundResult.RELEASE_NEEDED;
    }

    /** InventoryRejected arrived: nothing is reserved. A pending, waiting order is CANCELLED with the reason. */
    @Transactional
    public InboundResult applyInventoryRejected(UUID eventId, UUID orderId, String reason) {

        if (processedEvents.existsById(eventId)) {
            return InboundResult.DUPLICATE;
        }
        processedEvents.saveAndFlush(new ProcessedInboundEvent(eventId, "inventory-event", now()));

        Optional<Order> found = orderRepository.findById(orderId);
        if (found.isEmpty() || found.get().getReservationMode() != ReservationMode.ASYNC
                || found.get().getStatus() != OrderStatus.PENDING) {
            return InboundResult.IGNORED;
        }
        if (sagaRepository.findById(orderId).orElseThrow().getState() != SagaState.AWAITING_INVENTORY) {
            return InboundResult.IGNORED;   // already being cancelled by recovery
        }

        cancelAwaitingOrder(orderId, reason == null || reason.isBlank() ? "RESERVATION_REJECTED" : reason);
        return InboundResult.CANCELLED;
    }

    /**
     * The waiting deadline passed and Inventory says it reserved the order: continue to confirmation.
     * @return false if the order is no longer pending and waiting (a reply got there first)
     */
    @Transactional
    public boolean confirmAwaiting(UUID orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        if (order.getStatus() != OrderStatus.PENDING
                || sagaRepository.findById(orderId).orElseThrow().getState() != SagaState.AWAITING_INVENTORY) {
            return false;
        }
        itemRepository.updateAllStatus(orderId, ReservationStatus.RESERVED);
        confirmOrder(orderId, claimRepository.findByOrderId(orderId).orElseThrow());
        return true;
    }

    /**
     * Inventory rejected the order, or holds nothing for it after the deadline: cancel with {@code reason}.
     * If a reservation shows up later, the late-reply path releases it.
     * @return false if the order is no longer pending and waiting
     */
    @Transactional
    public boolean cancelAwaiting(UUID orderId, String reason) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        if (order.getStatus() != OrderStatus.PENDING
                || sagaRepository.findById(orderId).orElseThrow().getState() != SagaState.AWAITING_INVENTORY) {
            return false;
        }
        cancelAwaitingOrder(orderId, reason);
        return true;
    }

    private void cancelAwaitingOrder(UUID orderId, String reason) {
        itemRepository.updateAllStatus(orderId, ReservationStatus.NOT_RESERVED);
        OrderSaga saga = sagaRepository.findById(orderId).orElseThrow();
        saga.beginCompensation(reason);
        sagaRepository.saveAndFlush(saga);
        cancelOrder(orderId);
    }

    /** The reply is overdue and Inventory could not be asked: retry later with backoff, bounded, then RECOVERY_FAILED. */
    @Transactional
    public SagaStateView scheduleAwaitRetry(UUID orderId, String error) {
        OrderSaga saga = sagaRepository.findById(orderId).orElseThrow();
        int failedAttempts = saga.getAttemptCount() + 1;
        if (failedAttempts >= recovery.maxAttempts()) {
            saga.recoveryFailed(error);
        } else {
            saga.awaitRetryAt(now().plus(recovery.backoffAfter(failedAttempts)), error);
        }
        saga = sagaRepository.saveAndFlush(saga);
        return new SagaStateView(saga.getState(), saga.getAttemptCount(), saga.getNextAttemptAt());
    }

    @Transactional(readOnly = true)
    public Optional<ReservationMode> modeOf(UUID orderId) {
        return orderRepository.findModeById(orderId);
    }

    @Transactional(readOnly = true)
    public OrderResponse readOrder(UUID orderId) {
        return OrderResponse.from(orderRepository.findWithItemsById(orderId).orElseThrow());
    }

    // ---- durable saga progress -------------------------------------------------------------

    /**
     * Records an item's reservation progress. Called BEFORE the remote call with RESERVING (or
     * RELEASING), so an unknown outcome is on record. Doubles as a heartbeat for the saga's owner.
     */
    @Transactional
    public void markItem(UUID orderId, UUID productId, ReservationStatus status) {
        itemRepository.updateStatus(orderId, productId, status);
        sagaRepository.findById(orderId).ifPresent(saga -> {
            if (saga.getState().isActive()) {
                saga.heartbeat(now().plus(recovery.lease()), now().plus(recovery.staleAfter()));
                sagaRepository.saveAndFlush(saga);
            }
        });
    }

    @Transactional(readOnly = true)
    public List<ItemProgress> findItems(UUID orderId) {
        return itemRepository.findProgress(orderId);
    }

    @Transactional
    public void beginCompensation(UUID orderId, String reason) {
        OrderSaga saga = sagaRepository.findById(orderId).orElseThrow();
        saga.beginCompensation(reason);
        sagaRepository.saveAndFlush(saga);
    }

    /**
     * Compensation did not finish. Schedules the next recovery attempt with exponential backoff, or
     * gives up (RECOVERY_FAILED) once the configured number of attempts is used up.
     *
     * @return the saga state after this call
     */
    @Transactional
    public SagaStateView scheduleRetry(UUID orderId, String error) {
        OrderSaga saga = sagaRepository.findById(orderId).orElseThrow();
        int failedAttempts = saga.getAttemptCount() + 1;
        if (failedAttempts >= recovery.maxAttempts()) {
            saga.recoveryFailed(error);
        } else {
            Duration wait = recovery.backoffAfter(failedAttempts);
            saga.retryAt(now().plus(wait), error);
        }
        saga = sagaRepository.saveAndFlush(saga);
        return new SagaStateView(saga.getState(), saga.getAttemptCount(), saga.getNextAttemptAt());
    }

    /** Atomically picks due sagas (row-locked, skipping ones another worker holds) and leases them. */
    @Transactional
    public List<UUID> claimDue() {
        List<OrderSaga> due = sagaRepository.lockDue(
                List.of(SagaState.RESERVING, SagaState.AWAITING_INVENTORY, SagaState.COMPENSATING),
                now(),
                org.springframework.data.domain.PageRequest.of(0, recovery.batchSize()));
        List<UUID> ids = new java.util.ArrayList<>();
        for (OrderSaga saga : due) {
            saga.lease(now().plus(recovery.lease()));
            ids.add(saga.getOrderId());
        }
        sagaRepository.saveAllAndFlush(due);
        return ids;
    }

    @Transactional(readOnly = true)
    public Optional<SagaStateView> sagaOf(UUID orderId) {
        return sagaRepository.findById(orderId)
                .map(s -> new SagaStateView(s.getState(), s.getAttemptCount(), s.getNextAttemptAt()));
    }

    /** Plain-value view of a saga, for decisions outside a transaction. */
    public record SagaStateView(
            com.mercury.order.model.SagaState state, int attemptCount, Instant nextAttemptAt) {
    }

    /**
     * Read through projections, not entities: these are polled and re-checked after failures,
     * so they must always reflect what is committed right now, even when the calling thread
     * has a long-lived persistence context (e.g. if open-in-view were ever switched on).
     */
    @Transactional(readOnly = true)
    public Optional<Claim> findClaim(String idempotencyKey) {
        return claimRepository.findClaimView(idempotencyKey)
                .map(v -> new Claim(v.status(), v.requestHash(), v.orderId(), v.responseSnapshot()));
    }

    @Transactional(readOnly = true)
    public OrderResponse readConfirmedResponse(UUID orderId) {
        return OrderResponse.from(orderRepository.findWithItemsById(orderId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public Optional<OrderStatus> statusOf(UUID orderId) {
        return orderRepository.findStatusById(orderId);
    }

    public OrderResponse readSnapshot(String snapshot) {
        return jsonMapper.readValue(snapshot, OrderResponse.class);
    }
}
