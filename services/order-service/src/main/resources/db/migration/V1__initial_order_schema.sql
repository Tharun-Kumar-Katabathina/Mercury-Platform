CREATE TABLE orders (
    id           UUID                     NOT NULL,
    status       VARCHAR(20)              NOT NULL,
    total_amount NUMERIC(12, 2)           NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_orders_total_amount_non_negative CHECK (total_amount >= 0)
);

-- A snapshot of what was ordered: name, sku and price are copied from Product Service at
-- order time and never re-read, so later product changes do not rewrite order history.
CREATE TABLE order_items (
    id           UUID           NOT NULL,
    order_id     UUID           NOT NULL,
    product_id   UUID           NOT NULL,
    product_name VARCHAR(255)   NOT NULL,
    sku          VARCHAR(255)   NOT NULL,
    unit_price   NUMERIC(12, 2) NOT NULL,
    quantity     INTEGER        NOT NULL,
    CONSTRAINT pk_order_items PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT uk_order_items_order_product UNIQUE (order_id, product_id),
    CONSTRAINT ck_order_items_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_order_items_unit_price_non_negative CHECK (unit_price >= 0)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
