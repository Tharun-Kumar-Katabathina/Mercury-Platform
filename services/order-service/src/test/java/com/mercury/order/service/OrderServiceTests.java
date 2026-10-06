package com.mercury.order.service;

import com.mercury.order.client.InventoryClient;
import com.mercury.order.client.ProductClient;
import com.mercury.order.dto.CreateOrderItemRequest;
import com.mercury.order.dto.CreateOrderRequest;
import com.mercury.order.dto.InventoryOperationResult;
import com.mercury.order.dto.OrderResponse;
import com.mercury.order.dto.ProductDetails;
import com.mercury.order.exception.IdempotencyKeyMismatchException;
import com.mercury.order.exception.InvalidOrderException;
import com.mercury.order.exception.InventoryServiceException;
import com.mercury.order.exception.MissingIdempotencyKeyException;
import com.mercury.order.exception.OrderProcessingException;
import com.mercury.order.exception.ProductServiceException;
import com.mercury.order.model.Order;
import com.mercury.order.model.OrderStatus;
import com.mercury.order.repository.OrderIdempotencyRecordRepository;
import com.mercury.order.repository.OrderRepository;
import com.mercury.order.service.ConcurrentRunner.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
 * OrderService against a real (H2) database with Product and Inventory mocked at the client
 * boundary, so each distributed failure can be forced exactly.
 */
@SpringBootTest
class OrderServiceTests {

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final InventoryOperationResult OK = new InventoryOperationResult(false);

    @BeforeEach
    void inventoryAcceptsEverythingByDefault() {
        when(inventoryClient.reserve(any(), anyInt(), anyString())).thenReturn(OK);
        when(inventoryClient.release(any(), anyInt(), anyString())).thenReturn(OK);
    }

    // ---- helpers -------------------------------------------------------------------------

    private UUID product(String name, String price) {
        UUID id = UUID.randomUUID();
        when(productClient.getProduct(id))
                .thenReturn(new ProductDetails(id, name, "SKU-" + name, new BigDecimal(price)));
        return id;
    }

    private static CreateOrderRequest order(CreateOrderItemRequest... items) {
        return new CreateOrderRequest(List.of(items));
    }

    private static CreateOrderItemRequest item(UUID productId, Integer quantity) {
        return new CreateOrderItemRequest(productId, quantity);
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private static InventoryServiceException inventoryError(HttpStatus status, String code) {
        return new InventoryServiceException(status, "{\"error\":\"" + code + "\"}");
    }

    private OrderStatus statusOfOnlyOrderFor(UUID productId) {
        List<Order> orders = orderRepository.findAll().stream()
                .filter(o -> orderRepository.findWithItemsById(o.getId()).orElseThrow().getItems()
                        .stream().anyMatch(i -> i.getProductId().equals(productId)))
                .toList();
        assertThat(orders).hasSize(1);
        return orders.get(0).getStatus();
    }

    private long ordersFor(UUID productId) {
        return orderRepository.findAll().stream()
                .filter(o -> orderRepository.findWithItemsById(o.getId()).orElseThrow().getItems()
                        .stream().anyMatch(i -> i.getProductId().equals(productId)))
                .count();
    }

    // ---- success -------------------------------------------------------------------------

    @Test
    void createsAConfirmedOrderWithSnapshotAndTotal() {
        UUID laptop = product("Laptop", "2499.00");
        UUID mouse = product("Mouse", "19.99");

        OrderCreationResult result = orderService.createOrder(
                key(), order(item(laptop, 2), item(mouse, 3)));

        OrderResponse created = result.order();
        assertThat(result.replayed()).isFalse();
        assertThat(created.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(created.totalAmount()).isEqualByComparingTo("5057.97");   // 2*2499.00 + 3*19.99
        assertThat(created.items()).hasSize(2);
        assertThat(created.items()).anySatisfy(i -> {
            assertThat(i.productId()).isEqualTo(laptop);
            assertThat(i.productName()).isEqualTo("Laptop");
            assertThat(i.sku()).isEqualTo("SKU-Laptop");
            assertThat(i.unitPrice()).isEqualByComparingTo("2499.00");
            assertThat(i.quantity()).isEqualTo(2);
            assertThat(i.subtotal()).isEqualByComparingTo("4998.00");
        });

        // persisted, and retrievable as the same order
        OrderResponse stored = orderService.getOrder(created.id());
        assertThat(stored.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(stored.totalAmount()).isEqualByComparingTo("5057.97");
    }

    @Test
    void reservesEachItemUnderADeterministicKeyForThatOrderAndProduct() {
        UUID a = product("A", "1.00");
        UUID b = product("B", "2.00");

        OrderResponse created = orderService.createOrder(key(), order(item(b, 1), item(a, 4))).order();

        ArgumentCaptor<UUID> products = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Integer> quantities = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(inventoryClient, times(2))
                .reserve(products.capture(), quantities.capture(), keys.capture());

        for (int i = 0; i < 2; i++) {
            assertThat(keys.getAllValues().get(i))
                    .isEqualTo("order:" + created.id() + ":product:" + products.getAllValues().get(i));
        }
        assertThat(products.getAllValues()).isSorted();   // canonical order, always
        verify(inventoryClient, never()).release(any(), anyInt(), anyString());
    }

    // ---- validation: nothing is called, nothing is saved ---------------------------------

    @Test
    void rejectsAnInvalidRequestBeforeTouchingAnyOtherSystem() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> orderService.createOrder(key(), new CreateOrderRequest(List.of())))
                .isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> orderService.createOrder(key(), order(item(id, 0))))
                .isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> orderService.createOrder(key(), order(item(id, -1))))
                .isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> orderService.createOrder(key(), order(item(id, 1), item(id, 2))))
                .isInstanceOf(InvalidOrderException.class);

        verifyNoInteractions(productClient, inventoryClient);
    }

    @Test
    void requiresAnIdempotencyKey() {
        UUID id = product("A", "1.00");

        assertThatThrownBy(() -> orderService.createOrder(null, order(item(id, 1))))
                .isInstanceOf(MissingIdempotencyKeyException.class);
        assertThatThrownBy(() -> orderService.createOrder("  ", order(item(id, 1))))
                .isInstanceOf(MissingIdempotencyKeyException.class);

        verifyNoInteractions(inventoryClient);
    }

    @Test
    void unknownProductReservesNothingAndSavesNothing() {
        UUID good = product("Good", "5.00");
        UUID unknown = UUID.randomUUID();
        when(productClient.getProduct(unknown)).thenThrow(new ProductServiceException(
                HttpStatus.NOT_FOUND, "{\"error\":\"PRODUCT_NOT_FOUND\"}"));

        assertThatThrownBy(() -> orderService.createOrder(key(), order(item(good, 1), item(unknown, 1))))
                .isInstanceOfSatisfying(ProductServiceException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(404));

        verifyNoInteractions(inventoryClient);
        assertThat(ordersFor(good)).isZero();
    }

    // ---- reservation failures: compensate, CANCELLED, key freed ---------------------------

    @Test
    void insufficientStockOnTheFirstItemCancelsTheOrderWithNothingToRelease() {
        UUID a = product("A", "1.00");
        when(inventoryClient.reserve(eq(a), anyInt(), anyString()))
                .thenThrow(inventoryError(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK"));
        String key = key();

        assertThatThrownBy(() -> orderService.createOrder(key, order(item(a, 5))))
                .isInstanceOfSatisfying(InventoryServiceException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(409));

        assertThat(statusOfOnlyOrderFor(a)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(claimRepository.findByIdempotencyKey(key)).isEmpty();
        verify(inventoryClient, never()).release(any(), anyInt(), anyString());
    }

    @Test
    void failureOnTheSecondItemReleasesTheFirstAndCancelsTheOrder() {
        UUID a = product("A", "1.00");
        UUID b = product("B", "1.00");
        UUID first = a.compareTo(b) < 0 ? a : b;    // reserved first (canonical order)
        UUID second = first.equals(a) ? b : a;
        when(inventoryClient.reserve(eq(second), anyInt(), anyString()))
                .thenThrow(inventoryError(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK"));
        String key = key();

        assertThatThrownBy(() -> orderService.createOrder(key, order(item(a, 2), item(b, 3))))
                .isInstanceOf(InventoryServiceException.class);

        // exactly the item that WAS reserved is given back, under a deterministic release key
        ArgumentCaptor<String> releaseKey = ArgumentCaptor.forClass(String.class);
        verify(inventoryClient, times(1)).release(eq(first), anyInt(), releaseKey.capture());
        verify(inventoryClient, never()).release(eq(second), anyInt(), anyString());
        assertThat(releaseKey.getValue()).startsWith("order:").endsWith(":product:" + first + ":release");

        assertThat(statusOfOnlyOrderFor(first)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(claimRepository.findByIdempotencyKey(key)).isEmpty();
    }

    @Test
    void inventoryUnavailableCancelsTheOrder() {
        UUID a = product("A", "1.00");
        when(inventoryClient.reserve(any(), anyInt(), anyString()))
                .thenThrow(new InventoryServiceException(HttpStatus.SERVICE_UNAVAILABLE, null));

        assertThatThrownBy(() -> orderService.createOrder(key(), order(item(a, 1))))
                .isInstanceOfSatisfying(InventoryServiceException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(503));

        assertThat(statusOfOnlyOrderFor(a)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void aFailedRequestIsNotReplayedAndTheSameKeyIsEvaluatedAgain() {
        UUID a = product("A", "10.00");
        String key = key();
        when(inventoryClient.reserve(any(), anyInt(), anyString()))
                .thenThrow(inventoryError(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK"))
                .thenReturn(OK);

        assertThatThrownBy(() -> orderService.createOrder(key, order(item(a, 1))))
                .isInstanceOf(InventoryServiceException.class);
        OrderCreationResult retry = orderService.createOrder(key, order(item(a, 1)));

        assertThat(retry.replayed()).isFalse();
        assertThat(retry.order().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(ordersFor(a)).isEqualTo(2);   // the cancelled attempt stays as an audit record
    }

    // ---- idempotency ---------------------------------------------------------------------

    @Test
    void sameKeyReturnsTheOriginalOrderAndReservesNothingMore() {
        UUID a = product("A", "10.00");
        UUID b = product("B", "20.00");
        String key = key();

        OrderCreationResult first = orderService.createOrder(key, order(item(a, 1), item(b, 2)));
        OrderCreationResult second = orderService.createOrder(key, order(item(a, 1), item(b, 2)));
        OrderCreationResult third = orderService.createOrder(key, order(item(a, 1), item(b, 2)));

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(third.replayed()).isTrue();
        assertThat(second.order()).isEqualTo(first.order());
        assertThat(third.order().id()).isEqualTo(first.order().id());

        verify(inventoryClient, times(2)).reserve(any(), anyInt(), anyString());   // 2 items, once
        verify(productClient, times(1)).getProduct(a);                             // replays skip Product
        assertThat(ordersFor(a)).isEqualTo(1);
    }

    @Test
    void itemOrderInThePayloadDoesNotMatterForIdempotency() {
        UUID a = product("A", "10.00");
        UUID b = product("B", "20.00");
        String key = key();

        OrderCreationResult first = orderService.createOrder(key, order(item(a, 1), item(b, 2)));
        OrderCreationResult reordered = orderService.createOrder(key, order(item(b, 2), item(a, 1)));

        assertThat(reordered.replayed()).isTrue();
        assertThat(reordered.order().id()).isEqualTo(first.order().id());
    }

    @Test
    void sameKeyWithADifferentPayloadIsRejected() {
        UUID a = product("A", "10.00");
        String key = key();
        orderService.createOrder(key, order(item(a, 1)));
        clearInvocations(inventoryClient);

        assertThatThrownBy(() -> orderService.createOrder(key, order(item(a, 2))))
                .isInstanceOf(IdempotencyKeyMismatchException.class);

        verifyNoInteractions(inventoryClient);
        assertThat(ordersFor(a)).isEqualTo(1);
    }

    @Test
    void oneHundredConcurrentRequestsWithTheSameKeyCreateOneOrderAndOneReservation() throws Exception {
        UUID a = product("A", "10.00");
        UUID b = product("B", "20.00");
        String key = key();
        CreateOrderRequest request = order(item(a, 1), item(b, 2));

        List<Outcome<OrderCreationResult>> outcomes = ConcurrentRunner.runAll(100,
                i -> () -> orderService.createOrder(key, request));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        List<OrderCreationResult> results = outcomes.stream().map(Outcome::value).toList();

        assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1);
        assertThat(results.stream().filter(OrderCreationResult::replayed)).hasSize(99);
        assertThat(results.stream().map(r -> r.order().id()).distinct()).hasSize(1);

        verify(inventoryClient, times(2)).reserve(any(), anyInt(), anyString());   // 2 items, once
        verify(inventoryClient, never()).release(any(), anyInt(), anyString());
        assertThat(ordersFor(a)).isEqualTo(1);
    }

    @Test
    void identicalConcurrentRequestsReadProductOnceNotOncePerRequest() throws Exception {
        UUID a = product("A", "10.00");
        String key = key();
        CreateOrderRequest request = order(item(a, 1));

        List<Outcome<OrderCreationResult>> outcomes = ConcurrentRunner.runAll(100,
                i -> () -> orderService.createOrder(key, request));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        // one request creates the order; the other 99 wait for it and replay it, so Product is not
        // hit 100 times (its bulkhead would shed the excess) for what is a single order
        verify(productClient, times(1)).getProduct(a);
        assertThat(ordersFor(a)).isEqualTo(1);
    }

    // ---- compensation when the ORDER side fails -------------------------------------------

    @Test
    void ifTheOrderCannotBeConfirmedTheReservationIsReleased() {
        UUID a = product("A", "10.00");
        UUID b = product("B", "20.00");
        String key = key();
        doThrow(new IllegalStateException("database exploded"))
                .when(transactions).confirm(any(), eq(key));

        assertThatThrownBy(() -> orderService.createOrder(key, order(item(a, 1), item(b, 2))))
                .isInstanceOf(OrderProcessingException.class);

        // both reservations were made, then both given back
        verify(inventoryClient, times(2)).reserve(any(), anyInt(), anyString());
        verify(inventoryClient, times(1)).release(eq(a), eq(1), anyString());
        verify(inventoryClient, times(1)).release(eq(b), eq(2), anyString());

        assertThat(statusOfOnlyOrderFor(a)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(claimRepository.findByIdempotencyKey(key)).isEmpty();
    }

    @Test
    void ifConfirmFailsAfterActuallyCommittingTheOrderIsReturnedAndStockIsKept() {
        UUID a = product("A", "10.00");
        String key = key();
        // The spy sits INSIDE the @Transactional proxy, so a plain "call real method, then throw"
        // would be rolled back. Run the real confirm in its own transaction that genuinely
        // commits, and only then fail: "the commit succeeded, the acknowledgement was lost".
        TransactionTemplate committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(invocation -> {
            committed.execute(status -> {
                try {
                    return invocation.callRealMethod();
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            });
            throw new IllegalStateException("...but the connection dropped before we heard");
        }).when(transactions).confirm(any(), eq(key));

        OrderCreationResult result = orderService.createOrder(key, order(item(a, 3)));

        assertThat(result.order().status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(inventoryClient, never()).release(any(), anyInt(), anyString());   // would oversell
        assertThat(statusOfOnlyOrderFor(a)).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void ifTheOrderStateCannotBeVerifiedStockIsNotReleased() {
        UUID a = product("A", "10.00");
        String key = key();
        doThrow(new IllegalStateException("db down")).when(transactions).confirm(any(), eq(key));
        doThrow(new IllegalStateException("db still down")).when(transactions).statusOf(any());

        assertThatThrownBy(() -> orderService.createOrder(key, order(item(a, 1))))
                .isInstanceOf(OrderProcessingException.class);

        // we cannot tell whether the order was confirmed, so we must not give its stock away
        verify(inventoryClient, never()).release(any(), anyInt(), anyString());
    }
}
