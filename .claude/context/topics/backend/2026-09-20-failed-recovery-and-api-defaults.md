# FAILED recovery mechanism + self-service API defaults (implementation-facing)

Full design lives in `docs/architecture/adr/ADR-001-webhook-notification-delivery-outbox.md` §2.1, §4, §5, §8 — this note is only the implementation shape, not a restatement of the reasoning.

## FAILED-row recovery (internal, not client-facing)
- Never `UPDATE` the `FAILED` row directly. Insert a **new** `deliveries` row: `origin='RECOVERED'`, `replayed_from=<original FAILED row's delivery_id>` (same column `REPLAY` uses — `origin` disambiguates which), `status=PENDING`, `attempt_count=0`.
- Triggered by an operator/internal action after the underlying bug is fixed — not exposed on `POST /replay` (that endpoint keeps rejecting `FAILED` with 409).
- No new state-machine transition needed for this — the new row just enters through the same `[*] -> PENDING` arc every other insert uses.

## Self-service API defaults to implement (all proposals, not derived from measured data)
- `GET /notification_events` page size: default 50, max 200 — `limit` above max is clamped, not rejected.
- `GET /notification_events` date window when `created_from`/`created_to` omitted: default last 30 days.
- `GET /notification_events/{id}` must join and return the full `delivery_attempts` history in the response body, not just the current `deliveries` row state.

## Explicitly decided against
- No read replica for v1 — all reads and writes hit the same Postgres instance/connection pool.
