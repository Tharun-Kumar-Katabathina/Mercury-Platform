package com.mercury.order.service;

import com.mercury.order.client.InventoryClient;
import com.mercury.order.dto.CreateOrderItemRequest;
import com.mercury.order.dto.CreateOrderRequest;
import com.mercury.order.dto.OrderResponse;
import com.mercury.order.exception.IdempotencyKeyMismatchException;
import com.mercury.order.exception.InvalidOrderException;
import com.mercury.order.exception.MissingIdempotencyKeyException;
import com.mercury.order.exception.OrderConflictException;
import com.mercury.order.exception.OrderNotFoundException;
import com.mercury.order.exception.OrderProcessingException;
import com.mercury.order.model.ClaimStatus;
import com.mercury.order.model.OrderStatus;
import com.mercury.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates orders across three systems that cannot share one transaction (Order DB, Product,
 * Inventory), as an orchestrated saga:
 *
 * <pre>
 *   validate request -> read products (no side effects) -> save PENDING order + claim key
 *     -> reserve stock, item by item        (any failure: release what was reserved, CANCELLED)
 *     -> mark CONFIRMED + store response    (failure:      release everything,       CANCELLED)
 * </pre>
 *
 * Nothing here is @Transactional: each database step is its own short local transaction in
 * {@link OrderTransactions}, and no database transaction is ever open during a remote call.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final int MAX_ROUNDS = 3;
    private static final long POLL_MILLIS = 25;

    private final OrderRepository orderRepository;
    private final OrderPlanner planner;
    private final OrderTransactions transactions;
    private final InventoryClient inventoryClient;
    private final Duration idempotencyWaitTimeout;

    public OrderService(
            OrderRepository orderRepository,
            OrderPlanner planner,
            OrderTransactions transactions,
            InventoryClient inventoryClient,
            @Value("${order.idempotency.wait-timeout:5s}") Duration idempotencyWaitTimeout) {
        this.orderRepository = orderRepository;
        this.planner = planner;
        this.transactions = transactions;
        this.inventoryClient = inventoryClient;
        this.idempotencyWaitTimeout = idempotencyWaitTimeout;
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID orderId) {

        return orderRepository.findWithItemsById(orderId)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    public OrderCreationResult createOrder(String idempotencyKey, CreateOrderRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }
        if (idempotencyKey.length() > 255) {
            throw new InvalidOrderException("Idempotency-Key must be at most 255 characters");
        }

        List<CreateOrderItemRequest> items = planner.validate(request);
        String requestHash = requestHash(items);

        for (int round = 0; round < MAX_ROUNDS; round++) {

            Optional<OrderCreationResult> existing = resolveExisting(idempotencyKey, requestHash);
            if (existing.isPresent()) {
                log.info("Replaying order {} for key {}",
                        existing.get().order().id(), fingerprint(idempotencyKey));
                return existing.get();
            }

            // read-only: nothing is saved or reserved until every product is known to be valid
            OrderDraft draft = planner.draft(items);

            UUID orderId;
            try {
                orderId = transactions.createPending(idempotencyKey, requestHash, draft);
            } catch (DataIntegrityViolationException e) {
                if (transactions.findClaim(idempotencyKey).isPresent()) {
                    continue;   // another request claimed the key first; resolve it next round
                }
                throw e;
            }

            return process(orderId, idempotencyKey, draft);
        }

        throw new OrderConflictException(
                "Another request with this Idempotency-Key is being processed; retry shortly");
    }

    // ---- the saga ------------------------------------------------------------------------

    private OrderCreationResult process(UUID orderId, String idempotencyKey, OrderDraft draft) {

        log.info("Order {} PENDING for key {}: {} item(s), total {}",
                orderId, fingerprint(idempotencyKey), draft.items().size(), draft.totalAmount());

        List<OrderDraft.Item> reserved = new ArrayList<>();
        try {
            for (OrderDraft.Item item : draft.items()) {
                inventoryClient.reserve(
                        item.productId(), item.quantity(), reservationKey(orderId, item.productId()));
                reserved.add(item);
                log.info("Order {} reserved {} x product {}", orderId, item.quantity(), item.productId());
            }
        } catch (RuntimeException e) {
            log.warn("Order {} could not reserve stock ({}); compensating", orderId, e.getMessage());
            compensate(orderId, idempotencyKey, reserved);
            throw e;
        }

        try {
            OrderResponse confirmed = transactions.confirm(orderId, idempotencyKey);
            log.info("Order {} CONFIRMED", orderId);
            return new OrderCreationResult(confirmed, false);
        } catch (RuntimeException e) {
            return recoverFromFailedConfirm(orderId, idempotencyKey, reserved, e);
        }
    }

    /**
     * Stock is reserved but the order could not be marked CONFIRMED. The commit may in fact have
     * succeeded before the error surfaced, so look before releasing: giving back stock for an
     * order that really is CONFIRMED would oversell it.
     */
    private OrderCreationResult recoverFromFailedConfirm(
            UUID orderId, String idempotencyKey, List<OrderDraft.Item> reserved, RuntimeException cause) {

        Optional<OrderStatus> status;
        try {
            status = transactions.statusOf(orderId);
        } catch (RuntimeException unreadable) {
            log.error("Order {} could not be confirmed and its state cannot be read; "
                    + "leaving reserved stock untouched for reconciliation", orderId, cause);
            throw new OrderProcessingException(
                    "Order could not be completed and needs reconciliation", cause);
        }

        if (status.isPresent() && status.get() == OrderStatus.CONFIRMED) {
            log.warn("Order {} is CONFIRMED despite error on confirm; returning it", orderId);
            return resolveExisting(idempotencyKey, null)
                    .map(stored -> new OrderCreationResult(stored.order(), false))
                    .orElseThrow(() -> new OrderProcessingException(
                            "Order confirmed but its result is unavailable", cause));
        }

        log.error("Order {} could not be CONFIRMED; compensating", orderId, cause);
        compensate(orderId, idempotencyKey, reserved);
        throw new OrderProcessingException(
                "Order could not be completed; reserved stock was released", cause);
    }

    /**
     * Gives back every reservation this order made (newest first), then CANCELS the order.
     * Release is idempotent under a deterministic key, so this is safe to repeat. If any release
     * fails the order stays PENDING on purpose: CANCELLED must only mean "fully compensated".
     */
    private void compensate(UUID orderId, String idempotencyKey, List<OrderDraft.Item> reserved) {

        List<OrderDraft.Item> newestFirst = new ArrayList<>(reserved);
        Collections.reverse(newestFirst);

        boolean allReleased = true;
        for (OrderDraft.Item item : newestFirst) {
            try {
                inventoryClient.release(
                        item.productId(), item.quantity(), releaseKey(orderId, item.productId()));
                log.info("Order {} released {} x product {}", orderId, item.quantity(), item.productId());
            } catch (RuntimeException e) {
                allReleased = false;
                log.error("Order {} FAILED to release {} x product {}", orderId, item.quantity(),
                        item.productId(), e);
            }
        }

        if (!allReleased) {
            log.error("Order {} left PENDING: compensation incomplete, needs reconciliation", orderId);
            return;
        }

        try {
            transactions.cancel(orderId, idempotencyKey);
            log.info("Order {} CANCELLED", orderId);
        } catch (RuntimeException e) {
            log.error("Order {} stock released but marking it CANCELLED failed", orderId, e);
        }
    }

    // ---- idempotency ---------------------------------------------------------------------

    /**
     * Empty: nobody holds this key (or its holder failed and freed it), go ahead and create.
     * Otherwise the stored result; waits briefly if another request is still creating the order.
     * {@code requestHash == null} skips the payload check (used after our own confirm).
     */
    private Optional<OrderCreationResult> resolveExisting(String idempotencyKey, String requestHash) {

        long deadline = System.nanoTime() + idempotencyWaitTimeout.toNanos();

        while (true) {
            Optional<OrderTransactions.Claim> claim = transactions.findClaim(idempotencyKey);
            if (claim.isEmpty()) {
                return Optional.empty();
            }
            if (requestHash != null && !claim.get().requestHash().equals(requestHash)) {
                throw new IdempotencyKeyMismatchException();
            }
            if (claim.get().status() == ClaimStatus.COMPLETED) {
                return Optional.of(new OrderCreationResult(
                        transactions.readSnapshot(claim.get().snapshot()), true));
            }
            if (System.nanoTime() >= deadline) {
                throw new OrderConflictException(
                        "Another request with this Idempotency-Key is still being processed; retry shortly");
            }
            sleep(POLL_MILLIS);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for an in-flight order", e);
        }
    }

    // ---- keys and hashing ----------------------------------------------------------------

    /** One reservation per (order, product), the same on every retry of that step. */
    static String reservationKey(UUID orderId, UUID productId) {
        return "order:" + orderId + ":product:" + productId;
    }

    static String releaseKey(UUID orderId, UUID productId) {
        return reservationKey(orderId, productId) + ":release";
    }

    /** Hash of the canonical request, so item order in the payload does not matter. */
    private static String requestHash(List<CreateOrderItemRequest> canonicalItems) {
        StringBuilder canonical = new StringBuilder();
        for (CreateOrderItemRequest item : canonicalItems) {
            canonical.append(item.productId()).append(':').append(item.quantity()).append(';');
        }
        return sha256(canonical.toString());
    }

    /** Safe identifier for logs: never log the client's raw key. */
    private static String fingerprint(String idempotencyKey) {
        return sha256(idempotencyKey).substring(0, 8);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
