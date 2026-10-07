-- Behaviour and the features derived from it. The recommendation service owns this data; it is rebuilt from the
-- order events and the interactions it receives, never read by another service.

-- Every interaction a customer has with a product (views, cart additions); purchases arrive as order events.
CREATE TABLE interactions (
    id          UUID                     NOT NULL,
    user_id     VARCHAR(100)             NOT NULL,
    product_id  UUID                     NOT NULL,
    type        VARCHAR(20)              NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_interactions PRIMARY KEY (id),
    CONSTRAINT ck_interactions_type CHECK (type IN ('VIEW', 'ADD_TO_CART', 'PURCHASE'))
);
CREATE INDEX idx_interactions_user ON interactions (user_id, occurred_at);

-- How much a customer cares about a product: a weighted sum of their interactions, decayed by age when read.
CREATE TABLE user_affinity (
    user_id    VARCHAR(100)             NOT NULL,
    product_id UUID                     NOT NULL,
    score      DOUBLE PRECISION         NOT NULL,
    purchased  BOOLEAN                  NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_user_affinity PRIMARY KEY (user_id, product_id)
);
CREATE INDEX idx_user_affinity_user ON user_affinity (user_id, score DESC);

-- How often two products were bought in the same order (stored in both directions).
CREATE TABLE cooccurrence (
    product_id UUID             NOT NULL,
    other_id   UUID             NOT NULL,
    score      DOUBLE PRECISION NOT NULL,
    CONSTRAINT pk_cooccurrence PRIMARY KEY (product_id, other_id)
);
CREATE INDEX idx_cooccurrence_top ON cooccurrence (product_id, score DESC);

-- Per product: how often it was bought, and whether its vector in the index is out of date.
CREATE TABLE product_stats (
    product_id     UUID                     NOT NULL,
    purchases      BIGINT                   NOT NULL,
    index_dirty    BOOLEAN                  NOT NULL,
    indexed_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_product_stats PRIMARY KEY (product_id)
);
CREATE INDEX idx_product_stats_dirty ON product_stats (index_dirty);
CREATE INDEX idx_product_stats_popular ON product_stats (purchases DESC);

-- An order that was created but not yet decided: it becomes a purchase only if OrderConfirmed follows.
CREATE TABLE pending_orders (
    order_id    UUID                     NOT NULL,
    customer_id VARCHAR(100),
    items       VARCHAR(10000)           NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_pending_orders PRIMARY KEY (order_id)
);

-- Events already handled, written in the same transaction as their effect (consumer idempotency).
CREATE TABLE processed_events (
    event_id     UUID                     NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_processed_events PRIMARY KEY (event_id)
);
