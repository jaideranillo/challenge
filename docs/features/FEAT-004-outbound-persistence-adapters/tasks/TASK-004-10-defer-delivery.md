---
id: TASK-004-10
feature: FEAT-004
title: "deferDelivery: push next_attempt_at on a QUEUED row without touching attempt_count, last_error or delivery_attempts"
status: Not Started
agent: dba
depends_on: [TASK-004-07]
date: 2026-09-20
---

# TASK-004-10: `deferDelivery`

## Feature

FEAT-004

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

The deferral write behind ADR-002 §2.2 steps 3 and 4. Its own task, because it is defined by what it must **not** do, and that is a property worth reviewing on its own.

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/DeliveryPipelineJdbcRepository.java` (add one method)
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/DeferDeliveryTest.java` (new)
- Concern: deferring a delivery for which no attempt occurred.

### The statement

```sql
UPDATE deliveries
   SET next_attempt_at = :next_attempt_at,
       updated_at = now()
 WHERE delivery_id = :delivery_id
   AND status = 'QUEUED'::delivery_status
```

Return `affectedRows == 1`.

### What makes this operation what it is

Both ADR-002 §2.2 step 3 (bulkhead-acquire timeout) and step 4 (open circuit) hit this path, and both say the same thing: **no attempt occurred.** Therefore:

- **`status` does not change.** The row stays `QUEUED`. This is the one conditional operation in the pipeline whose guard column is not also in its `SET` clause, and that is correct, not an oversight: the row is already in the state it should be in; only its schedule moves.
- **`attempt_count` is not incremented.** A deferral that consumed retry budget would burn ADR-004 §1's six-step schedule on attempts that never happened, and a client behind a slow endpoint would reach `DEAD` without ever having been POSTed to.
- **`last_error` is not written.** There was no error; there was no request.
- **No `delivery_attempts` row.** Not from this method and not from its caller. ADR-003 §3's attempt history must contain attempts, and a deferral would pollute the audit trail the client can query (ADR-005 §1).
- **`delivered_at` and `event_created_at` are not touched.**

`updated_at` comes from the database's `now()`, because the directive fixed this method's arity at two parameters and there is no caller `Instant` to bind. This is a deliberate, logged inconsistency with the rest of the port (`docs/concerns.md`): **do not add a third parameter to "fix" it.** The test consequence is that `updated_at` is asserted as a bound (it advanced) rather than as an exact value.

Comment the method with the citation and the three must-nots, so the next person to touch it does not add an `attempt_count` increment while "making it consistent" with `scheduleRetry`.

### Required tests

1. `QUEUED` row: returns `true`, `next_attempt_at` becomes the bound value, `updated_at` advanced.
2. **The must-not-touch assertion, which is the point of the task:** after a successful defer, `status` is still `QUEUED`, and `attempt_count`, `last_error`, `delivered_at`, `event_created_at` and `created_at` are all identical to their pre-call values.
3. **No `delivery_attempts` row exists** for the delivery after the defer. Count rows for the `delivery_id`, assert zero.
4. Every non-`QUEUED` status returns `false` and mutates nothing. Parameterized over `DeliveryStatus` minus `QUEUED`.
5. A non-existent id returns `false` without throwing.
6. Two consecutive defers both return `true` and leave `attempt_count` at its original value. This is the sustained-bulkhead-pressure case (ADR-006 §1.1) and the one that would expose an accidental increment.

## Out of Scope

- The jitter and the 10-20s window. `nextAttemptAt` arrives computed; ADR-002 §2.2 step 3's interval is the use case's.
- SQS. ADR-006 §1.1 replaced `ChangeMessageVisibility` with this write plus a `DeleteMessage`; the delete is the consumer adapter's, not this one's.
- The bulkhead semaphore and the circuit read. `deferDelivery` does not decide *whether* to defer.
- The four outcome writes (TASK-004-09), `claimForProcessing` (TASK-004-07), `claimDue` (TASK-004-11).
- No `delivery_attempts` write of any kind, which is the entire point.

## Acceptance Criteria

- [ ] One method, one `UPDATE`, returning `boolean`; never throws on zero rows.
- [ ] The `SET` clause contains exactly `next_attempt_at` and `updated_at`. No `status`, no `attempt_count`, no `last_error`.
- [ ] The guard is `delivery_id = :id AND status = 'QUEUED'`.
- [ ] The method comment cites ADR-002 §2.2 steps 3-4 and lists the three must-nots.
- [ ] The two-parameter arity is preserved; no third `Instant` parameter was added.
- [ ] Tests written and passing: all six above, against real Postgres via `TestcontainersConfiguration`. Test 3 queries `delivery_attempts` directly and asserts zero rows. No H2, no mocked JDBC. `./gradlew test --tests "com.cobre.challenge.adapter.out.persistence.DeferDeliveryTest"` green.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A10** is the operative one twice over: the `false` return on a lost race keeps the deferral path from throwing inside the worker, and the untouched `attempt_count` keeps a deferral from silently consuming the retry budget for an attempt that never happened. Both are asserted.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Not Started <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
