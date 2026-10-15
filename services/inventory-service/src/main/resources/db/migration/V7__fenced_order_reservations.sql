-- A FENCED order reservation is a tombstone written by Order Service when an ASYNC order's waiting deadline has
-- passed and Inventory has decided nothing for it yet: Order is about to cancel the order. The order id is the
-- primary key, so exactly one of "reserve the order" (the Kafka command) and "fence the order" can ever claim it;
-- a command that arrives after the fence finds the order decided and changes nothing. Same idea as the per-item
-- fence for the synchronous path (V6). A FENCED row has no items.
ALTER TABLE order_reservations DROP CONSTRAINT ck_order_reservations_status;
ALTER TABLE order_reservations
    ADD CONSTRAINT ck_order_reservations_status CHECK (status IN ('RESERVED', 'REJECTED', 'FENCED'));
