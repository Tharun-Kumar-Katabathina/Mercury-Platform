package com.mercury.inventory.service;

import com.mercury.inventory.dto.CreateInventoryRequest;
import com.mercury.inventory.dto.InventoryResponse;
import com.mercury.inventory.event.ReservationCommand;
import com.mercury.inventory.model.OrderReservation;
import com.mercury.inventory.model.ReservationOutcome;
import com.mercury.inventory.outbox.OutboxRepository;
import com.mercury.inventory.repository.ProcessedEventRepository;
import com.mercury.inventory.repository.OrderReservationRepository;
import com.mercury.inventory.service.OrderReservationService.Result;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * Fencing an ASYNC order: once Order Service has given up waiting and is about to cancel the order, its reservation
 * command (queued, redelivered or still being processed) must not hold stock for it.
 */
@SpringBootTest(properties = "inventory.reserve.max-attempts=30")
class OrderReservationFenceTests {

    @Autowired private OrderReservationService service;
    @Autowired private InventoryService inventoryService;
    @Autowired private OutboxRepository outbox;
    @Autowired private ProcessedEventRepository processedEvents;
    @MockitoSpyBean private OrderReservationRepository reservations;
    @PersistenceContext private EntityManager entityManager;

    private UUID product(int stock) {
        UUID id = UUID.randomUUID();
        inventoryService.createInventory(new CreateInventoryRequest(id, stock));
        return id;
    }

    private static ReservationCommand command(UUID orderId, UUID productId, int quantity) {
        return new ReservationCommand(UUID.randomUUID(), ReservationCommand.TYPE, Instant.now(), orderId,
                List.of(new ReservationCommand.Item(productId, quantity)));
    }

    private void assertStock(UUID productId, int available, int reserved) {
        InventoryResponse stored = inventoryService.getInventory(productId);
        assertThat(stored.availableQuantity()).isEqualTo(available);
        assertThat(stored.reservedQuantity()).isEqualTo(reserved);
    }

    private int replies(UUID orderId) {
        return outbox.findByAggregateIdOrderBySeq(orderId).size();
    }

    // ---- fence before the command ------------------------------------------------------------

    @Test
    void aCommandAfterTheFenceIsRefusedHoldsNothingAndSendsNoReply() {
        UUID p = product(10);
        UUID orderId = UUID.randomUUID();

        assertThat(service.fence(orderId).getStatus()).isEqualTo(ReservationOutcome.FENCED);

        assertThat(service.reserve(command(orderId, p, 3))).isEqualTo(Result.FENCED);
        assertThat(service.reserve(command(orderId, p, 3))).isEqualTo(Result.FENCED);   // redelivered as a new event
        assertStock(p, 10, 0);
        assertThat(replies(orderId)).isZero();
        assertThat(service.findOutcome(orderId).getStatus()).isEqualTo(ReservationOutcome.FENCED);
    }

    @Test
    void fencingTwiceIsIdempotent() {
        UUID orderId = UUID.randomUUID();
        OrderReservation first = service.fence(orderId);
        OrderReservation second = service.fence(orderId);

        assertThat(second.getStatus()).isEqualTo(ReservationOutcome.FENCED);
        assertThat(second.getCreatedAt()).isEqualTo(first.getCreatedAt());
        assertThat(second.getItems()).isEmpty();
    }

    // ---- command before the fence ------------------------------------------------------------

    @Test
    void aFenceAfterTheCommandReportsTheReservationAndChangesNothing() {
        UUID p = product(10);
        UUID orderId = UUID.randomUUID();
        service.reserve(command(orderId, p, 3));

        OrderReservation outcome = service.fence(orderId);

        assertThat(outcome.getStatus()).isEqualTo(ReservationOutcome.RESERVED);
        assertThat(outcome.getItems()).singleElement().satisfies(i -> {
            assertThat(i.getProductId()).isEqualTo(p);
            assertThat(i.getQuantity()).isEqualTo(3);
        });
        assertStock(p, 7, 3);
        assertThat(replies(orderId)).isEqualTo(1);
    }

    @Test
    void aFenceAfterARejectionReportsTheRejectionWithItsReason() {
        UUID p = product(1);
        UUID orderId = UUID.randomUUID();
        service.reserve(command(orderId, p, 5));

        OrderReservation outcome = service.fence(orderId);

        assertThat(outcome.getStatus()).isEqualTo(ReservationOutcome.REJECTED);
        assertThat(outcome.getReason()).isEqualTo("INSUFFICIENT_STOCK");
        assertStock(p, 1, 0);
    }

    // ---- the in-flight race, made deterministic --------------------------------------------

    @Test
    void aCommandStalledBeforeItsInsertLosesToAFenceThatCommitsFirstAndRollsBackItsStockChange() throws Exception {
        UUID p = product(10);
        UUID orderId = UUID.randomUUID();
        Stall stall = stallInsertOf(ReservationOutcome.RESERVED, orderId);
        ReservationCommand stalled = command(orderId, p, 3);

        CompletableFuture<Result> command = CompletableFuture.supplyAsync(() -> service.reserve(stalled));
        assertThat(stall.reached.await(30, TimeUnit.SECONDS)).as("command reached its insert").isTrue();

        OrderReservation fence = service.fence(orderId);            // commits while the command is stalled
        stall.proceed.countDown();

        assertThat(fence.getStatus()).isEqualTo(ReservationOutcome.FENCED);
        assertThat(command.get(30, TimeUnit.SECONDS)).isEqualTo(Result.FENCED);
        assertStock(p, 10, 0);                                       // its stock change rolled back with it
        assertThat(replies(orderId)).isZero();                       // and so did its reply
        // the losing command is still recorded as handled, like one that arrives after the fence: otherwise its
        // offset is committed with no trace that Inventory ever saw it
        assertThat(processedEvents.existsById(stalled.eventId())).isTrue();
        assertThat(service.reserve(stalled)).isEqualTo(Result.DUPLICATE);   // redelivered: nothing new happens
        assertStock(p, 10, 0);
    }

    @Test
    void aFenceStalledBeforeItsInsertLosesToACommandThatCommitsFirstAndReportsIt() throws Exception {
        UUID p = product(10);
        UUID orderId = UUID.randomUUID();
        Stall stall = stallInsertOf(ReservationOutcome.FENCED, orderId);

        CompletableFuture<OrderReservation> fence = CompletableFuture.supplyAsync(() -> service.fence(orderId));
        assertThat(stall.reached.await(30, TimeUnit.SECONDS)).as("fence reached its insert").isTrue();

        assertThat(service.reserve(command(orderId, p, 3))).isEqualTo(Result.RESERVED);   // commits first
        stall.proceed.countDown();

        assertThat(fence.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(ReservationOutcome.RESERVED);
        assertStock(p, 7, 3);
    }

    // ---- many at once ---------------------------------------------------------------------

    @Test
    void concurrentCommandsAndFencesForOneOrderAlwaysAgreeOnASingleOutcome() throws Exception {
        for (int round = 0; round < 15; round++) {
            UUID p = product(10);
            UUID orderId = UUID.randomUUID();

            List<ConcurrentRunner.Outcome<Object>> outcomes = ConcurrentRunner.runAll(8, i -> () ->
                    i % 2 == 0 ? service.reserve(command(orderId, p, 3)) : service.fence(orderId).getStatus());

            assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).as("%s", o.failure()).isNull());
            ReservationOutcome stored = service.findOutcome(orderId).getStatus();
            if (stored == ReservationOutcome.RESERVED) {       // a command won: every fence says so, 3 are held, one reply
                assertThat(outcomes).noneMatch(o -> o.value() == ReservationOutcome.FENCED || o.value() == Result.FENCED);
                assertStock(p, 7, 3);
                assertThat(replies(orderId)).isEqualTo(1);
            } else {                                            // a fence won: nothing held, no command reserved
                assertThat(stored).isEqualTo(ReservationOutcome.FENCED);
                assertThat(outcomes).noneMatch(o -> o.value() == Result.RESERVED || o.value() == ReservationOutcome.RESERVED);
                assertStock(p, 10, 0);
                assertThat(replies(orderId)).isZero();
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static final class Stall {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);
    }

    /** Holds the first insert of an order reservation with {@code status} for {@code orderId} until the test lets it go. */
    private Stall stallInsertOf(ReservationOutcome status, UUID orderId) {
        reset(reservations);
        Stall stall = new Stall();
        doAnswer((InvocationOnMock call) -> {
            OrderReservation row = call.getArgument(0);
            if (row.getStatus() == status && orderId.equals(row.getOrderId()) && stall.reached.getCount() > 0) {
                stall.reached.countDown();
                if (!stall.proceed.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test never released the stalled insert");
                }
            }
            // a repository proxy has no real method to call on the spy: use an unspied instance on the same EntityManager
            try {
                return new JpaRepositoryFactory(entityManager).getRepository(OrderReservationRepository.class).saveAndFlush(row);
            } catch (jakarta.persistence.PersistenceException e) {
                // that instance is not wrapped by Spring's exception translation, the real repository is
                throw new org.springframework.dao.DataIntegrityViolationException(e.getMessage(), e);
            }
        }).when(reservations).saveAndFlush(any(OrderReservation.class));
        return stall;
    }
}
