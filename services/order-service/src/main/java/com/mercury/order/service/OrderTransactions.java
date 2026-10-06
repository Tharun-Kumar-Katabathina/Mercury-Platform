package com.mercury.order.service;

import com.mercury.order.dto.OrderResponse;
import com.mercury.order.model.ClaimStatus;
import com.mercury.order.model.Order;
import com.mercury.order.model.OrderIdempotencyRecord;
import com.mercury.order.model.OrderItem;
import com.mercury.order.model.OrderStatus;
import com.mercury.order.repository.OrderIdempotencyRecordRepository;
import com.mercury.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

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
    private final OrderIdempotencyRecordRepository claimRepository;
    private final JsonMapper jsonMapper;

    public OrderTransactions(
            OrderRepository orderRepository,
            OrderIdempotencyRecordRepository claimRepository,
            JsonMapper jsonMapper) {
        this.orderRepository = orderRepository;
        this.claimRepository = claimRepository;
        this.jsonMapper = jsonMapper;
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

        return response;
    }

    /**
     * PENDING -> CANCELLED, and frees the key so the client can retry the same request and have
     * it evaluated afresh (failures are never replayed).
     */
    @Transactional
    public void cancel(UUID orderId, String idempotencyKey) {

        Order order = orderRepository.findById(orderId).orElseThrow();
        order.cancel();
        orderRepository.saveAndFlush(order);

        claimRepository.deleteByIdempotencyKey(idempotencyKey);
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
    public Optional<OrderStatus> statusOf(UUID orderId) {
        return orderRepository.findStatusById(orderId);
    }

    public OrderResponse readSnapshot(String snapshot) {
        return jsonMapper.readValue(snapshot, OrderResponse.class);
    }
}
