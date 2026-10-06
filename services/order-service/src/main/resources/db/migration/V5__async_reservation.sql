-- Asynchronous reservation (Phase 10). New columns/tables and widened CHECKs only; V1-V4 are unchanged.

-- The mode is decided when the order is created and never changes, so switching
-- order.reservation.mode while orders are in flight cannot corrupt them.
ALTER TABLE orders ADD COLUMN reservation_mode VARCHAR(10) NOT NULL DEFAULT 'SYNC';
ALTER TABLE orders ADD CONSTRAINT ck_orders_reservation_mode CHECK (reservation_mode IN ('SYNC', 'ASYNC'));

-- AWAITING_INVENTORY: an ASYNC order waiting for Inventory's reply. next_attempt_at is its deadline.
ALTER TABLE order_saga DROP CONSTRAINT ck_order_saga_state;
ALTER TABLE order_saga ADD CONSTRAINT ck_order_saga_state CHECK (
    state IN ('RESERVING', 'AWAITING_INVENTORY', 'COMPENSATING', 'CONFIRMED', 'CANCELLED', 'RECOVERY_FAILED'));

-- the command Order sends to Inventory travels through the same outbox as the order events
ALTER TABLE order_outbox DROP CONSTRAINT ck_order_outbox_event_type;
ALTER TABLE order_outbox ADD CONSTRAINT ck_order_outbox_event_type CHECK (
    event_type IN ('OrderCreated', 'OrderConfirmed', 'OrderCancelled', 'InventoryReservationRequested'));

-- Inventory events already handled; written in the same transaction as their effect.
CREATE TABLE order_processed_events (
    event_id     UUID                     NOT NULL,
    consumer     VARCHAR(50)              NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_order_processed_events PRIMARY KEY (event_id)
);
