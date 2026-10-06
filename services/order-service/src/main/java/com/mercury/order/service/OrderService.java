package com.mercury.order.service;

import com.mercury.order.config.ReservationProperties;
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
import com.mercury.order.model.ReservationMode;
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
    private final OrderSagaService sagaService;
    private final Duration idempotencyWaitTimeout;
    private final ReservationProperties reservation;

    public OrderService(
            OrderRepository orderRepository,
            OrderPlanner planner,
            OrderTransactions transactions,
            OrderSagaService sagaService,
            @Value("${order.idempotency.wait-timeout:5s}") Duration idempotencyWaitTimeout,
            ReservationProperties reservation) {
        this.orderRepository = orderRepository;
        this.planner = planner;
        this.transactions = transactions;
        this.sagaService = sagaService;
        this.idempotencyWaitTimeout = idempotencyWaitTimeout;
        this.reservation = reservation;
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

            boolean async = reservation.mode() == ReservationMode.ASYNC;

            UUID orderId;
            try {
                orderId = async
                        ? transactions.createPendingAsync(
                                idempotencyKey, requestHash, draft, reservation.asyncDeadline())
                        : transactions.createPending(idempotencyKey, requestHash, draft);
            } catch (DataIntegrityViolationException e) {
                if (transactions.findClaim(idempotencyKey).isPresent()) {
                    continue;   // another request claimed the key first; resolve it next round
                }
                throw e;
            }

            if (async) {
                // nothing more to do on the request path: Inventory's reply (or the recovery deadline) finishes it
                sagaService.orderAccepted(orderId, draft);
                return new OrderCreationResult(
                        transactions.readOrder(orderId), false, OrderCreationResult.Kind.ACCEPTED);
            }
            return sagaService.execute(orderId, idempotencyKey, draft);
        }

        throw new OrderConflictException(
                "Another request with this Idempotency-Key is being processed; retry shortly");
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
            boolean async = transactions.modeOf(claim.get().orderId())
                    .map(mode -> mode == ReservationMode.ASYNC).orElse(false);

            if (claim.get().status() == ClaimStatus.COMPLETED) {
                return Optional.of(new OrderCreationResult(
                        transactions.readSnapshot(claim.get().snapshot()), true,
                        async ? OrderCreationResult.Kind.OK : OrderCreationResult.Kind.CREATED));
            }
            if (async) {
                // an ASYNC order can legitimately wait for a long time: answer with where it is now, never block
                var order = transactions.readOrder(claim.get().orderId());
                return Optional.of(new OrderCreationResult(order, true,
                        order.status() == OrderStatus.PENDING
                                ? OrderCreationResult.Kind.ACCEPTED : OrderCreationResult.Kind.OK));
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
