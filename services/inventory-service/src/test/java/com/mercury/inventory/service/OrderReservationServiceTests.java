package com.mercury.inventory.service;

import com.mercury.inventory.dto.CreateInventoryRequest;
import com.mercury.inventory.dto.InventoryResponse;
import com.mercury.inventory.event.ReservationCommand;
import com.mercury.inventory.exception.InvalidCommandException;
import com.mercury.inventory.exception.OrderReservationNotFoundException;
import com.mercury.inventory.model.OrderReservation;
import com.mercury.inventory.model.ReservationOutcome;
import com.mercury.inventory.outbox.OutboxEvent;
import com.mercury.inventory.outbox.OutboxRepository;
import com.mercury.inventory.outbox.OutboxWriter;
import com.mercury.inventory.service.ConcurrentRunner.Outcome;
import com.mercury.inventory.service.OrderReservationService.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** Order-level reservation: all items or none, one outcome, one reply, idempotent, concurrency-safe. */
// 30 attempts: every optimistic-lock conflict means another reservation committed, and at most the
// stock can, so no valid command can run out of retries (keeps the exact-count assertions deterministic)
@SpringBootTest(properties = "inventory.reserve.max-attempts=30")
class OrderReservationServiceTests {

    @Autowired private OrderReservationService reservations;
    @Autowired private InventoryService inventoryService;
    @Autowired private OutboxRepository outbox;
    @Autowired private JsonMapper json;
    @MockitoSpyBean private OutboxWriter outboxWriter;

    private UUID product(int stock) {
        UUID id = UUID.randomUUID();
        inventoryService.createInventory(new CreateInventoryRequest(id, stock));
        return id;
    }

    private static ReservationCommand command(UUID orderId, Object... productAndQuantity) {
        java.util.List<ReservationCommand.Item> items = new java.util.ArrayList<>();
        for (int i = 0; i < productAndQuantity.length; i += 2) {
            items.add(new ReservationCommand.Item((UUID) productAndQuantity[i], (Integer) productAndQuantity[i + 1]));
        }
        return new ReservationCommand(UUID.randomUUID(), ReservationCommand.TYPE, Instant.now(), orderId, items);
    }

    private InventoryResponse stock(UUID productId) {
        return inventoryService.getInventory(productId);
    }

    private List<OutboxEvent> replies(UUID orderId) {
        return outbox.findByAggregateIdOrderBySeq(orderId);
    }

    @Test
    void reservesEveryItemAndRecordsOneReservedOutcomeAndOneReply() {
        UUID a = product(10);
        UUID b = product(5);
        UUID orderId = UUID.randomUUID();

        assertThat(reservations.reserve(command(orderId, a, 3, b, 2))).isEqualTo(Result.RESERVED);

        assertThat(stock(a).availableQuantity()).isEqualTo(7);
        assertThat(stock(a).reservedQuantity()).isEqualTo(3);
        assertThat(stock(b).availableQuantity()).isEqualTo(3);
        assertThat(stock(b).reservedQuantity()).isEqualTo(2);

        OrderReservation outcome = reservations.findOutcome(orderId);
        assertThat(outcome.getStatus()).isEqualTo(ReservationOutcome.RESERVED);
        assertThat(outcome.getItems()).hasSize(2);

        List<OutboxEvent> events = replies(orderId);
        assertThat(events).hasSize(1);                                     // exactly one reply
        assertThat(events.get(0).getEventType()).isEqualTo("InventoryReserved");
        JsonNode payload = json.readTree(events.get(0).getPayload());
        assertThat(payload.path("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(payload.path("items").size()).isEqualTo(2);
        assertThat(events.get(0).getPayload()).doesNotContain("order:" + orderId);   // the key is not published
    }

    @Test
    void usesTheOrderLevelIdempotencyKey() {
        UUID a = product(5);
        UUID orderId = UUID.randomUUID();

        reservations.reserve(command(orderId, a, 1));

        assertThat(OrderReservation.keyFor(orderId)).isEqualTo("order:" + orderId);
    }

    @Test
    void allOrNothing_aShortItemRejectsTheWholeOrderAndLeavesEveryStockRowUntouched() {
        UUID plentiful = product(5);
        UUID scarce = product(1);
        UUID orderId = UUID.randomUUID();

        assertThat(reservations.reserve(command(orderId, plentiful, 2, scarce, 3))).isEqualTo(Result.REJECTED);

        assertThat(stock(plentiful).availableQuantity()).isEqualTo(5);
        assertThat(stock(plentiful).reservedQuantity()).isZero();
        assertThat(stock(scarce).availableQuantity()).isEqualTo(1);
        OrderReservation outcome = reservations.findOutcome(orderId);
        assertThat(outcome.getStatus()).isEqualTo(ReservationOutcome.REJECTED);
        assertThat(outcome.getReason()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(outcome.getRejectedProductId()).isEqualTo(scarce);
        assertThat(outcome.getItems()).isEmpty();

        List<OutboxEvent> events = replies(orderId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("InventoryRejected");
        JsonNode payload = json.readTree(events.get(0).getPayload());
        assertThat(payload.path("reason").asString()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(payload.path("productId").asString()).isEqualTo(scarce.toString());
    }

    @Test
    void aProductWithoutInventoryRejectsTheOrder() {
        UUID real = product(5);
        UUID unknown = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        assertThat(reservations.reserve(command(orderId, real, 1, unknown, 1))).isEqualTo(Result.REJECTED);

        assertThat(reservations.findOutcome(orderId).getReason()).isEqualTo("INVENTORY_NOT_FOUND");
        assertThat(stock(real).availableQuantity()).isEqualTo(5);
    }

    @Test
    void exactStockCanBeReservedInFull() {
        UUID a = product(4);

        assertThat(reservations.reserve(command(UUID.randomUUID(), a, 4))).isEqualTo(Result.RESERVED);

        assertThat(stock(a).availableQuantity()).isZero();
        assertThat(stock(a).reservedQuantity()).isEqualTo(4);
    }

    // ---- idempotency -------------------------------------------------------------------------

    @Test
    void theSameCommandDeliveredAgainHasNoSecondEffectAndNoSecondReply() {
        UUID a = product(10);
        UUID orderId = UUID.randomUUID();
        ReservationCommand command = command(orderId, a, 3);

        assertThat(reservations.reserve(command)).isEqualTo(Result.RESERVED);
        assertThat(reservations.reserve(command)).isEqualTo(Result.DUPLICATE);
        assertThat(reservations.reserve(command)).isEqualTo(Result.DUPLICATE);

        assertThat(stock(a).availableQuantity()).isEqualTo(7);             // 10 -> 7, never 10 -> 4
        assertThat(replies(orderId)).hasSize(1);
    }

    @Test
    void aSecondCommandForTheSameOrderUnderAnotherEventIdIsAlsoIgnored() {
        UUID a = product(10);
        UUID orderId = UUID.randomUUID();

        reservations.reserve(command(orderId, a, 3));
        assertThat(reservations.reserve(command(orderId, a, 3))).isEqualTo(Result.DUPLICATE);

        assertThat(stock(a).reservedQuantity()).isEqualTo(3);
        assertThat(replies(orderId)).hasSize(1);
    }

    @Test
    void aRejectedOrderIsNeverReEvaluatedSoItCanNeverBecomeReservedLater() {
        UUID a = product(1);
        UUID orderId = UUID.randomUUID();
        assertThat(reservations.reserve(command(orderId, a, 5))).isEqualTo(Result.REJECTED);

        inventoryService.updateInventory(a, new com.mercury.inventory.dto.UpdateInventoryRequest(100));   // stock appears
        assertThat(reservations.reserve(command(orderId, a, 5))).isEqualTo(Result.DUPLICATE);

        assertThat(reservations.findOutcome(orderId).getStatus()).isEqualTo(ReservationOutcome.REJECTED);
        assertThat(stock(a).reservedQuantity()).isZero();
        assertThat(replies(orderId)).hasSize(1);                           // still just the one rejection
    }

    // ---- invalid commands ------------------------------------------------------------------------

    @Test
    void commandsThatCanNeverBeProcessedAreRejectedAsInvalid() {
        UUID a = product(5);
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> reservations.reserve(null)).isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> reservations.reserve(new ReservationCommand(
                UUID.randomUUID(), ReservationCommand.TYPE, Instant.now(), orderId, List.of())))
                .isInstanceOf(InvalidCommandException.class);                        // no items
        assertThatThrownBy(() -> reservations.reserve(command(orderId, a, 0)))
                .isInstanceOf(InvalidCommandException.class);                        // quantity 0
        assertThatThrownBy(() -> reservations.reserve(command(orderId, a, 1, a, 2)))
                .isInstanceOf(InvalidCommandException.class);                        // same product twice
        assertThatThrownBy(() -> reservations.reserve(new ReservationCommand(
                UUID.randomUUID(), "SomethingElse", Instant.now(), orderId,
                List.of(new ReservationCommand.Item(a, 1)))))
                .isInstanceOf(InvalidCommandException.class);                        // wrong type

        assertThat(stock(a).availableQuantity()).isEqualTo(5);
        assertThat(replies(orderId)).isEmpty();
    }

    // ---- atomicity ---------------------------------------------------------------------------------

    @Test
    void theStockChangeTheOutcomeAndTheReplyCommitOrRollBackTogether() {
        UUID a = product(10);
        UUID orderId = UUID.randomUUID();
        // the reply row IS written, then the same transaction fails: stock, outcome and reply must all vanish
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("failure after the reply row was written");
        }).when(AopTestUtils.<OutboxWriter>getUltimateTargetObject(outboxWriter)).append(any());

        assertThatThrownBy(() -> reservations.reserve(command(orderId, a, 4)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(stock(a).availableQuantity()).isEqualTo(10);
        assertThat(stock(a).reservedQuantity()).isZero();
        assertThat(replies(orderId)).isEmpty();
        assertThatThrownBy(() -> reservations.findOutcome(orderId))
                .isInstanceOf(OrderReservationNotFoundException.class);
    }

    // ---- concurrency ----------------------------------------------------------------------------------

    @Test
    void fiftyConcurrentOrdersOnLimitedStockNeverOversell() throws Exception {
        int stock = 20;
        UUID a = product(stock);

        List<Outcome<Result>> outcomes = ConcurrentRunner.runAll(50,
                i -> () -> reservations.reserve(command(UUID.randomUUID(), a, 1)));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        long reserved = outcomes.stream().filter(o -> o.value() == Result.RESERVED).count();
        long rejected = outcomes.stream().filter(o -> o.value() == Result.REJECTED).count();

        assertThat(reserved).isEqualTo(stock);
        assertThat(rejected).isEqualTo(50 - stock);
        assertThat(stock(a).availableQuantity()).isZero();
        assertThat(stock(a).reservedQuantity()).isEqualTo(stock);
    }

    @Test
    void concurrentMultiItemOrdersNeverLeaveAPartialReservation() throws Exception {
        UUID a = product(10);
        UUID b = product(10);

        List<Outcome<Result>> outcomes = ConcurrentRunner.runAll(30,
                i -> () -> reservations.reserve(command(UUID.randomUUID(), a, 1, b, 1)));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        long reserved = outcomes.stream().filter(o -> o.value() == Result.RESERVED).count();
        assertThat(reserved).isEqualTo(10);
        // both rows moved together: never one item reserved without the other
        assertThat(stock(a).reservedQuantity()).isEqualTo(10);
        assertThat(stock(b).reservedQuantity()).isEqualTo(10);
        assertThat(stock(a).availableQuantity()).isZero();
        assertThat(stock(b).availableQuantity()).isZero();
    }

    @Test
    void oneHundredConcurrentDuplicatesOfOneCommandReserveOnceAndReplyOnce() throws Exception {
        UUID a = product(10);
        UUID orderId = UUID.randomUUID();
        ReservationCommand command = command(orderId, a, 2);

        List<Outcome<Result>> outcomes = ConcurrentRunner.runAll(100, i -> () -> reservations.reserve(command));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.stream().filter(o -> o.value() == Result.RESERVED)).hasSize(1);
        assertThat(stock(a).availableQuantity()).isEqualTo(8);
        assertThat(replies(orderId)).hasSize(1);
    }

    @Test
    void mixedConcurrentSyncReservesAndAsyncOrdersKeepStockConserved() throws Exception {
        // the existing per-item REST path and the new order-level path share the same stock rows
        int stock = 30;
        UUID a = product(stock);

        List<Outcome<Object>> outcomes = ConcurrentRunner.runAll(60, i -> () -> {
            if (i % 2 == 0) {
                return reservations.reserve(command(UUID.randomUUID(), a, 1));
            }
            return inventoryService.reserveInventory(a, 1, "key-" + UUID.randomUUID());
        });

        InventoryResponse result = stock(a);
        assertThat(result.availableQuantity()).isGreaterThanOrEqualTo(0);
        assertThat(result.availableQuantity() + result.reservedQuantity()).isEqualTo(stock);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()))
                .allMatch(o -> o.failure() instanceof com.mercury.inventory.exception.InsufficientStockException);
    }

    // ---- lookup -----------------------------------------------------------------------------------------

    @Test
    void anUnknownOrderHasNoOutcome() {
        assertThatThrownBy(() -> reservations.findOutcome(UUID.randomUUID()))
                .isInstanceOf(OrderReservationNotFoundException.class);
    }
}
