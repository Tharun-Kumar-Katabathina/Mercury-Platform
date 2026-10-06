-- Durable saga progress, so an interrupted order can be recovered instead of stranded.

-- Per item: how far the stock reservation got. It is written BEFORE each remote call, so an
-- unknown outcome (timeout, crash) is recorded as RESERVING and can be resolved later.
ALTER TABLE order_items ADD COLUMN reservation_status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED';
ALTER TABLE order_items ADD CONSTRAINT ck_order_items_reservation_status CHECK (
    reservation_status IN ('NOT_STARTED', 'RESERVING', 'RESERVED', 'NOT_RESERVED', 'RELEASING', 'RELEASED'));

-- One row per order: where the saga is and when recovery should look at it next.
CREATE TABLE order_saga (
    order_id        UUID                     NOT NULL,
    state           VARCHAR(30)              NOT NULL,
    attempt_count   INTEGER                  NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_until    TIMESTAMP WITH TIME ZONE,
    last_error      VARCHAR(1000),
    version         BIGINT                   NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_order_saga PRIMARY KEY (order_id),
    CONSTRAINT fk_order_saga_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_order_saga_state CHECK (
        state IN ('RESERVING', 'COMPENSATING', 'CONFIRMED', 'CANCELLED', 'RECOVERY_FAILED')),
    CONSTRAINT ck_order_saga_attempt_count CHECK (attempt_count >= 0)
);

-- what the recovery worker scans
CREATE INDEX idx_order_saga_due ON order_saga (state, next_attempt_at);

-- Orders that exist from before this migration. Items of CONFIRMED orders were reserved, of
-- CANCELLED orders were given back. A PENDING order's outcome is unknown, so each of its items is
-- marked RESERVING (resolved by asking Inventory) and the saga starts as COMPENSATING, due now.
UPDATE order_items SET reservation_status = (
    SELECT CASE o.status WHEN 'CONFIRMED' THEN 'RESERVED' WHEN 'CANCELLED' THEN 'RELEASED' ELSE 'RESERVING' END
    FROM orders o WHERE o.id = order_items.order_id);

INSERT INTO order_saga (order_id, state, attempt_count, next_attempt_at, version, created_at, updated_at)
SELECT id,
       CASE status WHEN 'CONFIRMED' THEN 'CONFIRMED' WHEN 'CANCELLED' THEN 'CANCELLED' ELSE 'COMPENSATING' END,
       0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM orders;
