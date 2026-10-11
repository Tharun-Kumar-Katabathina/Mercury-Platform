-- A FENCED record is a tombstone written under a reservation's idempotency key by the caller that has
-- given up on that reservation (Order Service, cancelling an order whose reserve outcome is unknown).
-- The key is unique, so exactly one of "reserve" and "fence" can ever claim it; a reserve that arrives
-- after the fence is refused instead of holding stock for an order that no longer exists.
ALTER TABLE idempotency_records DROP CONSTRAINT ck_idempotency_records_operation;
ALTER TABLE idempotency_records
    ADD CONSTRAINT ck_idempotency_records_operation CHECK (operation IN ('RESERVE', 'RELEASE', 'FENCED'));
