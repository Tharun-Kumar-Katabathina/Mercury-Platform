-- The W3C trace context of the request that wrote the event, so the asynchronous publish (and the consumers
-- behind it) continue the same distributed trace. Nullable: older rows and untraced writes simply have none.
ALTER TABLE inventory_outbox ADD COLUMN trace_parent VARCHAR(80);
