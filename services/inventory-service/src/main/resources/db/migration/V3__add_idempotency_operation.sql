-- Lets a stored result be looked up by key as "the reservation made under this key"
-- (used by Order Service to find out whether a reservation whose response was lost happened).
ALTER TABLE idempotency_records ADD COLUMN operation VARCHAR(10) NOT NULL DEFAULT 'RESERVE';

-- Rows written before this migration all defaulted to RESERVE; release snapshots are recognisable.
UPDATE idempotency_records SET operation = 'RELEASE' WHERE response_body LIKE '%"quantityReleased"%';

ALTER TABLE idempotency_records
    ADD CONSTRAINT ck_idempotency_records_operation CHECK (operation IN ('RESERVE', 'RELEASE'));
