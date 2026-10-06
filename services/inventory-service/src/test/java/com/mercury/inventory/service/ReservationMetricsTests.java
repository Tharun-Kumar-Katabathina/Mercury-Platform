package com.mercury.inventory.service;

import com.mercury.inventory.dto.CreateInventoryRequest;
import com.mercury.inventory.event.ReservationCommand;
import com.mercury.inventory.exception.InsufficientStockException;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The reservation numbers the dashboards read: counted once per decision, on both reservation paths. */
@SpringBootTest
class ReservationMetricsTests {

    @Autowired private InventoryService inventoryService;
    @Autowired private OrderReservationService orderReservations;
    @Autowired private MeterRegistry meters;

    private UUID product(int stock) {
        UUID id = UUID.randomUUID();
        inventoryService.createInventory(new CreateInventoryRequest(id, stock));
        return id;
    }

    private double count(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private static ReservationCommand command(UUID orderId, UUID product, int quantity) {
        return new ReservationCommand(UUID.randomUUID(), ReservationCommand.TYPE, Instant.now(), orderId,
                List.of(new ReservationCommand.Item(product, quantity)));
    }

    @Test
    void theSeriesExistFromTheStartSoRatesHaveSomethingToStartFrom() {
        assertThat(meters.find("inventory.oversell.attempts").tags("path", "rest").counter()).isNotNull();
        assertThat(meters.find("inventory.reservations").tags("path", "async", "result", "reserved").counter()).isNotNull();
    }

    @Test
    void aRestReservationAndItsReplayAreCountedOnceEach() {
        UUID p = product(10);
        double reserved = count("inventory.reservations", "path", "rest", "result", "reserved");
        double replayed = count("inventory.reservations", "path", "rest", "result", "replayed");

        inventoryService.reserveInventory(p, 2, "m-key-1");
        inventoryService.reserveInventory(p, 2, "m-key-1");

        assertThat(count("inventory.reservations", "path", "rest", "result", "reserved")).isEqualTo(reserved + 1);
        assertThat(count("inventory.reservations", "path", "rest", "result", "replayed")).isEqualTo(replayed + 1);
    }

    @Test
    void anAttemptToTakeMoreThanIsAvailableIsARejectionAndAnOversellAttempt() {
        UUID p = product(1);
        double rejected = count("inventory.rejections", "path", "rest", "reason", "INSUFFICIENT_STOCK");
        double attempts = count("inventory.oversell.attempts", "path", "rest");

        assertThatThrownBy(() -> inventoryService.reserveInventory(p, 5, "m-key-2"))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(count("inventory.rejections", "path", "rest", "reason", "INSUFFICIENT_STOCK")).isEqualTo(rejected + 1);
        assertThat(count("inventory.oversell.attempts", "path", "rest")).isEqualTo(attempts + 1);
    }

    @Test
    void anAsyncOrderIsCountedOncePerDecisionAndAMissingProductIsNotAnOversellAttempt() {
        UUID p = product(5);
        double reserved = count("inventory.reservations", "path", "async", "result", "reserved");
        double oversell = count("inventory.oversell.attempts", "path", "async");
        double notFound = count("inventory.rejections", "path", "async", "reason", "INVENTORY_NOT_FOUND");

        UUID ok = UUID.randomUUID();
        orderReservations.reserve(command(ok, p, 2));
        orderReservations.reserve(command(ok, p, 2));                                // duplicate: not a new decision
        orderReservations.reserve(command(UUID.randomUUID(), UUID.randomUUID(), 1));  // unknown product
        orderReservations.reserve(command(UUID.randomUUID(), p, 99));                // more than is left

        assertThat(count("inventory.reservations", "path", "async", "result", "reserved")).isEqualTo(reserved + 1);
        assertThat(count("inventory.rejections", "path", "async", "reason", "INVENTORY_NOT_FOUND")).isEqualTo(notFound + 1);
        assertThat(count("inventory.oversell.attempts", "path", "async")).isEqualTo(oversell + 1);
    }
}
