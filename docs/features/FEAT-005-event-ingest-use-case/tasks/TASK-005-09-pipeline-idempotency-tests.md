---
id: TASK-005-09
feature: FEAT-005
title: "Testcontainers: live-pair conflict returns empty, and a terminal state frees the pair"
status: Ready for Review
agent: dba
depends_on: [TASK-005-08]
date: 2026-09-20
---

# TASK-005-09: pipeline idempotency tests

## Feature

FEAT-005

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/DeliveryIdempotentInsertTest.java` (new)
- Concern: the partial-index semantics the ingest use case depends on.

Testcontainers, real Postgres, Flyway-migrated. Seed a subscription and a `notification_events` row first — `deliveries.event_id` is a foreign key.

Cases:

1. **First `insertIfAbsent` returns the inserted row**, with `status = 'PENDING'`, `origin = 'INGEST'`, `attemptCount = 0`, and both `traceContext` and `eventCreatedAt` round-tripping.
2. **A second `insertIfAbsent` for the same `(event_id, subscription_id)` returns `Optional.empty()`**, throws nothing, and leaves the table with exactly one row for the pair.
3. **`findLiveByEventAndSubscription` then returns that first row**, same `delivery_id`. Cases 2 and 3 together are the sequence the ingest use case performs on a replay.
4. **A terminal state frees the pair.** Drive the row to `DELIVERED` (through `markDelivered`, not a raw `UPDATE` — use the port), then `insertIfAbsent` the same pair again: it now returns a **new** row with a different `delivery_id`, and `findLiveByEventAndSubscription` returns the new one, not the terminal one. Repeat for `DEAD`. This is ADR-003 §2's "once a delivery reaches a terminal state, the pair is free again" and it is what `POST /replay` later depends on — the test exists so a reader does not mistake it for a bug.
5. **A different `subscription_id` for the same `event_id` inserts normally** — the pair is the key, not the event alone. This is the fan-out case, and it is the one that would break if someone narrowed the index to `event_id`.
6. **`findLiveByEventAndSubscription` returns `empty()`** for a pair with no row, and for a pair whose only row is terminal.

## Out of Scope

- Any assertion about `notification_events`. TASK-005-07.
- Concurrency between two inserters. Resolved by Postgres unique-index insertion locking, as `V2`'s comment states; not re-tested here.
- The use case, the controller, SQS, or any Spring MVC context.
- Editing the adapter. A failure is TASK-005-08's to fix.

## Acceptance Criteria

- [ ] Testcontainers against real Postgres with Flyway applied; no H2, no mocks.
- [ ] All six cases present and passing.
- [ ] Case 4 covers both `DELIVERED` and `DEAD`, and drives the transition through the port rather than raw SQL.
- [ ] Case 2 asserts no exception and a row count of one.
- [ ] No test asserts on a log line.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A10:** cases 2 and 6 are the regression tests for the conflict being a value rather than a throw.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
