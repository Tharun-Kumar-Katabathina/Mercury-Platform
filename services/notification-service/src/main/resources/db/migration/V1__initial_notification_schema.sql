-- One row per notification. event_id is UNIQUE: that constraint is what makes the consumer
-- idempotent. The same event delivered twice (Kafka is at-least-once) can only ever produce one row.
CREATE TABLE notification (
    id         UUID                     NOT NULL,
    event_id   UUID                     NOT NULL,
    order_id   UUID                     NOT NULL,
    type       VARCHAR(30)              NOT NULL,
    status     VARCHAR(20)              NOT NULL,
    detail     VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notification PRIMARY KEY (id),
    CONSTRAINT uk_notification_event_id UNIQUE (event_id),
    CONSTRAINT ck_notification_type CHECK (type IN ('ORDER_CONFIRMED', 'ORDER_CANCELLED')),
    CONSTRAINT ck_notification_status CHECK (status IN ('RECORDED'))
);

CREATE INDEX idx_notification_order_id ON notification (order_id);
