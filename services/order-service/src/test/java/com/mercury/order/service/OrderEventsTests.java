package com.mercury.order.service;

import com.mercury.order.client.InventoryClient;
import com.mercury.order.client.ProductClient;
import com.mercury.order.dto.CreateOrderItemRequest;
import com.mercury.order.dto.CreateOrderRequest;
import com.mercury.order.dto.InventoryOperationResult;
import com.mercury.order.dto.ProductDetails;
import com.mercury.order.exception.InventoryServiceException;
import com.mercury.order.model.OrderStatus;
import com.mercury.order.outbox.OutboxEvent;
import com.mercury.order.outbox.OutboxRepository;
import com.mercury.order.outbox.OutboxWriter;
import com.mercury.order.repository.OrderIdempotencyRecordRepository;
import com.mercury.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Every state change of an order writes its event, atomically, and the event carries no secrets. */
@SpringBootTest
class OrderEventsTests {

    @Autowired private OrderService orderService;
    @Autowired private OutboxRepository outbox;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderIdempotencyRecordRepository claims;
    @Autowired private JsonMapper json;

    @MockitoBean private ProductClient productClient;
    @MockitoBean private InventoryClient inventoryClient;
    @MockitoSpyBean private OutboxWriter outboxWriter;

    @BeforeEach
    void inventoryAcceptsEverything() {
        when(inventoryClient.reserve(any(), anyInt(), anyString())).thenReturn(new InventoryOperationResult(false));
        when(inventoryClient.release(any(), anyInt(), anyString())).thenReturn(new InventoryOperationResult(false));
        when(inventoryClient.findReservation(any(), anyString())).thenReturn(java.util.Optional.empty());
    }

    private UUID product(String name, String price) {
        UUID id = UUID.randomUUID();
        when(productClient.getProduct(id))
                .thenReturn(new ProductDetails(id, name, "SKU-" + name, new BigDecimal(price)));
        return id;
    }

    private static CreateOrderRequest order(UUID productId, int quantity) {
        return new CreateOrderRequest(List.of(new CreateOrderItemRequest(productId, quantity)));
    }

    private static String key() {
        return "secret-client-key-" + UUID.randomUUID();
    }

    private List<OutboxEvent> eventsFor(UUID orderId) {
        return outbox.findByAggregateIdOrderBySeq(orderId);
    }

    @Test
    void aConfirmedOrderWritesOrderCreatedThenOrderConfirmedInOrder() {
        UUID p = product("MacBook", "2499.00");

        UUID orderId = orderService.createOrder(key(), order(p, 2)).order().id();

        List<OutboxEvent> events = eventsFor(orderId);
        assertThat(events).extracting(OutboxEvent::getEventType)
                .containsExactly("OrderCreated", "OrderConfirmed");
        assertThat(events.get(0).getSeq()).isLessThan(events.get(1).getSeq());
        assertThat(events).allSatisfy(e -> {
            assertThat(e.getPublishedAt()).isNull();             // waiting for the publisher
            assertThat(e.getAggregateId()).isEqualTo(orderId);
        });
        assertThat(events.stream().map(OutboxEvent::getEventId).distinct()).hasSize(2);
    }

    @Test
    void orderCreatedCarriesTheItemSnapshotAndTotal() {
        UUID p = product("MacBook", "2499.00");

        UUID orderId = orderService.createOrder(key(), order(p, 2)).order().id();

        JsonNode created = json.readTree(eventsFor(orderId).get(0).getPayload());
        assertThat(created.path("eventType").asString()).isEqualTo("OrderCreated");
        assertThat(created.path("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(created.path("occurredAt").asString()).isNotBlank();
        assertThat(created.path("totalAmount").decimalValue()).isEqualByComparingTo("4998.00");
        JsonNode item = created.path("items").get(0);
        assertThat(item.path("productId").asString()).isEqualTo(p.toString());
        assertThat(item.path("quantity").asInt()).isEqualTo(2);
        assertThat(item.path("name").asString()).isEqualTo("MacBook");
        assertThat(item.path("sku").asString()).isEqualTo("SKU-MacBook");
        assertThat(item.path("unitPrice").decimalValue()).isEqualByComparingTo("2499.00");
    }

    @Test
    void aCancelledOrderWritesOrderCancelledWithAReason() {
        UUID p = product("Laptop", "10.00");
        when(inventoryClient.reserve(eq(p), anyInt(), anyString())).thenThrow(
                new InventoryServiceException(HttpStatus.CONFLICT, "{\"error\":\"INSUFFICIENT_STOCK\"}"));

        assertThatThrownBy(() -> orderService.createOrder(key(), order(p, 5)))
                .isInstanceOf(InventoryServiceException.class);

        UUID orderId = orderIdOf(p);
        List<OutboxEvent> events = eventsFor(orderId);
        assertThat(events).extracting(OutboxEvent::getEventType).containsExactly("OrderCreated", "OrderCancelled");
        JsonNode cancelled = json.readTree(events.get(1).getPayload());
        assertThat(cancelled.path("reason").asString()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(cancelled.path("orderId").asString()).isEqualTo(orderId.toString());
    }

    @Test
    void anInventoryOutageIsReportedAsInventoryUnavailable() {
        UUID p = product("Laptop", "10.00");
        when(inventoryClient.reserve(eq(p), anyInt(), anyString()))
                .thenThrow(new InventoryServiceException(HttpStatus.SERVICE_UNAVAILABLE, null, null, true));

        assertThatThrownBy(() -> orderService.createOrder(key(), order(p, 1)))
                .isInstanceOf(InventoryServiceException.class);

        JsonNode cancelled = json.readTree(eventsFor(orderIdOf(p)).get(1).getPayload());
        assertThat(cancelled.path("reason").asString()).isEqualTo("INVENTORY_UNAVAILABLE");
    }

    @Test
    void eventsNeverContainTheClientsIdempotencyKey() {
        UUID p = product("Laptop", "10.00");
        String secret = key();

        UUID orderId = orderService.createOrder(secret, order(p, 1)).order().id();

        assertThat(eventsFor(orderId)).allSatisfy(e -> assertThat(e.getPayload()).doesNotContain(secret));
    }

    @Test
    void aReplayedRequestWritesNoNewEvents() {
        UUID p = product("Laptop", "10.00");
        String key = key();
        UUID orderId = orderService.createOrder(key, order(p, 1)).order().id();

        orderService.createOrder(key, order(p, 1));
        orderService.createOrder(key, order(p, 1));

        assertThat(eventsFor(orderId)).hasSize(2);
    }

    @Test
    void theStateChangeAndItsEventRollBackTogether() {
        UUID p = product("Laptop", "10.00");
        String key = key();
        // the OrderConfirmed row IS inserted, then the same transaction fails: both must vanish
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if ("OrderConfirmed".equals(((com.mercury.order.event.OrderEvent) invocation.getArgument(0)).eventType())) {
                throw new IllegalStateException("failure after the event row was written");
            }
            return null;
        }).when(AopTestUtils.<OutboxWriter>getUltimateTargetObject(outboxWriter)).append(any());   // stub the spy itself: the
        // proxy in front of it enforces MANDATORY and would reject a call made outside a transaction

        assertThatThrownBy(() -> orderService.createOrder(key, order(p, 1)))
                .isInstanceOf(com.mercury.order.exception.OrderProcessingException.class);

        UUID orderId = orderIdOf(p);
        // never confirmed, and never announced as confirmed
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(eventsFor(orderId)).extracting(OutboxEvent::getEventType)
                .containsExactly("OrderCreated", "OrderCancelled");
        assertThat(eventsFor(orderId)).noneMatch(e -> e.getEventType().equals("OrderConfirmed"));
    }

    @Test
    void oneHundredConcurrentDuplicatesProduceExactlyOneOfEachEvent() throws Exception {
        UUID p = product("Laptop", "10.00");
        String key = key();

        var outcomes = ConcurrentRunner.runAll(100, i -> () -> orderService.createOrder(key, order(p, 1)));

        assertThat(outcomes).allMatch(ConcurrentRunner.Outcome::succeeded);
        UUID orderId = orderIdOf(p);
        assertThat(eventsFor(orderId)).extracting(OutboxEvent::getEventType)
                .containsExactly("OrderCreated", "OrderConfirmed");
    }

    @Test
    void anEventCannotBeWrittenOutsideATransaction() {
        assertThatThrownBy(() -> outboxWriter.append(
                com.mercury.order.event.OrderConfirmedEvent.of(UUID.randomUUID(), java.time.Instant.now())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    private UUID orderIdOf(UUID productId) {
        return orderRepository.findAll().stream()
                .map(o -> orderRepository.findWithItemsById(o.getId()).orElseThrow())
                .filter(o -> o.getItems().stream().anyMatch(i -> i.getProductId().equals(productId)))
                .map(o -> o.getId()).findFirst().orElseThrow();
    }
}
