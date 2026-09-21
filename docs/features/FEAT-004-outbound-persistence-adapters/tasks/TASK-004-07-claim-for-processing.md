---
id: TASK-004-07
feature: FEAT-004
title: "claimForProcessing: the conditional claim UPDATE guarded on status = 'QUEUED' (correctness-critical)"
status: Ready for Review
agent: dba
depends_on: [TASK-004-06]
date: 2026-09-20
---

# TASK-004-07: `claimForProcessing` — correctness-critical query 2 of 2

## Feature

FEAT-004

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

One method, isolated in its own task because it is the single mechanism preventing a double POST.

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/DeliveryPipelineJdbcRepository.java` (add one method)
- Concern: the worker's conditional claim, ADR-002 §2.2 step 1.

### The statement, which must match the ADR exactly

```sql
UPDATE deliveries
   SET status = 'PROCESSING'::delivery_status,
       updated_at = :now
 WHERE delivery_id = :delivery_id
   AND status = 'QUEUED'::delivery_status
```

Return `affectedRows == 1`.

Non-negotiable properties:

1. **The guard is `status = 'QUEUED'` and nothing else.** No `client_id`, no `attempt_count`, no `next_attempt_at` in the `WHERE` clause. Any extra predicate changes which races the claim wins and is a design change, not a hardening.
2. **`updated_at` is set explicitly**, bound from the caller's `now`. There is no trigger on this table (`V2`'s own comment says so), and ADR-002 §2.1's 60-second `PROCESSING` staleness reclaim reads this column: an unset `updated_at` makes a live delivery look reclaimable, which reintroduces the double-send this method exists to prevent.
3. **Zero rows affected returns `false`.** It does not throw, does not log at error, and is not an exceptional condition. ADR-002 §2.2 step 2 makes it the *designed* outcome for a redelivered message, followed by `DeleteMessage`. Throwing here would turn a benign duplicate into a crash loop at `maxReceiveCount = 3` and push a healthy delivery to the DLQ (A10).
4. **No other column moves.** Not `attempt_count`, not `next_attempt_at`, not `last_error`, not `delivered_at`, and not `event_created_at`.
5. **No `SELECT` before the `UPDATE`.** Read-then-update is the race this replaces. One statement.

Comment the method with its ADR-002 §2.2 step 1 citation and the ADR-003 Q3 reasoning: this conditional update, not the SQS `VisibilityTimeout`, is what makes a duplicate receive safe.

## Out of Scope

- The other conditional operations (TASK-004-09, -10) and `claimDue` (TASK-004-11).
- Tests. TASK-004-08 owns them, including the zero-row property.
- No `@Transactional`. A single conditional `UPDATE` is atomic on its own; the caller's transaction is the use case's business.
- No `SELECT ... FOR UPDATE`, no advisory lock, no optimistic-version column.
- No SQS interaction of any kind.

## Acceptance Criteria

- [ ] One method, one `UPDATE`, returning `boolean` from the affected row count.
- [ ] The `WHERE` clause is exactly `delivery_id = :id AND status = 'QUEUED'`.
- [ ] `updated_at` is set from the bound `now`.
- [ ] No exception is thrown on zero rows and no error-level log is emitted.
- [ ] No column other than `status` and `updated_at` appears in the `SET` clause.
- [ ] Both bound values are named parameters; the enum literals are explicitly cast.
- [ ] No preceding `SELECT` in the method.
- [ ] The method comment cites ADR-002 §2.2 step 1 and states why zero rows is normal.
- [ ] Tests written and passing: owned by TASK-004-08, which must be green before this task is `Ready for Review`.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A10** is the operative category and criterion 3 above is the control: this method's error path must fail closed by returning `false`, not by throwing. **A05:** bound parameters only.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
