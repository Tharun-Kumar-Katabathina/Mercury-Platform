package com.mercury.order.service;

import com.mercury.order.client.InventoryClient;
import com.mercury.order.dto.OrderResponse;
import com.mercury.order.dto.ReservationSnapshot;
import com.mercury.order.exception.InventoryServiceException;
import com.mercury.order.exception.OrderProcessingException;
import com.mercury.order.model.OrderStatus;
import com.mercury.order.model.ReservationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The order saga: reserve stock item by item, confirm, and undo everything if it does not complete.
 *
 * Every remote call is preceded by a durable record of the intent (item RESERVING / RELEASING), so
 * whatever happens next (a timeout, a crash) leaves enough in the database for recovery to finish.
 *
 * Policy: an order that does not reach CONFIRMED always ends CANCELLED with all stock given back.
 * That matches what the client was told (an error, or nothing at all). It is deterministic: any
 * state the saga can be left in has exactly one way forward, see {@link #compensate}.
 *
 * Nothing here is @Transactional; each database step is its own short transaction in
 * {@link OrderTransactions} and none is open during a remote call.
 */
@Service
public class OrderSagaService {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaService.class);

    private final OrderTransactions transactions;
    private final InventoryClient inventoryClient;
    private final SagaMetrics metrics;

    public OrderSagaService(
            OrderTransactions transactions, InventoryClient inventoryClient, SagaMetrics metrics) {
        this.transactions = transactions;
        this.inventoryClient = inventoryClient;
        this.metrics = metrics;
    }

    // ---- the request path ----------------------------------------------------------------

    public OrderCreationResult execute(UUID orderId, String idempotencyKey, OrderDraft draft) {

        metrics.orderCreated();
        log.info("saga orderId={} state=RESERVING items={} total={}",
                orderId, draft.items().size(), draft.totalAmount());

        try {
            for (OrderDraft.Item item : draft.items()) {
                reserve(orderId, item);
            }
        } catch (RuntimeException e) {
            log.warn("saga orderId={} operation=RESERVE result=FAILED reason={}; compensating",
                    orderId, e.getMessage());
            compensateOrScheduleRetry(orderId, reasonFor(e), e.getMessage());
            throw e;
        }

        try {
            OrderResponse confirmed = transactions.confirm(orderId, idempotencyKey);
            metrics.orderConfirmed();
            log.info("saga orderId={} state=CONFIRMED", orderId);
            return new OrderCreationResult(confirmed, false);
        } catch (RuntimeException e) {
            return recoverFromFailedConfirm(orderId, e);
        }
    }

    /** ASYNC: the order was saved and the reservation command is in the outbox; nothing is reserved yet. */
    public void orderAccepted(UUID orderId, OrderDraft draft) {
        metrics.orderCreated();
        log.info("saga orderId={} state=AWAITING_INVENTORY items={} total={}",
                orderId, draft.items().size(), draft.totalAmount());
    }

    /**
     * ASYNC, the reply is overdue: ask Inventory what it decided and finish the order accordingly.
     * RESERVED -> CONFIRMED, REJECTED -> CANCELLED with Inventory's reason, nothing decided -> CANCELLED with
     * RESERVATION_TIMEOUT (if the reservation shows up later, the late-reply path releases it).
     *
     * @return true when the order reached a final state (or a reply finished it first), false when
     *         Inventory could not be asked and a retry is needed
     */
    public boolean resolveAwaiting(UUID orderId) {

        Optional<com.mercury.order.dto.OrderReservationSnapshot> decision;
        long started = System.nanoTime();
        try {
            decision = inventoryClient.findOrderReservation(orderId);
        } catch (RuntimeException e) {
            log.warn("saga orderId={} operation=ORDER_LOOKUP result=FAILED reason={} durationMs={}",
                    orderId, e.getMessage(), (System.nanoTime() - started) / 1_000_000);
            return false;
        }

        if (decision.isPresent() && decision.get().reserved()) {
            if (transactions.confirmAwaiting(orderId)) {
                metrics.orderConfirmed();
                log.info("saga orderId={} state=CONFIRMED (resolved by lookup)", orderId);
            }
            return true;
        }

        String reason = decision.map(d -> d.reason() == null ? "RESERVATION_REJECTED" : d.reason())
                .orElse("RESERVATION_TIMEOUT");
        if (transactions.cancelAwaiting(orderId, reason)) {
            metrics.orderCancelled();
            log.info("saga orderId={} state=CANCELLED reason={} (resolved by lookup)", orderId, reason);
        }
        return true;
    }

    private void reserve(UUID orderId, OrderDraft.Item item) {

        String key = SagaKeys.reservation(orderId, item.productId());

        transactions.markItem(orderId, item.productId(), ReservationStatus.RESERVING);   // intent first
        long started = System.nanoTime();
        try {
            inventoryClient.reserve(item.productId(), item.quantity(), key);
        } catch (InventoryServiceException e) {
            logCallFailure(orderId, "RESERVE", item.productId(), e, started);
            if (isDefinitive(e)) {
                // Inventory rejected it: certainly nothing reserved. (If this write fails the item
                // simply stays RESERVING and is resolved by asking Inventory later.)
                try {
                    transactions.markItem(orderId, item.productId(), ReservationStatus.NOT_RESERVED);
                } catch (RuntimeException ignored) {
                    // resolved later
                }
            } else {
                metrics.inventoryTimeout("reserve");
            }
            throw e;
        }
        logCall(orderId, "RESERVE", item.productId(), "OK", started);
        transactions.markItem(orderId, item.productId(), ReservationStatus.RESERVED);
    }

    /**
     * Stock is reserved but the order could not be marked CONFIRMED. The commit may in fact have
     * succeeded before the error surfaced, so look before giving stock back: releasing for an order
     * that really is CONFIRMED would oversell it.
     */
    private OrderCreationResult recoverFromFailedConfirm(UUID orderId, RuntimeException cause) {

        Optional<OrderStatus> status;
        try {
            status = transactions.statusOf(orderId);
        } catch (RuntimeException unreadable) {
            log.error("saga orderId={} confirm failed and its state cannot be read; "
                    + "leaving reserved stock untouched for recovery", orderId, cause);
            throw new OrderProcessingException(
                    "Order could not be completed and needs reconciliation", cause);
        }

        if (status.isPresent() && status.get() == OrderStatus.CONFIRMED) {
            log.warn("saga orderId={} is CONFIRMED despite an error on confirm; returning it", orderId);
            return new OrderCreationResult(transactions.readConfirmedResponse(orderId), false);
        }

        log.error("saga orderId={} could not be CONFIRMED; compensating", orderId, cause);
        compensateOrScheduleRetry(orderId, "CONFIRMATION_FAILED", "confirm failed: " + cause.getMessage());
        throw new OrderProcessingException(
                "Order could not be completed; reserved stock was released", cause);
    }

    // ---- compensation (shared by the request path and recovery) -----------------------------

    /** One compensation pass now; if it cannot finish, schedule a durable retry. */
    private void compensateOrScheduleRetry(UUID orderId, String reasonCode, String detail) {
        boolean finished;
        try {
            finished = compensate(orderId, reasonCode);
        } catch (RuntimeException e) {
            log.error("saga orderId={} compensation pass crashed", orderId, e);
            finished = false;
        }
        if (!finished) {
            OrderTransactions.SagaStateView view = transactions.scheduleRetry(orderId, detail);
            log.warn("saga orderId={} state={} attempt={} nextAttemptAt={} (compensation incomplete)",
                    orderId, view.state(), view.attemptCount(), view.nextAttemptAt());
        }
    }

    /**
     * One pass over the order's items: resolve any unknown reservation, give back every held
     * reservation, then cancel the order. Safe to run any number of times and from either the
     * request path or recovery: it only reads durable state, and Inventory's reserve, lookup and
     * release are idempotent by key.
     *
     * @return true when the order is now CANCELLED, false when something is still outstanding
     */
    public boolean compensate(UUID orderId, String reasonCode) {

        transactions.beginCompensation(orderId, reasonCode);

        List<ItemProgress> items = new ArrayList<>(transactions.findItems(orderId));
        Collections.reverse(items);   // newest reservation first

        boolean allSettled = true;
        for (ItemProgress item : items) {
            allSettled &= settle(orderId, item);
        }

        if (!allSettled) {
            return false;
        }
        try {
            transactions.cancel(orderId);
        } catch (com.mercury.order.exception.StockStillHeldException stillHeld) {
            // a reservation turned up while this pass ran: not finished, the next pass releases it
            log.warn("saga orderId={} not cancelled yet: {}", orderId, stillHeld.getMessage());
            return false;
        }
        metrics.orderCancelled();
        log.info("saga orderId={} state=CANCELLED", orderId);
        return true;
    }

    /** Brings one item to a state where Inventory holds nothing for it. */
    private boolean settle(UUID orderId, ItemProgress item) {

        ReservationStatus status = item.status();

        if (status == ReservationStatus.RESERVING) {
            // outcome unknown: settle the key at Inventory. It reports the reservation if one exists, otherwise it
            // fences the key so a reserve still in flight (or retried) can never succeed after this cancel
            Optional<ReservationSnapshot> found;
            long started = System.nanoTime();
            try {
                found = inventoryClient.fenceReservation(
                        item.productId(), SagaKeys.reservation(orderId, item.productId()));
            } catch (RuntimeException e) {
                logCallFailure(orderId, "FENCE", item.productId(), e, started);
                return false;
            }
            logCall(orderId, "FENCE", item.productId(), found.isPresent() ? "FOUND" : "FENCED", started);
            if (found.isEmpty()) {
                transactions.markItem(orderId, item.productId(), ReservationStatus.NOT_RESERVED);
                return true;
            }
            transactions.markItem(orderId, item.productId(), ReservationStatus.RESERVED);
            status = ReservationStatus.RESERVED;
        }

        if (status == ReservationStatus.RESERVED || status == ReservationStatus.RELEASING) {
            transactions.markItem(orderId, item.productId(), ReservationStatus.RELEASING);
            long started = System.nanoTime();
            try {
                inventoryClient.release(
                        item.productId(), item.quantity(), SagaKeys.release(orderId, item.productId()));
            } catch (RuntimeException e) {
                metrics.compensationFailed();
                if (e instanceof InventoryServiceException ise && !isDefinitive(ise)) {
                    metrics.inventoryTimeout("release");
                }
                logCallFailure(orderId, "RELEASE", item.productId(), e, started);
                return false;
            }
            logCall(orderId, "RELEASE", item.productId(), "OK", started);
            transactions.markItem(orderId, item.productId(), ReservationStatus.RELEASED);
            metrics.compensationSucceeded();
        }

        return true;   // NOT_STARTED, NOT_RESERVED, RELEASED: nothing held
    }

    /** A short, stable code for why an order is being cancelled; carried by the OrderCancelled event. */
    static String reasonFor(RuntimeException e) {
        if (e instanceof InventoryServiceException ise) {
            String body = ise.getResponseBody() == null ? "" : ise.getResponseBody();
            if (body.contains("INSUFFICIENT_STOCK")) {
                return "INSUFFICIENT_STOCK";
            }
            if (body.contains("INVENTORY_NOT_FOUND")) {
                return "INVENTORY_NOT_FOUND";
            }
            return ise.getStatus().is4xxClientError() ? "RESERVATION_REJECTED" : "INVENTORY_UNAVAILABLE";
        }
        return "ORDER_PROCESSING_FAILED";
    }

    /**
     * Certain that Inventory applied nothing: it understood and refused (4xx), or the request was
     * never sent (circuit open, bulkhead full). A 5xx or no answer at all leaves the outcome unknown.
     */
    private static boolean isDefinitive(InventoryServiceException e) {
        return e.getStatus().is4xxClientError() || e.wasNeverSent();
    }

    private void logCall(UUID orderId, String operation, UUID productId, String result, long startedNanos) {
        log.info("saga orderId={} operation={} productId={} result={} durationMs={}",
                orderId, operation, productId, result, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private void logCallFailure(
            UUID orderId, String operation, UUID productId, RuntimeException e, long startedNanos) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        log.warn("saga orderId={} operation={} productId={} result=FAILED reason={}: {} durationMs={}",
                orderId, operation, productId, cause.getClass().getSimpleName(), cause.getMessage(),
                (System.nanoTime() - startedNanos) / 1_000_000);
    }
}
