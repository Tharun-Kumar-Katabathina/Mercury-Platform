-- One row per client Idempotency-Key. It is inserted (IN_PROGRESS) in the same transaction as
-- the PENDING order, so the unique key lets exactly one concurrent request create the order.
-- It becomes COMPLETED, with the original response, when the order is confirmed.
CREATE TABLE order_idempotency_records (
    id                UUID                     NOT NULL,
    idempotency_key   VARCHAR(255)             NOT NULL,
    request_hash      VARCHAR(64)              NOT NULL,
    order_id          UUID                     NOT NULL,
    status            VARCHAR(20)              NOT NULL,
    response_snapshot VARCHAR(100000),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_order_idempotency_records PRIMARY KEY (id),
    CONSTRAINT uk_order_idempotency_records_key UNIQUE (idempotency_key),
    CONSTRAINT fk_order_idempotency_records_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_order_idempotency_records_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);
