---
id: TASK-007-11
feature: FEAT-007
title: CircuitBreakerPort and the Resilience4j pod-local breaker adapter
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-007-01, TASK-007-02]
date: 2026-09-21
---

# TASK-007-11: `CircuitBreakerPort` and `Resilience4jCircuitBreakerAdapter`

## Feature

FEAT-007

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/out/resilience/CircuitBreakerPort.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/out/resilience/Resilience4jCircuitBreakerAdapter.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/out/resilience/Resilience4jCircuitBreakerAdapterTest.java` (new)
- Concern: the **pod-local, in-memory** failure count of ADR-006 §1.2. Nothing in these files
  touches the database.

### The rule this implements, and the one it must not break

ADR-006 §1.2: the trip decision is made from an in-memory per-subscription failure count, one
Resilience4j `CircuitBreaker` instance per `subscription_id` per pod, "deliberately not a column,
since writing it on every attempt is exactly the hot-row pattern being avoided". **No method here
writes anything anywhere.** The conditional `subscriptions` write belongs to the use case, which
calls `tripCircuit` when this port reports a trip.

### Contract

```java
// application/port/out/resilience/CircuitBreakerPort
/** A breaker-counting success (ADR-004 §1's classification). Resets the local count. */
void recordSuccess(UUID subscriptionId);
/** A breaker-counting failure. @return true only on the transition, never on later failures. */
boolean recordFailure(UUID subscriptionId);
/** ADR-006 §1.2's last bullet: the database says CLOSED, so clear this pod's local state. */
void resetIfOpenLocally(UUID subscriptionId);
```

**`recordFailure` returns an edge, not a level.** The 10th consecutive breaker-counting failure
returns `true`; the 11th and 12th return `false` while the local instance stays open. This is what
makes "exactly one conditional write per trip" a property of the contract rather than something
the caller has to remember — and it is directly assertable in TASK-007-15's unit test.

**`resetIfOpenLocally` is not cosmetic.** ADR-006 §1.2's last bullet spells out the failure it
prevents: a pod that never ran the probe keeps a frozen partial count forever, so its effective
trip threshold silently degrades outage after outage. The use case calls this on every attempt
where the loaded row says `circuit_state = 'CLOSED'`; the adapter makes it a cheap no-op when the
local instance is already closed and fresh.

### Adapter

- One `CircuitBreaker` per `subscription_id` in a `ConcurrentHashMap`, `computeIfAbsent` with a
  non-blocking mapping function.
- Configure a **count-based** consecutive-failure trip:
  `slidingWindowType = COUNT_BASED`, `slidingWindowSize = failureThreshold`,
  `minimumNumberOfCalls = failureThreshold`, `failureRateThreshold = 100`, so `failureThreshold`
  consecutive failures and nothing less trips it.
- **`failureThreshold` is read from `WorkerProperties`, never hardcoded**, and its value is
  profile-specific (Tech Lead direction, 2026-09-21): **10** under the default profile, **3**
  under `local`. The same applies to the base and max cooldown the use case and the writer pass
  to `tripCircuit`/`reopenCircuit` — 30s/1h by default, 10s/60s locally. A literal `10` anywhere
  in this adapter is a defect: it would make the demo profile untestable and silently diverge
  from the configured value. Take the whole triple from the injected properties record and do not
  cache a copy of any of it in a static field.
- **Disable Resilience4j's own time-based recovery**: `automaticTransitionFromOpenToHalfOpenEnabled
  = false` and a wait duration long enough to be inert. Recovery in this design is the relay's
  `promoteToHalfOpen` against PostgreSQL (ADR-002 §2.1, ADR-006 §1.2), not the library's timer.
  Two competing half-open mechanisms would admit probes nobody authorised.
- Derive the trip edge from the instance's own state transition (register a state-transition
  listener, or compare state before and after `onError`) — do not count calls yourself in a field.
- **No `synchronized`, no blocking inside the map's mapping function, no persistence, no logging
  of subscription data.**

## Out of Scope

- Every `subscriptions` write: `tripCircuit`, `reopenCircuit`, `closeCircuit`, `promoteToHalfOpen`.
  All merged on `SubscriptionRepositoryPort`, all called from the use case or the writer.
- Deciding which outcomes count. `AttemptOutcome.countsTowardCircuitBreaker()` already does, and
  the use case reads it before calling this port.
- The cooldown computation. It is SQL inside the merged `SubscriptionJdbcRepository`
  (`base * 2^consecutive_opens`, capped, pre-update exponent per ADR-006 Amendment B3).
- The bulkhead (TASK-007-10).

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker. Construct the adapter directly with a small `failureThreshold` (e.g. 3) so the tests are
short.

Required unit scenarios:
- `failureThreshold - 1` failures return `false`; the `failureThreshold`-th returns `true`
- the same test parameterized over **both** configured thresholds (10 and 3) trips at exactly
  that count each time — the property that proves nothing is hardcoded
- further failures after the trip return `false` — the edge fires exactly once
- a success before the threshold resets the count, so the trip needs a full run again
- `resetIfOpenLocally` on a locally-open breaker makes the next failure sequence start from zero
- `resetIfOpenLocally` on an already-closed, untouched breaker is a no-op and does not throw
- two different `subscriptionId`s keep independent counts
- no method performs any I/O (assert by construction: the adapter takes no repository)

**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileJava
compileTestJava` and report the tests as written and pending the Tech Lead's later explicit run.

## Acceptance Criteria

- [ ] The port lives in `application/port/out/resilience` and carries no Resilience4j type.
- [ ] No Resilience4j import exists outside `adapter/out/resilience`.
- [ ] `recordFailure` returns the trip **edge**, exactly once per trip.
- [ ] The failure threshold comes from `WorkerProperties`; no numeric literal for it exists in
      the adapter, and a test proves the trip point follows both the production (10) and the
      local (3) value.
- [ ] Resilience4j's automatic open-to-half-open transition is disabled.
- [ ] The adapter holds no repository, writes nothing, and persists nothing.
- [ ] Failure counts are per subscription and per pod, in memory only.
- [ ] No `synchronized`, no `ThreadLocal`, no logging of subscription data.
- [ ] Every unit scenario listed above is covered by a plain JUnit test.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer).

## Definition of Done

Code written, tests written but **not run**. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
