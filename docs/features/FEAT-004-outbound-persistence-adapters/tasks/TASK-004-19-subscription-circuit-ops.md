---
id: TASK-004-19
feature: FEAT-004
title: "SubscriptionJdbcRepository: tripCircuit, reopenCircuit, promoteToHalfOpen, closeCircuit with their differing guards, plus tests"
status: Ready for Review
agent: dba
depends_on: [TASK-004-04, TASK-004-18]
date: 2026-09-20
---

# TASK-004-19: the four circuit operations

## Feature

FEAT-004

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

The adapter half of ADR-006 Amendments B1, B2 and B3.

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/SubscriptionJdbcRepository.java` (add four methods)
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/SubscriptionCircuitOpsTest.java` (new)
- Concern: the circuit-breaker state transitions.

Each is **one** conditional `UPDATE` returning `affectedRows == 1`, first-writer-wins, never throwing on zero rows. ADR-006 §1.2 is explicit that only transitions are persisted, never a per-attempt counter, so each of these runs at most once per state change.

### The four statements

**`tripCircuit(id, baseCooldown, maxCooldown, now)` — guard `CLOSED`**

```sql
UPDATE subscriptions
   SET circuit_state = 'OPEN'::circuit_state,
       circuit_opened_at = :now,
       circuit_backoff = LEAST(:base_cooldown * POWER(2, consecutive_opens), :max_cooldown),
       consecutive_opens = consecutive_opens + 1,
       updated_at = :now
 WHERE subscription_id = :subscription_id
   AND circuit_state = 'CLOSED'::circuit_state
```

**`reopenCircuit(id, baseCooldown, maxCooldown, now)` — guard `HALF_OPEN`**: identical `SET` clause, `AND circuit_state = 'HALF_OPEN'`.

**`promoteToHalfOpen(id, asOf)`**

```sql
UPDATE subscriptions
   SET circuit_state = 'HALF_OPEN'::circuit_state, updated_at = :as_of
 WHERE subscription_id = :subscription_id
   AND circuit_state = 'OPEN'::circuit_state
   AND circuit_opened_at < :as_of - circuit_backoff
```

**`closeCircuit(id, now)`**

```sql
UPDATE subscriptions
   SET circuit_state = 'CLOSED'::circuit_state,
       circuit_opened_at = NULL, circuit_backoff = NULL,
       consecutive_opens = 0, updated_at = :now
 WHERE subscription_id = :subscription_id
   AND circuit_state = 'HALF_OPEN'::circuit_state
```

### Three things that will be got wrong without being told

1. **The exponent reads the pre-update `consecutive_opens`** (ADR-006 Amendment B3). A PostgreSQL `UPDATE`'s right-hand side sees the pre-update value, so a first trip has `consecutive_opens = 0` and `base * 2^0 = base`. This is why ADR-006 §1.2's prose (`2^(consecutive_opens - 1)`, post-update) and its SQL (`2^consecutive_opens`, pre-update) agree, and neither is a typo. **Writing `consecutive_opens + 1` in the exponent silently doubles every cooldown**, which no ordinary test notices. Test 5 below exists to catch it.
2. **`tripCircuit` and `reopenCircuit` are not the same method with a parameter.** Identical `SET`, different guard, and the guard is the contract: `CLOSED` means a healthy destination started failing, `HALF_OPEN` means a probe against a recovering one failed again. A failed probe depends on the `HALF_OPEN` guard. Do not refactor them into one private helper taking the expected state — the duplication of the `SET` clause is deliberate and cheaper than the precondition confusion it prevents (ADR-006 B2). A shared **constant** for the `SET` fragment is acceptable; a shared method taking the guard as a parameter is not.
3. **`interval` arithmetic:** `circuit_backoff` is an `interval` column and `baseCooldown`/`maxCooldown` arrive as `java.time.Duration`. Bind them in a form Postgres multiplies and compares as intervals (bind as an interval, or as seconds and multiply an `interval '1 second'`). Do not convert to a Java `Instant` and compare timestamps — the cooldown must be evaluated inside the atomic update, not before it, or `promoteToHalfOpen` becomes the read-then-write race the guard exists to prevent. State the chosen binding in a comment.

### Required tests

Per method: happy path asserting `true` and every column; every disallowed prior `circuit_state` asserting `false` and no column moved; unknown id asserting `false` without throwing.

Then the properties that matter:

1. **`tripCircuit` from `HALF_OPEN` returns `false` and changes nothing.** The guard distinction, asserted directly.
2. **`reopenCircuit` from `CLOSED` returns `false` and changes nothing.** The other half. Together with test 1 these two are what would fail if the methods were collapsed into one.
3. `reopenCircuit` from `HALF_OPEN` succeeds, sets `OPEN`, and increments `consecutive_opens` — a failed probe escalates the cooldown.
4. First-writer-wins: two sequential `tripCircuit` calls on a `CLOSED` row give `true` then `false`, with `consecutive_opens` advanced exactly once. ADR-006 §1.2's "a second pod tripping moments later affects zero rows and does nothing further."
5. **The cooldown ladder.** From `consecutive_opens = 0`, a trip yields `circuit_backoff == baseCooldown` exactly. Drive the full ladder (close, trip again, close, trip again) and assert `base`, `2 * base`, `4 * base`. **The first assertion is the one that catches an off-by-one in the exponent.**
6. The cap: with a `consecutive_opens` high enough that the doubling would exceed `maxCooldown`, `circuit_backoff == maxCooldown`.
7. `promoteToHalfOpen` with the cooldown **not** yet elapsed returns `false` and leaves the row `OPEN`. With it elapsed, returns `true`. Both, driven by manipulating `circuit_opened_at`/`circuit_backoff` in the fixture rather than by sleeping.
8. `promoteToHalfOpen` from `CLOSED` and from `HALF_OPEN` both return `false`.
9. `closeCircuit` resets **all four**: `CLOSED`, `circuit_opened_at` null, `circuit_backoff` null, `consecutive_opens` zero. A successful probe must fully forget the escalation, or a client that recovers stays on a long cooldown forever.
10. `closeCircuit` from `OPEN` returns `false`. Only a probe may close a circuit.
11. **A full lifecycle test:** `CLOSED -> trip -> OPEN -> promote -> HALF_OPEN -> reopen -> OPEN -> promote -> HALF_OPEN -> close -> CLOSED`, asserting the state and the cooldown after every step, and that `consecutive_opens` is 0 at the end. This is the path ADR-006 §1.2 added to close the "a circuit that trips never recovers" gap, and it should be provable end to end in one test.
12. None of the four touches `active`, `verification_state` or `throttled_until` (ADR-004 §1: a 429 throttle and a breaker trip are different mechanisms).
13. `promoteToHalfOpen` interacts correctly with the relay: after a promotion, the subscription's deliveries are admitted by `claimDue`. Reuse a minimal fixture; this is the ADR-002 §2.1 / ADR-006 §1.2 seam and the only place the two halves meet.

Real Postgres via `TestcontainersConfiguration`. **No H2, no mocked JDBC** — `interval` arithmetic, `POWER`, `LEAST` and native enums are all Postgres behaviors, and this is the task where a mock would prove nothing at all.

## Out of Scope

- Resilience4j, the in-memory per-pod failure count, the 10-failure trip threshold. ADR-006 §1.2 keeps those out of the database by design; the adapter only persists transitions.
- The `base_cooldown`/`max_cooldown` configuration values. They arrive as parameters; the numbers are ADR-006 §1.2's proposals and belong to the worker's wiring.
- The `HALF_OPEN` one-probe admission cap and the effective-concurrency value (0 / 1 / `max_concurrency`). ADR-006 §1.2-1.3's relay-side concern, not in `claimDue` (TASK-004-11) and not here.
- `claimDue` itself (TASK-004-11). Test 13 only asserts the seam.
- `deactivate`, `setThrottledUntil` (TASK-004-18), the reads (TASK-004-17).
- No `consecutive_failures` column. ADR-006 §1.2 deliberately does not have one; do not add it.

## Acceptance Criteria

- [ ] Four methods, one `UPDATE` each, all returning `boolean` from the affected row count, none throwing on zero rows.
- [ ] Each guard matches its ADR-006 §1.2 row exactly: `tripCircuit` on `CLOSED`, `reopenCircuit` on `HALF_OPEN`, `promoteToHalfOpen` on `OPEN` **plus** the elapsed-cooldown predicate, `closeCircuit` on `HALF_OPEN`.
- [ ] The cooldown exponent uses the pre-update `consecutive_opens` and is capped at `maxCooldown`; a comment cites ADR-006 B3 and warns against `+ 1`.
- [ ] `tripCircuit` and `reopenCircuit` are not collapsed into a shared method taking the expected state; a shared `SET`-fragment constant is acceptable.
- [ ] The elapsed-cooldown check happens inside the `UPDATE`, never as a preceding read.
- [ ] `closeCircuit` resets all four columns.
- [ ] `updated_at` is set explicitly in all four.
- [ ] Every value bound as a named parameter; enum literals cast; the interval binding is documented in a comment.
- [ ] Tests written and passing: the per-method matrix plus all thirteen properties above, against real Postgres via `TestcontainersConfiguration`. Test 5's first assertion (`circuit_backoff == baseCooldown` on the first trip) and tests 1-2 (the guard distinction) are mandatory, not optional. Test 7 manipulates fixture columns rather than sleeping. No H2, no mocked JDBC. `./gradlew test --tests "com.cobre.challenge.adapter.out.persistence.SubscriptionCircuitOpsTest"` green, full suite still green.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition. LSP: returning `false` rather than throwing is what makes this adapter substitutable for any other implementation of the port.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A10** is the operative category throughout: every one of these four is a lost-race path, and if any threw instead of returning `false`, ADR-006 §1.2's designed first-writer-wins outcome would surface as an error inside the worker and the breaker would fail open — the one failure mode that lets a dead endpoint keep absorbing traffic. **A05:** bound parameters only.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
