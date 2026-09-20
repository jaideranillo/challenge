# Session: 2026-09-20 ADR-001 resilience, ingest path, relay guarantee

**Date:** 2026-09-20
**Topics:** architecture, backend, database

## Work Completed

### Files Modified
- `docs/architecture/adr/ADR-001-webhook-notification-delivery-outbox.md` — extensive iteration. See ADR-001 for full content, not restated here. Sections added/rewritten this session: §0.1 (SQS vs Kafka), §1.1 (Ingest path), §2/§2.1 (state machine + FAILED terminal state + who-writes-what), §3 (tenant isolation hardened), §6.1 (relay due-query, the guaranteed path), §8 (data model reconciled twice), §9 (Resilience policies).

### Problems Solved
1. **§5/§8 replay contradiction (Atlas review finding #1)** — one said replay resets `attempt_count` on the original row, the other said it doesn't. Resolved by changing replay semantics entirely: `POST /replay` now inserts a **new** `deliveries` row (`origin='REPLAY'`, `replayed_from=<original id>`) rather than mutating the `DEAD` row. Original row stays immutable audit. Required relaxing the idempotency guarantee from a hard `UNIQUE(event_id, subscription_id)` to a **partial unique index** scoped to non-terminal statuses, so a pair can have multiple historical rows but at most one live one.
2. **DLQ poison messages were invisible** — a message landing in SQS's DLQ used to leave the `deliveries` row unchanged (looked like normal backlog). Added `FAILED` as a third terminal state (distinct from `DEAD`: internal/poison vs. business failure), written only by a dedicated DLQ-consumer actor.
3. **Q10 (event ingress mechanism) was unresolved** — pinned down as a synchronous HTTP gateway endpoint, no outbound calls, `202` response before any queue publish, best-effort SQS send strictly after commit. New open item: producer-auth mechanism for this endpoint still unspecified.
4. **Tenant isolation was fetch-then-compare, not structural** — §3 previously fetched a subscription then asserted its `client_id` matched; rewritten so the query predicate itself includes `client_id`, so a cross-tenant row can never be materialized in the first place.
5. **`PROCESSING` rows had no reclaim path** — the relay's due-query (as first drafted) only covered `PENDING`/`RETRYING`/`QUEUED`; a worker crash mid-HTTP-call left a row stuck forever. Added `PROCESSING` to the query with a 60s staleness guard on `updated_at`.
6. **Schema naming mismatch discovered via a real SQL join** — `subscriptions.id`/`deliveries.id` didn't support `JOIN subscriptions USING (subscription_id)`. Renamed to `subscriptions.subscription_id` (PK) and `deliveries.delivery_id` (PK) throughout the ADR.
7. **"Lease expiry" was never actually implemented** — old state machine promised a `PROCESSING -> PENDING` lease-expiry path with no owning mechanism (lease columns were dropped earlier for being a hot-row risk). Replaced everywhere with "the relay's own 5s due-query is the reclaim mechanism" — no separate lease concept.

### Technical Decisions
All architectural — see ADR-001. Pointers only:
- Circuit breaker state on `subscriptions` (Postgres), not Redis; failure count in-memory per pod (Resilience4j), only the OPEN transition persisted, first-writer-wins `UPDATE ... WHERE circuit_state='CLOSED'` (§9).
- `circuit_backoff` (interval) computed once at trip time (`base * 2^(consecutive_opens-1)`) and stored, so the relay's due-query can read it directly instead of recomputing (§8, §9).
- Retry backoff: `5s→30s→2m→10m→1h→6h` ±20% jitter (§4).
- The relay's periodic due-query (5s fixedDelay, `FOR UPDATE SKIP LOCKED`) is explicitly *the* guaranteed-delivery mechanism; SQS is a latency optimization only, losable without correctness impact (§0, §6.1).

## Status at End
- Completed: §1.1, §2/§2.1 rewrite, §3 hardening, §6.1, replay-as-insert, `FAILED` state, PK renames, PROCESSING reclaim gap fix.
- Still open (from prior review, not yet fixed):
  - **Circuit breaker still never closes** — only `CLOSED -> OPEN` is defined anywhere in the ADR; no `HALF_OPEN`/`CLOSED` transition exists. Explicitly flagged inline in ADR §9 now ("open point, not yet resolved") rather than silently left. This is the next thing the user asked to fix, after the ingest/relay clarifications captured this session.
  - Q5 retry-schedule numbers still stale in the Assumptions list (says 30s base/2x/1h cap — actual schedule is `5s→30s→2m→10m→1h→6h`).
  - Bulkhead concurrency semantics (per-pod semaphore vs. DB-side in-flight count, Atlas finding #4) not reconciled.
  - SQS visibility timeout still never stated numerically.
  - `throttled_until` write-guard (monotonic, not just first-writer-wins) not yet specified.
  - OWASP A03/A10 rows not updated for Resilience4j dependency / bulkhead's no-write outcome.

## Notes for Next Session
- User's stated order: fix circuit breaker (HALF_OPEN/CLOSED transitions) next.
- ADR-001 `Status` is still `Proposed`; Q1–Q11 plus the above still outstanding before `Accepted`.
