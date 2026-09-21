---
id: TASK-004-09
feature: FEAT-004
title: "The four worker outcome writes: markDelivered, scheduleRetry, markDead, markFailed, with tests"
status: Ready for Review
agent: dba
depends_on: [TASK-004-07]
date: 2026-09-20
---

# TASK-004-09: the four outcome writes

## Feature

FEAT-004

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

The four transitions that record an attempt's outcome, per ADR-003 Amendment A1's table.

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/DeliveryPipelineJdbcRepository.java` (add four methods)
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/DeliveryOutcomeWritesTest.java` (new)
- Concern: the worker's outcome writes. Four methods, one concern, two files.

Each is **one** conditional `UPDATE`, returning `affectedRows == 1`. Every one sets `updated_at` explicitly from the caller's `Instant` (no trigger on this table; ADR-002 §2.1's staleness reclaim reads it).

| Method | `WHERE` | `SET` beyond `status`, `updated_at` |
|---|---|---|
| `markDelivered(id, deliveredAt)` | `status = 'PROCESSING'` | `delivered_at = :deliveredAt`, `next_attempt_at = NULL` |
| `scheduleRetry(id, nextAttemptAt, lastError, now)` | `status = 'PROCESSING'` | `attempt_count = attempt_count + 1`, `next_attempt_at = :nextAttemptAt`, `last_error = :lastError` |
| `markDead(id, lastError, now)` | `status = 'PROCESSING'` | `next_attempt_at = NULL`, `last_error = :lastError` |
| `markFailed(id, lastError, now)` | `status IN ('QUEUED', 'PROCESSING')` | `last_error = :lastError` |

Details that are easy to get wrong:

- **`markDelivered` uses `deliveredAt` for both `delivered_at` and `updated_at`.** One instant, one attempt outcome.
- **`attempt_count` increments in SQL** (`attempt_count = attempt_count + 1`), never from a value the caller read first. Read-then-write here would let two writers land the same count.
- **`next_attempt_at = NULL` on both terminal transitions.** ADR-003 §1.1 requires it for `markDead`, and it matters operationally: a terminal row with a non-null `next_attempt_at` is a row the due-query's partial index still considers, which is a slow leak into every relay poll.
- **`markFailed`'s guard is the status set, not a single state.** ADR-003 §1's machine admits `QUEUED -> FAILED` and `PROCESSING -> FAILED` because a message reaches the DLQ from either and the consumer does not know which. The three terminal states must stay excluded so a late DLQ message cannot overwrite a `DELIVERED` row.
- **`last_error` is bound, possibly null, and truncated by the caller if needed.** There is no length limit on this column (unlike `response_excerpt`), but do not log its contents (**A09**).
- **`event_created_at` appears in none of the four statements.** This is where ADR-003 A4's immutability rule is actually enforced.

### Required tests

Per method: the happy path asserting `true` and every column's new value; every disallowed prior status asserting `false` **and** that no column moved (parameterized over `DeliveryStatus`); a non-existent id asserting `false` without throwing.

Then the four cross-cutting properties:

1. `scheduleRetry` called twice on a row re-claimed between calls advances `attempt_count` by exactly 2.
2. `markFailed` on a `DELIVERED` row returns `false` and leaves the row `DELIVERED`. The late-DLQ-message case.
3. `markFailed` succeeds from **both** `QUEUED` and `PROCESSING`.
4. **After each of the four, `event_created_at` and `created_at` are unchanged.** The immutability assertion.

## Out of Scope

- `deferDelivery` (TASK-004-10). It is a different concern: no attempt occurred.
- `claimForProcessing` (TASK-004-07), `claimDue` (TASK-004-11).
- The `delivery_attempts` insert (TASK-004-16). These four write the `deliveries` row only; pairing them into one transaction is the use case's job.
- No circuit or throttle write. `SubscriptionRepositoryPort`'s, TASK-004-18/-19.
- No retry-schedule computation. `nextAttemptAt` arrives computed; ADR-004 §1's `RetryPolicy` is the domain's.
- No `@Transactional`, no response classification, no HTTP status interpretation.

## Acceptance Criteria

- [ ] Four methods, one `UPDATE` each, each returning `boolean` from the affected row count, none throwing on zero rows.
- [ ] Every one sets `updated_at` explicitly; `markDelivered` uses `deliveredAt` for it.
- [ ] `attempt_count` increments in SQL and appears in `scheduleRetry` only.
- [ ] `next_attempt_at = NULL` in `markDelivered` and `markDead`.
- [ ] `markFailed`'s guard is `status IN ('QUEUED', 'PROCESSING')` and excludes all three terminal states.
- [ ] `event_created_at` appears in none of the four `SET` clauses.
- [ ] All values bound as named parameters; enum literals explicitly cast.
- [ ] Tests written and passing: the per-method matrix plus the four cross-cutting properties above, against real Postgres via `TestcontainersConfiguration`. No H2, no mocked JDBC. `./gradlew test --tests "com.cobre.challenge.adapter.out.persistence.DeliveryOutcomeWritesTest"` green.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A10:** all four return `false` on a lost race rather than throwing, asserted per method. **A09:** `last_error` is never logged. **A05:** bound parameters only.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

**Note:** could not confirm a green `./gradlew test` run in this session — see report below for why, and for the one thing still worth an eyeball before this moves further.
