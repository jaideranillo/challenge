---
id: TASK-007-13
feature: FEAT-007
title: Unit tests — the response-code matrix on DeliveryOutcomeWriter
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-007-12]
date: 2026-09-21
---

# TASK-007-13: Response-code matrix unit tests

## Feature

FEAT-007

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/test/java/com/cobre/challenge/application/usecase/DeliveryOutcomeWriterTest.java` (new)
- Concern: prove ADR-004 §1's classification table becomes the right writes, with mocked ports
  and no database.

`DeliveryOutcomeWriter` takes five ports and a `RetryPolicy`. Mock the ports (Mockito is on the
classpath via the Boot test starters) and construct a real `RetryPolicy` with a **seeded**
`RandomGenerator` so jittered instants are deterministic. Do not mock `RetryPolicy` — it is a
merged, tested pure object and mocking it would test nothing.

## Required unit scenarios

Each is one test method with an explicit name.

1. **2xx -> `DELIVERED`.** `SUCCESS` calls `markDelivered(deliveryId, attemptedAt)` exactly once,
   inserts exactly one `delivery_attempts` row, and calls **no** method on
   `SubscriptionRepositoryPort` (the hot-row rule, ADR-006 §1.2).
2. **500 -> `RETRYING` with a future `next_attempt_at`.** `RETRYABLE` calls `scheduleRetry` with
   an instant strictly greater than `attemptedAt`, and within the jitter band of the schedule's
   entry for that attempt number (`[0.8x, 1.2x]` of nominal, per the merged `RetryPolicy`).
   `markDelivered` and `markDead` are never called.
3. **400 -> `DEAD`, no retry.** `NON_RETRYABLE` calls `markDead` exactly once, calls
   `scheduleRetry` never, and touches `SubscriptionRepositoryPort` not at all.
4. **429 sets `throttled_until` and is excluded from the breaker.** `RETRYABLE_THROTTLED` calls
   both `scheduleRetry` and `setThrottledUntil`; with a `Retry-After` on the command the throttle
   instant is `attemptedAt + retryAfter`, and without one it equals the scheduled retry instant.
   Cover both sub-cases, and one where the command carries a value **already clamped** to the
   profile's ceiling (e.g. exactly 1h): the writer passes it through untouched and does not
   re-clamp or reject it. An absent `Retry-After` is a normal fallback, never an error path.
   `reopenCircuit` and `tripCircuit` are never called, on a probe or otherwise. Assert this from
   `AttemptOutcome.RETRYABLE_THROTTLED.countsTowardCircuitBreaker()` being false rather than by
   hard-coding 429.
5. **404/410 deactivates.** `NON_RETRYABLE_DEACTIVATE_SUBSCRIPTION` calls `markDead` **and**
   `deactivate`, and no circuit method.
6. **3xx is dead and breaker-counting.** `NON_RETRYABLE_REDIRECT` calls `markDead`; on a probe it
   also calls `reopenCircuit`; off a probe it writes nothing to `subscriptions`.
7. **Exhausted schedule.** A `RETRYABLE` outcome whose `currentAttemptCount` is already the
   policy's `maxAttempts()` calls `markDead`, never `scheduleRetry`.
8. **Probe success closes the circuit.** `wasHalfOpenProbe = true` with `SUCCESS` calls
   `closeCircuit` exactly once, in addition to `markDelivered`.
9. **Probe failure reopens the circuit.** `wasHalfOpenProbe = true` with `RETRYABLE` calls
   `reopenCircuit` exactly once, with the base and max cooldown from the properties.
10. **Every outcome inserts exactly one attempt row**, including the dead and throttled paths —
    a parameterized test over all six `AttemptOutcome` values.
11. **A `false` from a conditional write is tolerated.** Stub `markDelivered` to return `false`;
    the method returns normally and throws nothing.
12. **A pre-HTTP failure records correctly.** A command with `httpStatus` absent, an `error`
    string, and outcome `NON_RETRYABLE` (the egress-policy-rejection shape, TASK-007-20) inserts
    one `delivery_attempts` row with a null/absent `http_status` and that `error`, calls
    `markDead`, and touches `SubscriptionRepositoryPort` not at all. Repeat with outcome
    `RETRYABLE` (the DNS-failure shape) and assert `scheduleRetry` instead. No new enum value
    appears in either case.
13. **`lastError` carries no payload.** For a 500 with a response excerpt present, the string
    passed as `lastError` does not contain the excerpt, the target URL, or any header value.

## Out of Scope

- Anything that needs a Spring context, a container, a socket or a real database. If a scenario
  seems to need one, it belongs to the deferred integration phase, not here.
- The claim, deferral, breaker-trip and probe-selection paths — those are the use case's, tested
  in TASK-007-15.
- Modifying `DeliveryOutcomeWriter`. If a test cannot be written without a production change,
  that is a finding to report, not a silent edit; raise it and stop.

## Testing (phase rule)

Unit tests only: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no Docker.
**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileTestJava` (or
the IDE's compile) and report the tests as written and pending the Tech Lead's later explicit run.

## Acceptance Criteria

- [ ] All thirteen scenarios above exist as named test methods.
- [ ] The pre-HTTP scenario asserts an absent `http_status` on the inserted attempt row and
      confirms no new `AttemptOutcome` value was needed.
- [ ] Ports are mocked; `RetryPolicy` is real and seeded for determinism.
- [ ] No `@SpringBootTest`, no `@DataJdbcTest`, no Testcontainers, no `Thread.sleep`.
- [ ] Interactions are asserted with `verify(..., times(1))` / `verifyNoInteractions(...)`, not
      only by return value, so "writes nothing to `subscriptions`" is genuinely proven.
- [ ] Tests compile and are readable as a table of ADR-004 §1's classification.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer).

## Definition of Done

Tests written but **not run**. **Do not run `git add` or `git commit`.** Set this task's `status`
to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
