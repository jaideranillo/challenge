-- V4__deliveries_event_created_at.sql
-- Supersedes idx_deliveries_client_created_at from V2 (see § "The index swap" below).
-- V1-V3 are immutable; do not edit them.

-- ADR-003 Amendment A4, ADR-005 Amendment D2.
-- For a REPLAY or RECOVERED row, deliveries.created_at is when the replay was
-- requested, not when the event happened. Filtering on created_at would file a
-- replayed delivery under the replay's date rather than the event's date, hiding
-- it from any client query scoped to the event's actual window. The fix is to
-- denormalize notification_events.created_at onto deliveries at insert time and
-- run the list endpoint's date filter and keyset on that column instead.

-- Step 1: add nullable first - NOT NULL cannot be added before values exist.
-- No DEFAULT: a DEFAULT now() would silently mask a missing value from the
-- adapter and produce the exact replay-timestamp bug this column exists to prevent.
ALTER TABLE deliveries ADD COLUMN event_created_at timestamptz;

-- Step 2: backfill from notification_events.
-- The join on event_id is correct against a populated table; event_id is the FK
-- that guarantees every deliveries row has a parent notification_events row.
-- On a fresh or test database the UPDATE affects zero rows; the migration is
-- nonetheless written to be correct at any row count.
UPDATE deliveries d
SET event_created_at = e.created_at
FROM notification_events e
WHERE e.event_id = d.event_id;

-- Step 3: enforce NOT NULL now that every row has a value.
-- No DEFAULT and no trigger: immutability is an adapter property asserted by
-- TASK-004-09 and TASK-004-10, consistent with ADR-003 §3's rejection of triggers
-- for the idempotency invariant.
ALTER TABLE deliveries ALTER COLUMN event_created_at SET NOT NULL;

-- Step 4: create the replacement index.
-- Serves GET /notification_events date-range filter and keyset on
-- (client_id, event_created_at) (ADR-005 Amendment D2).
CREATE INDEX idx_deliveries_client_event_created_at
  ON deliveries (client_id, event_created_at);

-- Step 5: drop the index whose consumer moved to event_created_at.
-- idx_deliveries_client_created_at (V2) served the list endpoint's date filter.
-- After this migration the filter runs on event_created_at; created_at itself is
-- not dropped (it is still an audit column), but it no longer serves the keyset
-- or the date filter, and this index has no remaining query consumer.
-- The due-query's PENDING grace predicate (d.created_at < now() - interval '30s')
-- is served by idx_deliveries_due, not by this index.
DROP INDEX idx_deliveries_client_created_at;

COMMENT ON COLUMN deliveries.event_created_at IS
  'Denormalized from notification_events.created_at; written once at insert by the adapter, never updated (ADR-003 Amendment A4, ADR-005 Amendment D2). deliveries.created_at is when a replay was requested, not when the event happened — using created_at for the list-endpoint date filter or keyset would file a replayed delivery under the wrong day and hide it from the event''s window. This column exists to prevent that.';

COMMENT ON INDEX idx_deliveries_client_event_created_at IS
  'GET /notification_events date-range filter and keyset on (client_id, event_created_at) (ADR-005 Amendment D2). Supersedes idx_deliveries_client_created_at (V2), which is dropped by this migration because its consumer moved to event_created_at.';
