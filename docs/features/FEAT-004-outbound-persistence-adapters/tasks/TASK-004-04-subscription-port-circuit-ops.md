---
id: TASK-004-04
feature: FEAT-004
title: "SubscriptionRepositoryPort: replace transitionCircuitState with four guarded circuit operations"
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-004-03]
date: 2026-09-20
---

# TASK-004-04: the four circuit operations on `SubscriptionRepositoryPort`

## Feature

FEAT-004

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

A **contract** task. `depends_on: [TASK-004-03]` is not a logical dependency (this port is independent of the delivery split) but a file one: both tasks amend `PersistencePortsTest`, so they are serialized to avoid two agents editing it.

## Scope

Replace the generic `transitionCircuitState` with four named operations, per ADR-006 Amendments B1, B2 and B3.

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/out/persistence/SubscriptionRepositoryPort.java`
  - `src/test/java/com/cobre/challenge/application/port/out/persistence/PersistencePortsTest.java`
- Concern: the circuit-breaker transition contract.

`SubscriptionRepositoryPort` stays a **single cross-tenant interface** and is not split. It has no `client_id` parameter anywhere and gains none.

### The four operations

Each is one atomic conditional `UPDATE`, first-writer-wins, returning `boolean` from the affected row count; zero rows never throws. Delete `transitionCircuitState` entirely.

| Method | Guard | Sets |
|---|---|---|
| `boolean tripCircuit(UUID subscriptionId, Duration baseCooldown, Duration maxCooldown, Instant now)` | `circuit_state = 'CLOSED'` | `'OPEN'`, `circuit_opened_at = now`, `circuit_backoff` computed, `consecutive_opens + 1` |
| `boolean reopenCircuit(UUID subscriptionId, Duration baseCooldown, Duration maxCooldown, Instant now)` | `circuit_state = 'HALF_OPEN'` | identical `SET` clause to `tripCircuit` |
| `boolean promoteToHalfOpen(UUID subscriptionId, Instant asOf)` | `circuit_state = 'OPEN' AND circuit_opened_at < asOf - circuit_backoff` | `'HALF_OPEN'` |
| `boolean closeCircuit(UUID subscriptionId, Instant now)` | `circuit_state = 'HALF_OPEN'` | `'CLOSED'`, `circuit_opened_at = NULL`, `circuit_backoff = NULL`, `consecutive_opens = 0` |

### `tripCircuit` and `reopenCircuit` are two operations and the javadoc must say why

They write an **identical `SET` clause** and differ **only** in their guard. That is not an argument for merging them, it is the reason they must stay apart:

- `tripCircuit`'s guard is `CLOSED`: a healthy destination has started failing. Caller: the worker, when its pod-local Resilience4j breaker trips.
- `reopenCircuit`'s guard is `HALF_OPEN`: a probe against a recovering destination failed again. Caller: the worker, in the probe's outcome transaction (ADR-002 §2.2 step 6).

A single operation taking the expected state as a parameter is exactly the generic shape this task removes, and it would let a caller pass the wrong precondition: a probe outcome applied to a `CLOSED` circuit trips a destination nothing is currently failing against, and a trip applied to a `HALF_OPEN` circuit double-counts a cooldown escalation. **A failed probe depends on the `HALF_OPEN` guard.** Javadoc both methods with their own guard and cite ADR-006 B2.

### Why the cooldown bounds are parameters and not a computed interval

`circuit_backoff` is `base * 2^consecutive_opens`, capped, and the exponent reads `consecutive_opens` **from the row**. Passing a pre-computed interval would require the caller to read the column first, which is the read-then-write race the conditional update exists to eliminate. So the bounds go in and the adapter computes in SQL.

Javadoc must carry ADR-006 B3's warning verbatim in substance: **the exponent uses the pre-update column value.** ADR-006 §1.2's prose (`2^(consecutive_opens - 1)`, post-update) and its SQL (`2^consecutive_opens`, pre-update) agree and neither is a typo, because a PostgreSQL `UPDATE`'s right-hand side reads the pre-update value: a first trip has `consecutive_opens = 0`, so `base * 2^0 = base`. Writing `consecutive_opens + 1` would silently double every cooldown, which is not a bug a test finds unless it is looking for it.

`java.time.Duration` is a JDK type and is fine in a port. `promoteToHalfOpen` takes `asOf` rather than reading a clock so one relay poll cycle evaluates every subscription against one instant (ADR-002 C2).

### Untouched on this interface

`findActiveForEvent`, `findById`, `deactivate`, `setThrottledUntil` keep their committed signatures. `deactivate`'s missing `Instant` parameter is a pre-existing clock-source inconsistency already logged in `docs/concerns.md`; **do not change it here.**

## Out of Scope

- No adapter implementation. Interfaces only; the JDBC class is `dba`'s, TASK-004-19.
- No split of this interface. It is cross-tenant throughout.
- No `Resilience4j` configuration, no in-memory breaker, no trip threshold. ADR-006 §1.2's pod-local count is the worker's concern, not a port's.
- No change to the `CircuitState` enum, `Subscription`, or any other domain type.
- No change to `deactivate` or `setThrottledUntil`.
- No change to the delivery ports (TASK-004-03).

## Acceptance Criteria

- [ ] `transitionCircuitState` is gone; the four methods exist with exactly the signatures tabled above.
- [ ] All four return `boolean`, and no javadoc suggests throwing on zero rows.
- [ ] `tripCircuit` and `reopenCircuit` each javadoc their own guard, state that the `SET` clauses are identical and the guards are not, and cite ADR-006 B2.
- [ ] `promoteToHalfOpen`'s javadoc states the compound guard including the elapsed-cooldown predicate, and why `asOf` is a parameter.
- [ ] `closeCircuit`'s javadoc lists all four columns it resets.
- [ ] The pre-update-exponent warning from ADR-006 B3 appears in the javadoc of both `tripCircuit` and `reopenCircuit`.
- [ ] `findActiveForEvent`, `findById`, `deactivate`, `setThrottledUntil` are byte-identical to their committed form.
- [ ] Zero framework imports. `java.time.Duration`/`Instant`, `java.util.UUID`/`Optional`/`List`, `domain/model` only.
- [ ] Tests written and passing: `PersistencePortsTest` amended to assert `transitionCircuitState` is absent, the four methods are present with the right arity and `boolean` returns, no method on this interface takes a `clientId`, and the interface imports no framework type. Plain JUnit, no Spring context.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition. ISP and Item 64; and LSP is live here, since an adapter that threw on zero rows would not be substitutable for one that returned `false`.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A10** is the operative one: a circuit operation that threw on a lost race would turn ADR-006 §1.2's designed first-writer-wins outcome into an error path in the worker, and the breaker would fail open.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
