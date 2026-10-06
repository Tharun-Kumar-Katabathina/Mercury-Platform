package com.mercury.inventory.service;

import com.mercury.inventory.event.InventoryRejectedEvent;
import com.mercury.inventory.event.InventoryReservedEvent;
import com.mercury.inventory.event.ReservationCommand;
import com.mercury.inventory.exception.InvalidCommandException;
import com.mercury.inventory.exception.OrderReservationNotFoundException;
import com.mercury.inventory.model.Inventory;
import com.mercury.inventory.model.OrderReservation;
import com.mercury.inventory.model.ProcessedEvent;
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

    public enum Result { RESERVED, REJECTED, DUPLICATE }

    private final InventoryRepository inventoryRepository;
    private final OrderReservationRepository reservations;
    private final ProcessedEventRepository processedEvents;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final int maxAttempts;

    public OrderReservationService(
            InventoryRepository inventoryRepository,
            OrderReservationRepository reservations,
            ProcessedEventRepository processedEvents,
            OutboxWriter outbox,
            TransactionTemplate transactionTemplate,
            Clock clock,
            @Value("${inventory.reserve.max-attempts:5}") int maxAttempts) {
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
                return transactionTemplate.execute(status -> reserveOnce(command, items));
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= maxAttempts) {
                    throw e;   // not lost: the consumer retries, then dead-letters; Order's deadline resolves it
                }
                backOff(attempt);
            } catch (DataIntegrityViolationException e) {
                // a concurrent delivery of the same command committed first
                if (alreadyHandled(command)) {
                    return Result.DUPLICATE;
                }
                throw e;
            }
        }
    }

    private Result reserveOnce(ReservationCommand command, List<ReservationCommand.Item> items) {

        if (processedEvents.existsById(command.eventId())) {
            return Result.DUPLICATE;
        }
        if (reservations.existsById(command.orderId())) {
            processedEvents.saveAndFlush(processed(command));   // a new event id for an order already decided
            return Result.DUPLICATE;
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

    private boolean alreadyHandled(ReservationCommand command) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status ->
                processedEvents.existsById(command.eventId()) || reservations.existsById(command.orderId())));
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
