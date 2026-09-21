---
id: TASK-005-07
feature: FEAT-005
title: "Testcontainers: the event insert is idempotent and non-destructive"
status: Ready for Review
agent: dba
depends_on: [TASK-005-06]
date: 2026-09-20
---

# TASK-005-07: `NotificationEventJdbcRepository` tests

## Feature

FEAT-005

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/NotificationEventJdbcRepositoryTest.java` (new)
- Concern: proving the idempotency mechanism of ADR-002 §1.1 step 3 against a real Postgres.

Testcontainers, real Postgres, Flyway-migrated — the pattern `DeliveryPipelineJdbcRepositoryTest` already uses. No H2, no mocks.

Cases:

1. **First insert returns `true`** and every column round-trips: `event_id`, `client_id`, `event_type`, `content`, `created_at`. Assert `created_at` equals the value passed in, to the precision `timestamptz` preserves — not "approximately now". This is the assertion that catches a `DEFAULT now()` or a `now()` in the statement.
2. **Second insert of the same `event_id` returns `false`** and throws nothing.
3. **The second insert is non-destructive.** Insert `E` with `content = "A"`, then insert `E` again with `client_id`, `event_type`, `content` and `created_at` all different. Re-read: every column still holds the first insert's values, and the table holds exactly one row for `E`. This is the test that would fail if someone later "improved" `DO NOTHING` into `DO UPDATE`.
4. **`findById` returns `empty()` for an unknown `event_id`** and does not throw.
5. **`findById` returns the stored row** for a known one, with `createdAt` matching case 1's stored value.

## Out of Scope

- Any `deliveries` assertion. TASK-005-09.
- Concurrency. Two concurrent inserts of the same `event_id` are resolved by ordinary primary-key insertion locking, the same mechanism `V2`'s comment describes for the partial index; no test is written for a Postgres guarantee.
- Use case, controller, or SQS. Nothing in this test builds a Spring MVC context.
- Editing the adapter. If a case fails, the fix belongs to TASK-005-06's file and that task's author.

## Acceptance Criteria

- [ ] Testcontainers against real Postgres with Flyway applied; no H2, no mocked `JdbcTemplate`.
- [ ] All five cases above present and passing.
- [ ] Case 3 asserts column-by-column that the stored row is unchanged, and asserts the row count is one.
- [ ] Case 1 asserts `created_at` equality against the supplied value, not proximity to `now()`.
- [ ] No test asserts on a log line, and no test prints `content`.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A10:** case 2 is the regression test for "a replay must not be an exception".

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
