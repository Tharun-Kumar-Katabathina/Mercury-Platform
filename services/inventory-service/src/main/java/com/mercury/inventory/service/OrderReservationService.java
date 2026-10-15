package com.mercury.inventory.service;

import com.mercury.inventory.event.InventoryRejectedEvent;
import com.mercury.inventory.event.InventoryReservedEvent;
import com.mercury.inventory.event.ReservationCommand;
import com.mercury.inventory.exception.InvalidCommandException;
import com.mercury.inventory.exception.OrderReservationNotFoundException;
import com.mercury.inventory.model.Inventory;
import com.mercury.inventory.model.OrderReservation;
import com.mercury.inventory.model.ProcessedEvent;
import com.mercury.inventory.model.ReservationOutcome;
import com.mercury.inventory.outbox.OutboxWriter;
import com.mercury.inventory.repository.InventoryRepository;
import com.mercury.inventory.repository.OrderReservationRepository;
import com.mercury.inventory.repository.ProcessedEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reserves a whole order, all items or none, in ONE local transaction, and records the outcome.
 *
 * In that same transaction it also writes: the stock changes, the durable outcome (RESERVED or
 * REJECTED, keyed by {@code order:{orderId}}), the "event handled" marker, and exactly one reply
 * event in the outbox. So either everything is there or nothing is, and a crash can never produce
 * stock held without a reply, or a reply without the stock.
 *
 * Idempotent three ways: the event id, the order id (primary key of the outcome), and the stored
 * outcome itself (a REJECTED order is never re-evaluated, so it can never become RESERVED later).
 * Concurrent stock changes are caught by the @Version check; the whole transaction is retried a
 * bounded number of times. This path does not touch the synchronous REST reserve in any way.
 */
@Service
public class OrderReservationService {

    public static final String CONSUMER = "reservation-command";

    /** FENCED: the order was cancelled before this command was decided; nothing is reserved and no reply is sent */
    public enum Result { RESERVED, REJECTED, DUPLICATE, FENCED }

    private final InventoryRepository inventoryRepository;
    private final OrderReservationRepository reservations;
    private final ProcessedEventRepository processedEvents;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final int maxAttempts;
    private final ReservationMetrics metrics;

    public OrderReservationService(
            InventoryRepository inventoryRepository,
            OrderReservationRepository reservations,
            ProcessedEventRepository processedEvents,
            OutboxWriter outbox,
            TransactionTemplate transactionTemplate,
            Clock clock,
            @Value("${inventory.reserve.max-attempts:5}") int maxAttempts,
            ReservationMetrics metrics) {
        this.metrics = metrics;
        this.inventoryRepository = inventoryRepository;
        this.reservations = reservations;
        this.processedEvents = processedEvents;
        this.outbox = outbox;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    /** @throws InvalidCommandException if the command can never be processed */
    public Result reserve(ReservationCommand command) {

        List<ReservationCommand.Item> items = validated(command);

        for (int attempt = 1; ; attempt++) {
            try {
                Result result = transactionTemplate.execute(status -> reserveOnce(command, items));
                count(command, result);
                return result;
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= maxAttempts) {
                    throw e;   // not lost: the consumer retries, then dead-letters; Order's deadline resolves it
                }
                backOff(attempt);
            } catch (DataIntegrityViolationException e) {
                // a concurrent delivery of the same command, or the order's fence, committed first; this attempt
                // rolled back whole, stock changes included. The next one sees the winner's row and, like any command
                // for an order already decided, records this command as handled before answering FENCED or DUPLICATE
                if (attempt >= maxAttempts) {
                    throw e;   // the consumer retries, then dead-letters; Order's deadline resolves it
                }
            }
        }
    }

    /** after the commit: one count per decision, never per retry (a DUPLICATE is not a new decision) */
    private void count(ReservationCommand command, Result result) {
        if (result == Result.RESERVED) {
            metrics.reserved("async", false);
        } else if (result == Result.REJECTED) {
            reservations.findById(command.orderId())
                    .ifPresent(r -> metrics.rejected("async", r.getReason()));
        }
    }

    private Result reserveOnce(ReservationCommand command, List<ReservationCommand.Item> items) {

        if (processedEvents.existsById(command.eventId())) {
            return Result.DUPLICATE;
        }
        Optional<OrderReservation> decided = reservations.findById(command.orderId());
        if (decided.isPresent()) {
            processedEvents.saveAndFlush(processed(command));   // a new event id for an order already decided
            return decided.get().getStatus() == ReservationOutcome.FENCED ? Result.FENCED : Result.DUPLICATE;
        }

        // decide first, change nothing yet: a rejection must leave every stock row untouched
        List<Inventory> rows = new ArrayList<>();
        for (ReservationCommand.Item item : items) {
            Optional<Inventory> row = inventoryRepository.findByProductId(item.productId());
            if (row.isEmpty()) {
                return reject(command, "INVENTORY_NOT_FOUND", item.productId());
            }
            if (row.get().getAvailableQuantity() < item.quantity()) {
                return reject(command, "INSUFFICIENT_STOCK", item.productId());
            }
            rows.add(row.get());
        }

        for (int i = 0; i < rows.size(); i++) {
            Inventory row = rows.get(i);
            int quantity = items.get(i).quantity();
            row.setAvailableQuantity(row.getAvailableQuantity() - quantity);
            row.setReservedQuantity(row.getReservedQuantity() + quantity);
        }
        inventoryRepository.saveAllAndFlush(rows);   // the version check happens here, inside this attempt

        Instant now = Instant.now(clock);
        reservations.saveAndFlush(OrderReservation.reserved(command.orderId(),
                items.stream().map(i -> new OrderReservation.ItemQuantity(i.productId(), i.quantity())).toList(), now));
        processedEvents.saveAndFlush(processed(command));
        outbox.append(InventoryReservedEvent.of(command.orderId(), now,
                items.stream().map(i -> new InventoryReservedEvent.Item(i.productId(), i.quantity())).toList()));
        return Result.RESERVED;
    }

    private Result reject(ReservationCommand command, String reason, UUID productId) {
        Instant now = Instant.now(clock);
        reservations.saveAndFlush(OrderReservation.rejected(command.orderId(), reason, productId, now));
        processedEvents.saveAndFlush(processed(command));
        outbox.append(InventoryRejectedEvent.of(command.orderId(), now, reason, productId));
        return Result.REJECTED;
    }

    private ProcessedEvent processed(ReservationCommand command) {
        return new ProcessedEvent(command.eventId(), CONSUMER, Instant.now(clock));
    }

    /**
     * Settles an order for good, from Order Service's side: called when its waiting deadline has passed and it is
     * about to cancel. If Inventory has already decided (RESERVED or REJECTED) that outcome is returned unchanged
     * and Order acts on it. Otherwise a FENCED tombstone is written and returned: from then on the order's command
     * changes nothing, so Order may cancel knowing that no stock can be reserved for it later.
     *
     * The order id is the primary key, so this and the command cannot both claim the order: whichever inserts first
     * wins, and the loser sees the winner's row. Idempotent: fencing a fenced order returns the same tombstone.
     */
    public OrderReservation fence(UUID orderId) {
        try {
            boolean[] written = {false};
            OrderReservation outcome = transactionTemplate.execute(status -> reservations.findWithItemsByOrderId(orderId)
                    .orElseGet(() -> {
                        written[0] = true;
                        return reservations.saveAndFlush(OrderReservation.fenced(orderId, Instant.now(clock)));
                    }));
            if (written[0]) {
                metrics.fenced("async");   // once per order, after the commit; a repeated fence is not counted
            }
            return outcome;
        } catch (DataIntegrityViolationException e) {
            // the command (or another fence) inserted the order's row first: report what it decided
            return findOutcome(orderId);
        }
    }

    /** The stored outcome for an order: what Order Service asks when a reply is overdue. */
    @Transactional(readOnly = true)
    public OrderReservation findOutcome(UUID orderId) {
        return reservations.findWithItemsByOrderId(orderId)
                .orElseThrow(() -> new OrderReservationNotFoundException(orderId));
    }

    private static List<ReservationCommand.Item> validated(ReservationCommand command) {
        if (command == null || command.eventId() == null || command.orderId() == null) {
            throw new InvalidCommandException("command is missing eventId or orderId");
        }
        if (!ReservationCommand.TYPE.equals(command.eventType())) {
            throw new InvalidCommandException("unexpected command type " + command.eventType());
        }
        if (command.items() == null || command.items().isEmpty()) {
            throw new InvalidCommandException("command has no items");
        }
        Set<UUID> seen = new HashSet<>();
        for (ReservationCommand.Item item : command.items()) {
            if (item == null || item.productId() == null) {
                throw new InvalidCommandException("every item needs a productId");
            }
            if (item.quantity() == null || item.quantity() < 1) {
                throw new InvalidCommandException("quantity must be at least 1 for product " + item.productId());
            }
            if (!seen.add(item.productId())) {
                throw new InvalidCommandException("product " + item.productId() + " appears more than once");
            }
        }
        // canonical order, so concurrent commands touch rows in the same order
        return command.items().stream().sorted(Comparator.comparing(ReservationCommand.Item::productId)).toList();
    }

    private void backOff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 5L * attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying an order reservation", e);
        }
    }
}
