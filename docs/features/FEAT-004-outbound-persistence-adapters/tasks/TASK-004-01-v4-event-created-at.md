---
id: TASK-004-01
feature: FEAT-004
title: "V4 migration: denormalize event_created_at onto deliveries and swap the list-endpoint index"
status: Ready for Review
agent: dba
depends_on: []
date: 2026-09-20
---

# TASK-004-01: `V4` migration — `event_created_at`

## Feature

FEAT-004

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

A new additive Flyway migration implementing ADR-003 Amendment A4 and ADR-005 Amendment D2.

- File(s):
  - `src/main/resources/db/migration/V4__deliveries_event_created_at.sql` (new)
- Concern: the denormalized event timestamp and the index that serves it.

### Why a new version and not an edit to `V2`

**Do not edit `V2__deliveries.sql`.** Flyway records a checksum per applied migration and validates it on every start. `V1`-`V3` have already been applied to the local compose database and to every Testcontainers run in FEAT-002's suite, so an in-place edit fails validation for any database that already ran it, recoverable only by `flyway repair` or a drop. Applied migrations are immutable; this is a forward migration.

### Statement order, which is not optional

```
1. ALTER TABLE deliveries ADD COLUMN event_created_at timestamptz;      -- nullable first
2. UPDATE deliveries d SET event_created_at = e.created_at
     FROM notification_events e WHERE e.event_id = d.event_id;          -- backfill
3. ALTER TABLE deliveries ALTER COLUMN event_created_at SET NOT NULL;
4. CREATE INDEX idx_deliveries_client_event_created_at
     ON deliveries (client_id, event_created_at);
5. DROP INDEX idx_deliveries_client_created_at;
```

`NOT NULL` cannot be added before the column has values. The table is empty today, so the backfill is a no-op in practice, but a migration that only works on an empty table is a latent failure and this one must be correct against a populated table.

**No `DEFAULT`, and no trigger.** The value is supplied by the adapter's `INSERT` from the `Delivery` aggregate (TASK-004-06). A `DEFAULT now()` would silently mask a missing value and produce the exact bug Amendment A4 exists to prevent: a replayed row filed under the replay's timestamp rather than the event's. Immutability is likewise not enforced by a trigger, consistent with ADR-003 §3's existing rejection of triggers for the idempotency invariant; it is an adapter property asserted by TASK-004-09 and TASK-004-10.

### The index swap

`idx_deliveries_client_created_at` is dropped because after the filter moves it has no consumer. The due-query's `d.created_at < now() - interval '30 seconds'` predicate (ADR-002 §2.1's `PENDING` grace) is served by `idx_deliveries_due`, not by this index. **The `created_at` column itself stays** and is not touched.

`V2`'s committed comment block still describes the dropped index and cannot be edited. `V4` therefore carries a comment stating that it supersedes `V2`'s `idx_deliveries_client_created_at` and why, so the next reader of `V2` is not misled.

### Comments

Match the style `V1`-`V3` already use (`COMMENT ON COLUMN`, `COMMENT ON INDEX`). The column comment must state: denormalized from `notification_events.created_at`, written once at insert, **never updated**, and that it exists because `deliveries.created_at` is when a replay was requested rather than when the event happened (ADR-003 A4, ADR-005 D2).

## Out of Scope

- **No edit to `V1`, `V2` or `V3`.** Not even a comment.
- No other column. `trace_context` already exists in `V2` and needs no migration.
- No `DEFAULT`, no trigger, no check constraint on the new column.
- No partitioning and no retention (ADR-003 §3 defers both).
- No RLS policy and no database role (deferred to ADR-007's feature).
- No adapter code and no Java of any kind.
- No change to `TestcontainersConfiguration`.

## Acceptance Criteria

- [ ] `V4__deliveries_event_created_at.sql` exists; `V1`-`V3` are byte-identical to their committed state.
- [ ] Statements are in the order add-nullable, backfill, `SET NOT NULL`, create new index, drop old index.
- [ ] The backfill joins `notification_events` on `event_id` and would be correct against a populated table.
- [ ] `event_created_at` is `NOT NULL` with **no** `DEFAULT`.
- [ ] `idx_deliveries_client_event_created_at` exists on `(client_id, event_created_at)`; `idx_deliveries_client_created_at` is dropped.
- [ ] The `created_at` column is unchanged and still present.
- [ ] Column and index comments follow `V1`-`V3`'s convention and state the immutability rule and the supersession of `V2`'s index.
- [ ] Tests written and passing: `./gradlew test` is green with Docker running, which exercises `V4` through every existing `@SpringBootTest`. FEAT-002's `DeliveryDueQueryIndexTest` and `DeliveryIdempotencyIndexTest` must still pass unmodified; if either breaks, `V4` did something it should not have.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition (insofar as they apply to DDL: one concern, nothing speculative).
- [ ] No new OWASP Top 10:2025 exposure introduced. Static DDL, no injection surface.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
