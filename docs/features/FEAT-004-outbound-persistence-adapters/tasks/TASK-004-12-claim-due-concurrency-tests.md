---
id: TASK-004-12
feature: FEAT-004
title: "Testcontainers: two concurrent transactions running claimDue claim disjoint batches"
status: Ready for Review
agent: dba
depends_on: [TASK-004-11]
date: 2026-09-20
---

# TASK-004-12: `claimDue` concurrency and predicate tests

## Feature

FEAT-004

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/ClaimDueConcurrencyTest.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/ClaimDuePredicateTest.java` (new)
- Concern: proving `SKIP LOCKED` and the six predicates behave as ADR-002 §2.1 claims.

Two files, because the concurrency proof and the predicate matrix need different fixtures and different lifecycles, and the concurrency one is the artifact worth reviewing on its own.

### `ClaimDueConcurrencyTest` — the user's stated acceptance property

**Two concurrent transactions running the due-query claim disjoint batches.**

Shape: seed N due deliveries (say 20). Open two real, separate connections and begin a transaction on each (`TransactionTemplate` on two threads, or two `DataSource` connections driven manually — but two genuinely concurrent transactions, not two sequential calls). Have both call `claimDue` with a limit smaller than N, holding both transactions open until both have returned, then commit.

Assertions:

1. **The two returned id sets are disjoint.** Set intersection is empty.
2. Neither call blocks on the other. Use a latch so the second call starts while the first transaction is still open, and bound the test with a timeout so a regression to plain `FOR UPDATE` (which blocks) fails instead of hanging.
3. The union is claimed exactly once: every returned row is `QUEUED` with `next_attempt_at` pushed, and no row appears twice.
4. A third sequential call after both commit returns the remaining rows and none of the already-claimed ones.
5. **`FOR UPDATE OF d` specifically:** seed two due deliveries sharing **one** `subscription_id`. Two concurrent `claimDue` calls with limit 1 each must return **both** rows, one apiece. Under a bare `FOR UPDATE ... SKIP LOCKED` the second call skips the row because the shared `subscriptions` row is locked, and returns empty. This test is the only thing that catches the `OF d` clause being dropped, and it must fail if it is.
6. **No active transaction:** calling `claimDue` outside a transaction throws `IllegalStateException` and **no row is modified**. Fail-closed (A10). Assert both the throw and the unchanged rows.

Virtual-thread note: the test's own threading uses `java.util.concurrent` (latches, executors). No `synchronized` in the adapter is exercised by this, and none should exist.

### `ClaimDuePredicateTest` — one test per predicate, each proving the row is excluded

7. `DELIVERED`, `DEAD`, `FAILED` rows are never returned.
8. `next_attempt_at > asOf` excluded; `<= asOf` included.
9. `PENDING` younger than the 30-second grace excluded; older included.
10. `PROCESSING` fresher than 60 seconds excluded; staler included (the crashed-worker reclaim).
11. `circuit_state = 'OPEN'` with an unelapsed cooldown excluded; **with an elapsed cooldown included** — the recovery trigger (ADR-002 §2.1, ADR-006 §1.2). Both halves.
12. `throttled_until` in the future excluded; null or past included.
13. `ORDER BY next_attempt_at` respected, and `LIMIT` honored exactly.
14. Every returned row is `QUEUED` with `next_attempt_at` advanced roughly five minutes, and **`event_created_at` unchanged** by the claim.
15. Empty result is an empty list, not null.
16. **Plan assertion:** `EXPLAIN (FORMAT JSON)` on the select shows `idx_deliveries_due` used and no sequential scan on `deliveries`. Follow the existing style in `src/test/java/com/cobre/challenge/schema/DeliveryDueQueryIndexTest.java`; seed enough rows that the planner would not prefer a seq scan on size alone.

Both files use real Postgres via `TestcontainersConfiguration`. **No H2, no mocked JDBC** — `SKIP LOCKED`, partial-index planning and `interval` arithmetic are all Postgres behaviors and a mock would assert nothing.

## Out of Scope

- `claimForProcessing` (TASK-004-08's file), the outcome writes (TASK-004-09), `deferDelivery` (TASK-004-10).
- `max_concurrency` and the `HALF_OPEN` one-probe cap. Not in the query (TASK-004-11's out-of-scope list); do not test for behavior the adapter does not implement.
- `promoteToHalfOpen`'s own write (TASK-004-19). Test 11 asserts only that the query *admits* a cooled-down subscription.
- SQS, the relay scheduler, throughput benchmarking.
- No LocalStack.

## Acceptance Criteria

- [ ] `ClaimDueConcurrencyTest` uses two genuinely concurrent open transactions, not two sequential calls, and asserts disjoint id sets.
- [ ] Test 2 is bounded by a timeout so a regression to blocking `FOR UPDATE` fails rather than hangs.
- [ ] Test 5 (two deliveries, one shared subscription, both claimed) exists and would fail if `OF d` were dropped.
- [ ] Test 6 asserts both the `IllegalStateException` and that no row changed.
- [ ] `ClaimDuePredicateTest` covers all six predicates including **both** halves of the circuit predicate.
- [ ] Test 14 asserts `event_created_at` unchanged by the claim.
- [ ] Test 16 asserts `idx_deliveries_due` in the plan with no seq scan on `deliveries`, in the existing schema-test style.
- [ ] No H2, no mocked JDBC or `ResultSet` in either file.
- [ ] Tests written and passing: `./gradlew test --tests "com.cobre.challenge.adapter.out.persistence.ClaimDue*"` green with Docker running, and the full suite still green. Both files must pass repeatedly, not once — a flaky concurrency test is worse than none, so if timing is fragile, use latches rather than sleeps.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A10:** test 6 is the fail-closed control for the missing-transaction path, which is the one failure here that would silently double-send in production. No real payload or secret in any fixture (**A09**).

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
