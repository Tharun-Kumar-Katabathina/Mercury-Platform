package com.mercury.order.integration;

import com.mercury.order.integration.StackClient.Api;
import com.mercury.order.integration.StackClient.Stock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.mercury.order.integration.StackClient.key;
import static com.mercury.order.integration.StackClient.orderJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Failure injection for the asynchronous reservation against the REAL stack, with Order Service running
 * as its own process (real main classes, real configuration, recovery worker ON) so it can be killed:
 * a crash between the request and the reply, a reply that never arrives, a reservation that turns up after
 * the order timed out, and Inventory being unreachable when the deadline passes.
 *
 * Every assertion reads PostgreSQL directly.
 */
@Testcontainers(disabledWithoutDocker = true)
class AsyncFailureInjectionIntegrationTests {

    private static final String DB = RealServicesStack.ORDER_PROCESS_DB;

    private final RealServicesStack stack = RealServicesStack.start();
    private final StackClient client = new StackClient(JsonMapper.builder().build());
    private final List<UUID> products = new ArrayList<>();

    private Map<String, String> asyncOrder(String deadline, boolean inbound) {
        return Map.of(
                "ORDER_RESERVATION_MODE", "ASYNC",
                "ORDER_RESERVATION_ASYNC_DEADLINE", deadline,
                "ORDER_INBOUND_ENABLED", String.valueOf(inbound),
                "ORDER_CONSUMER_GROUP", "order-it-" + UUID.randomUUID(),
                "ORDER_RECOVERY_MAX_ATTEMPTS", "30",
                "INVENTORY_EVENT_TOPIC", RealServicesStack.INVENTORY_EVENTS_TOPIC);
    }

    private UUID productWithStock(int stock) {
        UUID id = client.productWithStock("Item", "10.00", stock);
        products.add(id);
        return id;
    }

    private Api place(UUID product, int quantity) {
        return client.call("POST", stack.orderProcessBaseUrl(), "/api/v1/orders", key(), orderJson(product, quantity));
    }

    private static UUID idOf(Api response) {
        return UUID.fromString(response.body().path("id").asString());
    }

    private void awaitStatus(UUID orderId, String status, int seconds) {
        await().atMost(Duration.ofSeconds(seconds)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(client.orderStatus(DB, orderId)).isEqualTo(status));
    }

    @AfterEach
    void clean() throws Exception {
        stack.unpauseKafkaIfPaused();
        stack.stopOrderProcess();
        stack.startInventoryServiceIfStopped();
        client.cleanUp(DB, products.toArray(UUID[]::new));
        products.clear();
    }

    @Test
    void anOrderServiceKilledBetweenTheRequestAndTheReplyStillCompletesAfterARestart() throws Exception {
        UUID product = productWithStock(10);
        stack.startOrderProcess(stack.inventoryBaseUrl(), asyncOrder("60s", true));
        stack.stopInventoryService();            // so the order is certainly in flight when Order dies

        Api accepted = place(product, 3);
        assertThat(accepted.status()).isEqualTo(202);
        UUID orderId = idOf(accepted);
        await().atMost(Duration.ofSeconds(30)).until(() -> client.outboxUnpublished(DB, orderId) == 0);   // command is in Kafka

        stack.killOrderProcess();                // kill -9: no cleanup at all
        assertThat(client.orderStatus(DB, orderId)).isEqualTo("PENDING");

        stack.startInventoryService();           // Inventory reserves and replies while Order is dead
        await().atMost(Duration.ofSeconds(60)).until(() ->
                "RESERVED".equals(client.inventoryDecision(orderId)) && client.inventoryRepliesUnpublished(orderId) == 0);
        assertThat(client.orderStatus(DB, orderId)).isEqualTo("PENDING");     // nobody has read the reply yet

        stack.startOrderProcess(stack.inventoryBaseUrl(), asyncOrder("60s", true));   // restart: reads it from Kafka

        awaitStatus(orderId, "CONFIRMED", 90);
        assertThat(client.sagaState(DB, orderId)).isEqualTo("CONFIRMED");
        assertThat(client.stock(product)).isEqualTo(new Stock(7, 3));
        assertThat(client.outboxOfType(DB, orderId, "OrderConfirmed")).isEqualTo(1);
    }

    @Test
    void aReplyThatNeverArrivesIsResolvedAfterTheDeadlineByAskingInventory() throws Exception {
        UUID reservable = productWithStock(10);
        UUID scarce = productWithStock(1);
        // Order does not consume replies at all, so only the deadline lookup can finish these orders
        stack.startOrderProcess(stack.inventoryBaseUrl(), asyncOrder("3s", false));

        UUID confirmed = idOf(place(reservable, 2));
        UUID cancelled = idOf(place(scarce, 5));

        awaitStatus(confirmed, "CONFIRMED", 90);
        awaitStatus(cancelled, "CANCELLED", 90);
        assertThat(client.stock(reservable)).isEqualTo(new Stock(8, 2));
        assertThat(client.stock(scarce)).isEqualTo(new Stock(1, 0));
        assertThat(client.cancelReason(DB, cancelled)).contains("INSUFFICIENT_STOCK");
        assertThat(client.sagaState(DB, confirmed)).isEqualTo("CONFIRMED");
    }

    @Test
    void aReservationThatArrivesAfterTheOrderTimedOutIsGivenBackExactlyOnce() throws Exception {
        UUID product = productWithStock(10);
        stack.startOrderProcess(stack.inventoryBaseUrl(), asyncOrder("3s", true));

        stack.pauseKafka();                      // the command cannot leave Order, so Inventory knows nothing
        UUID orderId;
        try {
            Api accepted = place(product, 4);
            assertThat(accepted.status()).isEqualTo(202);
            orderId = idOf(accepted);
            awaitStatus(orderId, "CANCELLED", 60);    // deadline passed: Inventory has decided nothing -> timeout
            assertThat(client.cancelReason(DB, orderId)).contains("RESERVATION_TIMEOUT");
            assertThat(client.stock(product)).isEqualTo(new Stock(10, 0));
        } finally {
            stack.unpauseKafka();
        }

        // the command is finally delivered: Inventory reserves for an order that no longer wants it ...
        await().atMost(Duration.ofSeconds(60)).until(() -> "RESERVED".equals(client.inventoryDecision(orderId)));
        // ... and Order, on the late reply, gives it back through the durable compensation path
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            assertThat(client.stock(product)).isEqualTo(new Stock(10, 0));
            assertThat(client.sagaState(DB, orderId)).isEqualTo("CANCELLED");
        });
        assertThat(client.orderStatus(DB, orderId)).isEqualTo("CANCELLED");
        assertThat(client.itemStatus(DB, orderId, product)).isEqualTo("RELEASED");
        assertThat(client.inventoryRecords(product, "RELEASE")).isEqualTo(1);               // released once
        assertThat(client.outboxOfType(DB, orderId, "OrderCancelled")).isEqualTo(1);        // announced once
    }

    @Test
    void ifInventoryIsUnreachableWhenTheDeadlinePassesTheOrderIsNotCancelledOnAGuess() throws Exception {
        UUID product = productWithStock(10);
        // Replies ARE consumed here, unlike in the lookup-only test above. Whether the order ends up confirmed or timed out
        // depends on who is first once Inventory is back, and a reservation that turns up after a timeout can only be found
        // and given back by the consumer: without it nothing would ever learn about it and the stock would stay reserved.
        stack.startOrderProcess(stack.inventoryBaseUrl(), asyncOrder("2s", true));
        stack.stopInventoryService();

        UUID orderId = idOf(place(product, 2));
        await().atMost(Duration.ofSeconds(30)).until(() -> client.sagaAttempts(DB, orderId) >= 2);   // it keeps asking
        assertThat(client.orderStatus(DB, orderId)).isEqualTo("PENDING");                              // and never guesses
        assertThat(client.sagaState(DB, orderId)).isEqualTo("AWAITING_INVENTORY");

        stack.startInventoryService();           // it consumes the waiting command and reserves

        // Two outcomes are both correct, depending on who is first once Inventory is back: its consumer
        // reserves and the reply (or the lookup) confirms the order, OR the lookup runs a moment before the
        // consumer has joined and Inventory has decided nothing yet, so the order times out and the
        // reservation that follows is released as a late reservation. What must hold either way: the order
        // reaches a final state and no stock is stranded. Both happen from run to run: the first lookup Inventory
        // answers comes as it finishes starting, which is also when its consumer starts working.
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            String status = client.orderStatus(DB, orderId);
            if ("CONFIRMED".equals(status)) {
                assertThat(client.stock(product)).isEqualTo(new Stock(8, 2));
                assertThat(client.inventoryRecords(product, "RELEASE")).isZero();   // nothing to give back
            } else {
                assertThat(status).isEqualTo("CANCELLED");
                assertThat(client.cancelReason(DB, orderId)).contains("RESERVATION_TIMEOUT");
                assertThat(client.sagaState(DB, orderId)).isEqualTo("CANCELLED");
                assertThat(client.stock(product)).isEqualTo(new Stock(10, 0));     // the late reservation was given back ...
                // ... and it really was: before Inventory has decided anything the stock is 10/0 as well
                assertThat(client.inventoryRecords(product, "RELEASE")).isEqualTo(1);
            }
        });
    }
}
