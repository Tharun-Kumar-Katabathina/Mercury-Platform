package com.mercury.inventory.kafka;

import com.mercury.inventory.event.ReservationCommand;
import com.mercury.inventory.exception.InvalidCommandException;
import com.mercury.inventory.service.OrderReservationService;
import com.mercury.inventory.service.OrderReservationService.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReservationCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ReservationCommandHandler.class);

    private final JsonMapper jsonMapper;
    private final OrderReservationService reservations;
    private final InventoryMetrics metrics;

    public ReservationCommandHandler(
            JsonMapper jsonMapper, OrderReservationService reservations, InventoryMetrics metrics) {
        this.jsonMapper = jsonMapper;
        this.reservations = reservations;
        this.metrics = metrics;
    }

    /** @throws InvalidCommandException when the payload can never be processed (do not retry) */
    public Result handle(String payload) {

        ReservationCommand command;
        try {
            command = jsonMapper.readValue(payload, ReservationCommand.class);
        } catch (RuntimeException e) {
            throw new InvalidCommandException("payload is not a readable reservation command", e);
        }

        Result result = reservations.reserve(command);
        metrics.consumed();
        switch (result) {
            case RESERVED -> metrics.reserved();
            case REJECTED -> metrics.rejected();
            case DUPLICATE -> metrics.duplicate();
        }
        log.info("command eventId={} orderId={} result={}", command.eventId(), command.orderId(), result);
        return result;
    }
}
