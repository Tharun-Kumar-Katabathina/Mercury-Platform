package com.mercury.order.service;

import com.mercury.order.config.RecoveryProperties;
import com.mercury.order.dto.OrderResponse;
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

    public OrderTransactions(
            OrderRepository orderRepository,
            OrderItemRepository itemRepository,
            OrderIdempotencyRecordRepository claimRepository,
            OrderSagaRepository sagaRepository,
            JsonMapper jsonMapper,
            RecoveryProperties recovery,
            Clock clock) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.claimRepository = claimRepository;
        this.sagaRepository = sagaRepository;
        this.jsonMapper = jsonMapper;
        this.recovery = recovery;
        this.clock = clock;
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

        return order.getId();
    }

    /** PENDING -> CONFIRMED and key IN_PROGRESS -> COMPLETED with the original response. */
    @Transactional
    public OrderResponse confirm(UUID orderId, String idempotencyKey) {

        Order order = orderRepository.findWithItemsById(orderId).orElseThrow();
        order.confirm();
        order = orderRepository.saveAndFlush(order);   // flush so updatedAt is final

        OrderResponse response = OrderResponse.from(order);

        OrderIdempotencyRecord claim = claimRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow();
        claim.complete(jsonMapper.writeValueAsString(response));
        claimRepository.saveAndFlush(claim);

        OrderSaga saga = sagaRepository.findById(orderId).orElseThrow();
        saga.confirmed();
        sagaRepository.saveAndFlush(saga);

        return response;
    }

    /**
     * PENDING -> CANCELLED, saga CANCELLED, and the key is freed so the client can retry the same
     * request and have it evaluated afresh (failures are never replayed).
     */
    @Transactional
    public void cancel(UUID orderId) {

        Order order = orderRepository.findById(orderId).orElseThrow();
        order.cancel();
        orderRepository.saveAndFlush(order);

        claimRepository.deleteByOrderId(orderId);

        OrderSaga saga = sagaRepository.findById(orderId).orElseThrow();
        saga.cancelled();
        sagaRepository.saveAndFlush(saga);
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
    public void beginCompensation(UUID orderId) {
        OrderSaga saga = sagaRepository.findById(orderId).orElseThrow();
        saga.beginCompensation();
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
                List.of(com.mercury.order.model.SagaState.RESERVING, com.mercury.order.model.SagaState.COMPENSATING),
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
