---
id: TASK-004-11
feature: FEAT-004
title: "claimDue: the relay due-query with FOR UPDATE SKIP LOCKED and its subscription join (correctness-critical)"
status: Ready for Review
agent: dba
depends_on: [TASK-004-07]
date: 2026-09-20
---

# TASK-004-11: `claimDue` — correctness-critical query 1 of 2

## Feature

FEAT-004

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/DeliveryPipelineJdbcRepository.java` (add one method)
- Concern: the relay's batch claim, ADR-002 §2.1.

`List<Delivery> claimDue(int batchLimit, Instant asOf)`.

### Reproduce ADR-002 §2.1's query, predicate for predicate

```sql
SELECT d.<explicit columns> FROM deliveries d
JOIN subscriptions s ON s.subscription_id = d.subscription_id
WHERE d.status IN ('PENDING', 'RETRYING', 'QUEUED', 'PROCESSING')
  AND d.next_attempt_at <= :as_of
  AND (d.status <> 'PENDING'    OR d.created_at < :as_of - interval '30 seconds')
  AND (d.status <> 'PROCESSING' OR d.updated_at < :as_of - interval '60 seconds')
  AND (s.circuit_state <> 'OPEN' OR s.circuit_opened_at < :as_of - s.circuit_backoff)
  AND (s.throttled_until IS NULL OR s.throttled_until <= :as_of)
ORDER BY d.next_attempt_at
LIMIT :batch_limit
FOR UPDATE OF d SKIP LOCKED
```

Then, **in the same transaction**, mark the returned batch `QUEUED` and push `next_attempt_at` forward by 5 minutes, returning the claimed rows.

Requirements that are not stylistic:

1. **`FOR UPDATE OF d SKIP LOCKED`, locking `deliveries` only.** A bare `FOR UPDATE ... SKIP LOCKED` also locks the joined `subscriptions` row, so two relay instances working on different deliveries of the same busy subscription would skip each other's rows and silently under-claim. `OF d` is load-bearing. ADR-002 §2.1's text writes the bare form; the `OF d` restriction is what implements its stated intent ("each takes a disjoint batch" of *deliveries*), and this deviation is intentional and must be commented as such.
2. **`asOf` replaces every `now()`.** One bound instant for all six predicates, so a single poll cycle cannot straddle a clock tick and evaluate the grace window against a different instant than the cooldown.
3. **The relay's five-minute push** (`next_attempt_at = :as_of + interval '5 minutes'`, or a bound value) is the lost-message recovery mechanism, not an optimization. Set `updated_at = :as_of` in the same statement.
4. **Assert an active transaction and fail closed.** `SKIP LOCKED` outside a transaction takes no lasting locks, so every relay instance claims the same batch and the whole design silently double-sends. Check `TransactionSynchronizationManager.isActualTransactionActive()` at the top and throw `IllegalStateException` before touching a row if false. This is A10: the error path must fail closed, loudly, not degrade. **Do not** add `@Transactional` to the adapter to make this pass — the transaction is the use case's (CLAUDE.md), and the assertion exists precisely to catch a caller that forgot it.
5. **`batchLimit` is bound, not interpolated**, and rejected if non-positive. ADR-002 §2.1's 500 is the caller's default, not this method's.
6. Explicit column list qualified `d.`; no `SELECT *` and no `d.*`, or the join's columns collide in the mapper.
7. Empty batch returns an empty list, never null (Effective Java Item 54).
8. The `interval` arithmetic stays in SQL. Do not compute `asOf - 30s` in Java; the ADR's predicates are the contract and a reviewer must be able to diff them against §2.1 line by line.

`promoteToHalfOpen` is **not** called from here. The relay use case issues it separately for subscriptions this query admitted as cooled-down (ADR-002 Amendment C2); this method only reads the circuit predicate.

## Out of Scope

- `promoteToHalfOpen` (TASK-004-19) and the `HALF_OPEN` one-probe cap. The effective-concurrency cap (0 / 1 / `max_concurrency`, ADR-006 §1.2-1.3) is not in this query and is not added here.
- `max_concurrency` and the per-subscription in-flight count. Not in ADR-002 §2.1's query text; not invented here.
- The concurrency and plan tests (TASK-004-12).
- `SendMessageBatch` and every other SQS call.
- The 5s `fixedDelay` scheduler and the batch-size default.
- No `@Transactional` on this class. See requirement 4.
- No index change. `idx_deliveries_due` already exists (FEAT-002); this task must not alter it.

## Acceptance Criteria

- [ ] All six `WHERE` predicates from ADR-002 §2.1 are present, in the same form, with `:as_of` in place of every `now()`.
- [ ] The lock clause is `FOR UPDATE OF d SKIP LOCKED`, with a comment explaining why `OF d` is required and noting it as an intentional refinement of §2.1's written form.
- [ ] `ORDER BY d.next_attempt_at` and a bound `LIMIT`.
- [ ] The `QUEUED` claim and the five-minute `next_attempt_at` push happen in the same method call and therefore the same caller transaction; `updated_at` is set.
- [ ] `TransactionSynchronizationManager.isActualTransactionActive()` is asserted first and throws `IllegalStateException` when false, before any statement executes.
- [ ] No `@Transactional` anywhere in the class.
- [ ] Non-positive `batchLimit` is rejected; the limit is bound, never interpolated.
- [ ] Explicit `d.`-qualified column list.
- [ ] Empty result returns an empty list.
- [ ] Interval arithmetic is in SQL, not Java.
- [ ] Tests written and passing: TASK-004-12 owns them and must be green before this task is `Ready for Review`.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition. Item 54 (empty collection over null).
- [ ] No new OWASP Top 10:2025 exposure introduced. **A10** is the operative category and criterion 5 (the transaction assertion) is the control. **A05:** `batchLimit` and `asOf` bound, intervals are SQL literals in static text. **A01:** deliberately cross-tenant; this is the platform's own scheduler, per ADR-007 §5.2 and Amendment E1.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
