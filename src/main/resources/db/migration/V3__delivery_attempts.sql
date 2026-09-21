-- ADR-003 §3: append-only history of HTTP attempts against a delivery.
-- Plain, unpartitioned (ADR-003 §3 defers partitioning).

CREATE TABLE delivery_attempts (
  id                bigint GENERATED ALWAYS AS IDENTITY,
  delivery_id       uuid        NOT NULL,
  attempt_number    int         NOT NULL,
  http_status       int,
  response_time_ms  int,
  response_excerpt  varchar(1000),
  error             text,
  attempted_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_delivery_attempts PRIMARY KEY (id),
  -- ON DELETE NO ACTION (the default): nothing in the ADRs deletes a
  -- deliveries row, so there is no cascade scenario to design for; if a
  -- future retention story starts deleting deliveries rows, this FK should
  -- be revisited then, against that story, not speculatively now.
  CONSTRAINT fk_delivery_attempts_delivery
    FOREIGN KEY (delivery_id) REFERENCES deliveries (delivery_id)
);

COMMENT ON TABLE delivery_attempts IS
  'ADR-003 §3: append-only, one row per HTTP attempt (or per attempted-but-failed-before-HTTP-response case). No outcome column - success/retryable/non-retryable is derived from http_status/error using ADR-004 §1''s classification, not stored redundantly.';
COMMENT ON COLUMN delivery_attempts.id IS
  'Not a public identifier - nothing in ADR-003 or ADR-005 exposes it.';
COMMENT ON COLUMN delivery_attempts.attempt_number IS
  'Mirrors deliveries.attempt_count at the time of the attempt; carried in the signed outbound body (ADR-004 §1.1).';
COMMENT ON COLUMN delivery_attempts.http_status IS
  'NULL when the attempt failed before an HTTP response existed (connect timeout, DNS failure, URL revalidation failure - ADR-003 §3).';
COMMENT ON COLUMN delivery_attempts.response_excerpt IS
  'PII (ADR-002 §3.1) - must NEVER reach a log, a span attribute, or MDC. Truncated at the application layer before insert; the varchar(1000) bound here is a hard backstop at the schema level so a truncation bug in the adapter cannot store an unbounded client response body. 1000 chars chosen as comfortably within "a few hundred to a couple thousand" (enough to diagnose a client error body) while still bounding worst-case row size; not a derived value, a proposal.';
COMMENT ON COLUMN delivery_attempts.error IS
  'Error class or message for a failure with no HTTP response (connect timeout, DNS failure, etc).';

-- "Full history behind a single complaint" (ADR-003 §3) and the DLQ
-- observer's correlation by delivery_id: an ordered walk through one
-- delivery's attempts with no extra sort. Leading column also serves plain
-- delivery_id lookups. The only index this table needs today - monitoring
-- aggregates (p95 latency, failure rate over a window) are a later,
-- measured decision against a named query, not speculated here.
CREATE INDEX idx_delivery_attempts_delivery_attempt ON delivery_attempts (delivery_id, attempt_number);

COMMENT ON INDEX idx_delivery_attempts_delivery_attempt IS
  'Full attempt history for one delivery, ordered, and DLQ-observer correlation by delivery_id (ADR-003 §3).';
