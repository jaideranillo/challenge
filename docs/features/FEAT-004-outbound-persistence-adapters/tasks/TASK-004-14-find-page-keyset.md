---
id: TASK-004-14
feature: FEAT-004
title: "findPage: keyset pagination and date filtering on event_created_at, with an opaque cursor codec"
status: Ready for Review
agent: dba
depends_on: [TASK-004-13]
date: 2026-09-20
---

# TASK-004-14: `findPage` on `event_created_at`

## Feature

FEAT-004

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/DeliveryQueryJdbcRepository.java` (add one method)
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/cursor/DeliveryPageCursor.java` (new)
- Concern: the list endpoint's paged, filtered read.

`DeliveryPage findPage(String clientId, Optional<Instant> eventCreatedFrom, Optional<Instant> eventCreatedTo, Optional<DeliveryStatus> status, Optional<String> cursor, int limit)`.

### The column, which is the whole point of this task

**Every date predicate and the entire keyset run on `deliveries.event_created_at`, never on `deliveries.created_at`** (ADR-003 Amendment A4, ADR-005 Amendment D2).

For a `REPLAY` or `RECOVERED` row the two differ: `created_at` is when the replay was requested, `event_created_at` is when the platform event happened. A client asking for deliveries of events in a date window means the latter, so filtering on `created_at` would file a replayed delivery under the wrong day and hide it from the window the client actually asked about. Comment the method with this, because `created_at` is the column a maintainer will reach for by reflex.

### Statement shape

```sql
SELECT <explicit columns> FROM deliveries
 WHERE client_id = :client_id
   [AND event_created_at >= :event_created_from]
   [AND event_created_at <  :event_created_to]
   [AND status = :status::delivery_status]
   [AND (event_created_at, delivery_id) < (:cursor_ts, :cursor_id)]
 ORDER BY event_created_at DESC, delivery_id DESC
 LIMIT :limit_plus_one
```

- **`client_id` is unconditional.** Never omitted, never optional, always bound.
- **Row-value comparison for the keyset**, `(event_created_at, delivery_id) < (:ts, :id)`, not a hand-expanded `OR`. Postgres matches a composite index against the row-value form; the expanded form frequently degrades to a filter.
- **`ORDER BY` must match the keyset direction exactly.** Newest-first (`DESC, DESC`) with `<`. Any mismatch silently drops or repeats rows at page boundaries, which no single-page test catches — TASK-004-15 tests the boundary specifically.
- **`delivery_id` is the tiebreak** and is mandatory: `event_created_at` is not unique (a fan-out writes N rows with the same value), and without the tiebreak a page boundary landing inside a fan-out loses rows.
- **Fetch `limit + 1`** to compute `hasMore` without a second `COUNT`, and return only `limit` rows.
- Optional predicates are appended by **static fragment composition** — a fixed `if` per filter concatenating a constant `String`, each with its own named parameter. No user value is ever concatenated.
- Every filter combination must still bind `client_id`. Structure the builder so omitting it is not expressible.

### `DeliveryPageCursor`

Encode and decode `(Instant eventCreatedAt, UUID deliveryId)` as one opaque string.

- Base64-url of a fixed, documented text form. **Opaque to the client but not a security boundary**: it is not signed and not encrypted, because it carries no secret and no authorization — the tenant comes from the security context on every request, never from the cursor. State this in the class javadoc, so nobody later mistakes the encoding for a control.
- **Decode is strict and rejects.** A malformed, truncated or wrong-arity cursor throws a dedicated unchecked exception; it must **not** silently fall back to page 1. A client paging through a large result set and getting page 1 back would loop forever, and it would hide a real bug (A10).
- Decode never trusts the decoded bytes into SQL as text: parse to `Instant` and `UUID` **first**, bind the typed values. This is the one client-supplied structured value that round-trips into a `WHERE` clause (A05).
- A cursor from one tenant used by another is harmless by construction, because `client_id` is bound separately and the cursor carries no tenant. Say so in the javadoc; TASK-004-15 asserts it.
- Immutable record, no state, no `ThreadLocal`.

`DeliveryPage`'s existing shape is unchanged; `Delivery` now carries `eventCreatedAt` (TASK-004-02), so the next cursor is built from the last returned record without a side channel.

## Out of Scope

- Tests (TASK-004-15).
- `limit` clamping and the default 30-day window (ADR-005 §1: default 50, max 200, 30 days). Applied on the way in by the web adapter or use case; this method binds what it is given. Reject a non-positive `limit` and nothing more.
- The public `delivery_status` translation (ADR-003 §1). The parameter is the internal enum.
- No `COUNT(*)` and no total-count field. Keyset pagination deliberately has none.
- No join to `notification_events`. The denormalized column exists precisely so this query stays single-table.
- No `findById` change (TASK-004-13).
- No new index. TASK-004-01 created `idx_deliveries_client_event_created_at`.

## Acceptance Criteria

- [ ] Every date predicate and the keyset bind `event_created_at`; the string `created_at` appears nowhere in this method's SQL.
- [ ] `client_id` is unconditional in every filter combination.
- [ ] The keyset uses row-value comparison `(event_created_at, delivery_id) < (:ts, :id)`.
- [ ] `ORDER BY event_created_at DESC, delivery_id DESC` matches the keyset direction.
- [ ] `limit + 1` is fetched and only `limit` rows are returned; `hasMore` derives from the extra row.
- [ ] Optional predicates are static fragments with named parameters; no user value is concatenated into SQL anywhere.
- [ ] Non-positive `limit` is rejected.
- [ ] `DeliveryPageCursor` is an immutable record; decode rejects malformed input with an exception rather than defaulting to page 1; decoded values are parsed to `Instant`/`UUID` before binding.
- [ ] The cursor javadoc states that it is opaque but not a security boundary and that the tenant never comes from it.
- [ ] The method comment explains why `event_created_at` and not `created_at`, citing ADR-003 A4.
- [ ] Tests written and passing: TASK-004-15 owns them and must be green before this task is `Ready for Review`.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition. SRP: the cursor codec is its own type, not four private methods on the repository.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A05:** the cursor is the one client-supplied structured value reaching a `WHERE` clause and is parsed to typed values before binding. **A01:** `client_id` unconditional in every branch. **A10:** a malformed cursor fails loudly rather than degrading to page 1.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
