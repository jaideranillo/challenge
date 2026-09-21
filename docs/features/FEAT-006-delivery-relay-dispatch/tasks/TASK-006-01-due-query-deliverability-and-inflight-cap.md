---
id: TASK-006-01
feature: FEAT-006
title: Due-query — deliverability gate and per-subscription effective in-flight cap
status: Ready for Review
agent: dba
depends_on: []
date: 2026-09-21
---

# TASK-006-01: Due-query — deliverability gate and per-subscription effective in-flight cap

## Feature

FEAT-006

## Assigned Agent

`dba` — this task is only for this agent. It is one SQL statement and the port javadoc that
describes it. The use case that calls it is TASK-006-06 and is not yours.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/DeliveryPipelineJdbcRepository.java`
    (modify `claimDue` only)
  - `src/main/java/com/cobre/challenge/application/port/out/persistence/DeliveryPipelineRepositoryPort.java`
    (javadoc of `claimDue` only — no signature change)
- Concern: the relay's claim statement admits exactly the rows ADR-002 §2.1 says it may.

### What to add

Two mandatory exclusions, on top of the six predicates already in the statement. **Do not
change, reorder, or reword the six existing predicates** — they reproduce ADR-002 §2.1 verbatim
and are already covered by tests.

**1. Deliverability gate.** A row is a candidate only if its subscription is deliverable:

```
AND s.active
AND s.verification_state = 'VERIFIED'
```

ADR-005 §2: a subscription is not deliverable until its target URL is verified, and the merged
domain model already encodes the pair as `Subscription.isDeliverable()`. Enqueuing work for an
inactive or unverified subscription hands the worker a delivery it is forbidden to attempt.

**2. Per-subscription effective in-flight cap.** ADR-002 §2.1: the cap is `0` while
`OPEN`-and-cooling, `1` while `HALF_OPEN`, and `max_concurrency` while `CLOSED`. ADR-006 §1.2
calls the middle value the one-probe rule.

Effective cap:

```
CASE WHEN s.circuit_state = 'CLOSED' THEN s.max_concurrency ELSE 1 END
```

The `0` case needs no branch: an `OPEN` circuit that is still cooling is already excluded by the
existing circuit predicate, so any row that reaches the cap check with `circuit_state = 'OPEN'` is
by definition a cooled-down probe candidate and its cap is `1`. `HALF_OPEN` is `1` for the same
reason. **Write it as the two-branch `CASE` above and say so in a comment** — a three-branch
`CASE` with a `0` arm would be dead code that reads as if it did something.

Admission rule:

- `already_in_flight(subscription)` = the number of `deliveries` rows for that subscription with
  `status IN ('QUEUED', 'PROCESSING')` that this cycle's candidate predicate does **not** admit —
  that is, rows genuinely being worked on rather than reclaimable ones. A stale `PROCESSING` row
  (`updated_at` older than 60s) and a `QUEUED` row whose `next_attempt_at` has already elapsed are
  candidates, not in-flight, and must not be double-counted against the cap.
- `remaining_allowance(subscription)` = `GREATEST(0, effective_cap - already_in_flight)`.
  `GREATEST` is not decoration: `already_in_flight` can legitimately exceed the cap (an operator
  lowered `max_concurrency`, or a circuit moved `CLOSED -> HALF_OPEN` while rows were in flight),
  and a negative `LIMIT` is a runtime error in PostgreSQL.
- A subscription contributes **at most `remaining_allowance` rows to the batch**, and they are its
  oldest candidates ordered by `(d.next_attempt_at, d.delivery_id)`. The `delivery_id` tiebreak is
  required: without it the order is nondeterministic for rows sharing a `next_attempt_at` and two
  relay instances can order the same set differently.

### The candidate query must be bounded per subscription, not globally

**This is the load-bearing part of this task.** Do not select a global pool of candidates and then
filter it by the cap. That shape starves subscriptions: one saturated subscription with thousands
of due rows fills the global `LIMIT` with rows the cap then discards, and a second subscription
with a single due row is never even looked at. The cap must bound the candidate selection
*per subscription*, before the global batch limit is applied.

Structure the candidate CTE as a **`LATERAL` join driven by `subscriptions`**:

```
outer relation : subscriptions s
                   filtered by the subscription-side predicates
                   (deliverability gate, circuit predicate, throttle predicate)
  CROSS JOIN LATERAL (scalar: remaining_allowance for s)            AS a
  CROSS JOIN LATERAL (SELECT d.delivery_id, d.next_attempt_at
                      FROM deliveries d
                      WHERE d.subscription_id = s.subscription_id
                        AND <the delivery-side predicates, unchanged>
                      ORDER BY d.next_attempt_at, d.delivery_id
                      LIMIT a.remaining_allowance
                      FOR UPDATE OF d SKIP LOCKED)                  AS d
outer ORDER BY d.next_attempt_at, d.delivery_id
outer LIMIT :batch_limit
```

Required properties of that shape, each of which you must preserve:

1. **`CROSS JOIN LATERAL` / `JOIN LATERAL ... ON TRUE` — never `LEFT JOIN LATERAL`.** PostgreSQL
   rejects a locking clause on the nullable side of an outer join, and a subscription with no
   admissible candidate must drop out of the result rather than contribute a null row.
2. **`ORDER BY` and `LIMIT` appear at two levels, and they do different jobs.** The *inner*
   `ORDER BY ... LIMIT a.remaining_allowance` is the cap: it is what makes one subscription unable
   to contribute more rows than it is allowed. The *outer* `ORDER BY ... LIMIT :batch_limit` is the
   batch bound: it picks the globally oldest rows from an already-capped, already-locked set.
   Removing either one is a correctness bug, not an optimization.
3. **`FOR UPDATE OF d SKIP LOCKED` moves inside the inner `LATERAL` subquery**, where `deliveries`
   is scanned. Keep `OF d` — the existing comment explains why it is load-bearing; it now also
   matters because `subscriptions` is the outer relation of the join and must never be locked by
   the relay. PostgreSQL plans the inner subquery as `Limit -> LockRows -> ...`, so a row skipped
   by `SKIP LOCKED` does **not** consume the inner `LIMIT`: a concurrent relay holding one of this
   subscription's candidate rows makes this cycle reach past it to the next unlocked candidate,
   which is exactly the intended behaviour.
4. **No window function is needed any more.** The previous `ROW_NUMBER() OVER (PARTITION BY ...)`
   ranking is deleted, not moved — the inner `ORDER BY ... LIMIT` expresses the same ranking and,
   unlike a window function, is legal in the same query level as `FOR UPDATE`. Do not reintroduce
   a window function anywhere in this statement.
5. **The subscription-side predicates stay in the outer relation**, evaluated once per
   subscription, not once per candidate row. The delivery-side predicates stay inside the inner
   subquery, textually unchanged.
6. The admitted `delivery_id`s from that CTE feed the existing `UPDATE ... RETURNING` CTE exactly
   as before. That CTE is unchanged.

**The accepted consequence is still that a cycle briefly locks rows it then declines to return** —
now only those rows trimmed by the *outer* `LIMIT :batch_limit`, which is bounded by the sum of the
per-subscription allowances rather than by the size of the due backlog. Those rows are skipped by
concurrent relays for the few milliseconds the transaction lives, whereas filtering after the
`UPDATE` would mean rows were already moved to `QUEUED` before the cap was checked, which is the
over-admission the cap exists to prevent.

A cycle may return fewer than `batch_limit` rows. That is correct and intended: ADR-002 §2.1
requires a *bounded* claim batch, and rows not admitted this cycle are picked up on the next one.
Do not grow the candidate pool to "fill" the batch.

`:as_of` continues to replace every `now()`, in the added predicates and in both `LATERAL` bodies.
One cycle, one clock.

### Report, do not fix, if the plan is bad

The driving relation is now `subscriptions`, so the plan shape changes. If `EXPLAIN (ANALYZE,
BUFFERS)` shows the outer relation is a sequential scan over all subscriptions, or the inner
`LATERAL` does not use `idx_deliveries_due` / `idx_deliveries_subscription_status`, **report it in
your handoff**. Adding an index is a separate `dba` task with its own migration, not part of this
one.

## Out of Scope

- The six existing predicates, the `UPDATE` CTE's `SET` clause, the 5-minute push interval, the
  `RETURNING` column list, the transaction assertion, and every other method on this class.
- Any migration. There is no `V5` in this feature. `subscriptions.active`,
  `verification_state` and `max_concurrency` all exist since `V1`;
  `idx_deliveries_subscription_status` exists since `V2`.
- Any new index. If your `EXPLAIN` shows a sequential scan for the in-flight count, **report it in
  your handoff** rather than adding an index here.
- `SubscriptionRepositoryPort` and `promoteToHalfOpen`. Promotion is TASK-006-06's call, not a
  predicate of this statement.
- Tests. TASK-006-02 owns them.

## Acceptance Criteria

- [ ] `claimDue` excludes rows whose subscription has `active = false`.
- [ ] `claimDue` excludes rows whose subscription has `verification_state <> 'VERIFIED'`.
- [ ] The effective cap is the two-branch `CASE` above, with a comment stating why there is no
      `0` arm.
- [ ] `already_in_flight` counts only non-candidate `QUEUED`/`PROCESSING` rows; a stale
      `PROCESSING` row and an elapsed `QUEUED` row are not counted against the cap.
- [ ] Candidate selection is bounded **per subscription** by a `LATERAL` subquery whose
      `ORDER BY (next_attempt_at, delivery_id) LIMIT remaining_allowance` applies the cap *before*
      the outer `LIMIT :batch_limit`. No global candidate pool is filtered by the cap afterwards.
- [ ] `remaining_allowance` is `GREATEST(0, effective_cap - already_in_flight)`, so the inner
      `LIMIT` can never be negative.
- [ ] The join is `CROSS JOIN LATERAL` / `JOIN LATERAL ... ON TRUE`, never `LEFT JOIN LATERAL`.
- [ ] No window function remains anywhere in the statement; the previous
      `ROW_NUMBER() OVER (PARTITION BY ...)` ranking is deleted.
- [ ] `FOR UPDATE OF d SKIP LOCKED` sits inside the inner `LATERAL` subquery and still applies to
      `deliveries` only — `subscriptions` is never locked by this statement.
- [ ] The outer `ORDER BY d.next_attempt_at, d.delivery_id` + `LIMIT :batch_limit` is retained.
- [ ] Every value is bound through `MapSqlParameterSource`. No predicate, interval, cap, or enum
      literal is concatenated from a caller-supplied value (OWASP A05).
- [ ] The six pre-existing predicates are textually unchanged.
- [ ] The port javadoc on `claimDue` states both added exclusions and the three cap values, so an
      implementer cannot drop them without contradicting the contract. Signature unchanged.
- [ ] `./gradlew test` passes — in particular the merged `ClaimDuePredicateTest` and
      `ClaimDueConcurrencyTest` still pass unchanged. If one now fails because its fixture builds
      a subscription that is not `VERIFIED`, **fix the fixture, not the predicate**, and say so in
      your handoff.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable)

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
