-- Who placed the order: the subject of the authenticated caller's token. Nullable: orders created before
-- authentication existed (or with security switched off in development) have no owner and are visible only
-- to administrators and services.
ALTER TABLE orders ADD COLUMN customer_id VARCHAR(100);
CREATE INDEX idx_orders_customer_id ON orders (customer_id);
