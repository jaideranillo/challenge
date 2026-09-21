---
id: TASK-005-08
feature: FEAT-005
title: "Pipeline adapter: ON CONFLICT DO NOTHING inferred on the partial unique index, and the live-pair read"
status: Ready for Review
agent: dba
depends_on: [TASK-005-04]
date: 2026-09-20
---

# TASK-005-08: `insertIfAbsent` and `findLiveByEventAndSubscription`

## Feature

FEAT-005

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/DeliveryPipelineJdbcRepository.java` (add two methods)
- Concern: implementing ADR-003 §2's idempotency outcome as a return value instead of an exception.

**`insertIfAbsent`.** The same column list and bindings `insert` already uses — including `trace_context` (ADR-003 A3) and `event_created_at` (A4) — with a conflict clause and a `RETURNING`:

```sql
INSERT INTO deliveries (...) VALUES (...)
ON CONFLICT (event_id, subscription_id)
  WHERE status NOT IN ('DELIVERED','DEAD','FAILED')
  DO NOTHING
RETURNING ...
```

**The inference clause's `WHERE` must repeat `idx_deliveries_live_pair`'s predicate verbatim** (`V2` line 60-61). Postgres cannot infer a partial unique index without it, and the statement will fail at runtime with "there is no unique or exclusion constraint matching the ON CONFLICT specification" rather than at compile time. Write the predicate in the same order and with the same literals as the index, and comment the coupling — if the index ever changes, this statement changes with it.

Zero rows returned means a live row exists for the pair: return `Optional.empty()`. Do not follow it with a read inside this method; the port's second method is the caller's explicit next step, and folding it in here would hide a second round trip behind an insert.

**Reuse, do not duplicate.** `insert` and `insertIfAbsent` share their column list, bindings and `RETURNING` mapping; extract the shared parts into one private helper rather than copying the statement. A drift between the two would mean a delivery created by ingest and one created by replay carry different columns.

**`findLiveByEventAndSubscription`.** A `SELECT` with `event_id = :event_id AND subscription_id = :subscription_id AND status NOT IN ('DELIVERED','DEAD','FAILED')`, using the same `DeliveryRowMapper` every other read uses, returning `Optional`. At most one row, guaranteed by the unique partial index; if more than one comes back, that is a broken invariant and must fail loudly rather than pick one.

Enum literals get their explicit `::delivery_status` casts, per the conventions TASK-004-05 established.

## Out of Scope

- Any change to `insert`'s behavior or signature, and no deprecation of it.
- Any conditional write (`claimForProcessing`, `markDelivered`, `scheduleRetry`, `markDead`, `markFailed`, `deferDelivery`) or `claimDue`.
- `DeliveryQueryJdbcRepository`.
- Tests. TASK-005-09.
- Any migration or index change. `idx_deliveries_live_pair` is used as it is.
- `@Transactional` on the adapter.

## Acceptance Criteria

- [ ] Both methods implemented; every existing method's behavior unchanged.
- [ ] The `ON CONFLICT` inference clause repeats the partial index's `WHERE` predicate verbatim, with a comment naming `idx_deliveries_live_pair` and the coupling.
- [ ] Zero rows returned yields `Optional.empty()`; no `DuplicateKeyException` can escape either method.
- [ ] The column list, bindings and `RETURNING` mapping are shared with `insert` through one helper, not duplicated.
- [ ] `insertIfAbsent` writes `trace_context` and `event_created_at`, and no `UPDATE` in the class touches `event_created_at`.
- [ ] `findLiveByEventAndSubscription` uses the identical live predicate and fails loudly on more than one row.
- [ ] Bound parameters only; explicit enum casts.
- [ ] Tests written and passing: owned by TASK-005-09, which must be green before this task is `Ready for Review`.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A05:** bound parameters only. **A10:** a conflict is a return value here, not an exception — this is the whole point of the task.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
