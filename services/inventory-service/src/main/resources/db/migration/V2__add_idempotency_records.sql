CREATE TABLE idempotency_records (
    id              UUID                     NOT NULL,
    idempotency_key VARCHAR(255)             NOT NULL,
    request_hash    VARCHAR(64)              NOT NULL,
    product_id      UUID                     NOT NULL,
    response_body   VARCHAR(2000)            NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_idempotency_records PRIMARY KEY (id),
    -- guarantees two concurrent requests can never both record the same key
    CONSTRAINT uk_idempotency_records_key UNIQUE (idempotency_key)
);
