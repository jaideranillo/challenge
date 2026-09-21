---
id: TASK-007-15
feature: FEAT-007
title: Unit tests — claim, deferral, breaker trip and probe paths
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-007-14]
date: 2026-09-21
---

# TASK-007-15: `AttemptDeliveryUseCaseImpl` flow unit tests

## Feature

FEAT-007

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/test/java/com/cobre/challenge/application/usecase/AttemptDeliveryUseCaseImplTest.java` (new)
- Concern: prove the fixed per-message order and its branches, against mocked ports, with no
  database, no socket and no container.

Mock every port. Use a fixed `Clock` and a seeded `RandomGenerator` so deferral instants are
deterministic. Prefer a hand-written fake for `CircuitBreakerPort` where a scenario depends on the
trip edge firing once — a fake makes "exactly one" obvious; a mock makes it a stubbing puzzle.

## Required unit scenarios

0. **No-attempt paths return an empty `outcome`.** Per TASK-007-14's follow-up 14a, the
   lost-claim and both deferral paths return `AttemptDeliveryResult.noAttempt(QUEUED)`:
   `outcome().isEmpty()` is true and `status()` is `QUEUED`. Every attempted path returns a
   present `outcome`. Assert this explicitly in scenarios 1, 2 and 3 rather than as a separate
   test — it is a property of those paths, not a behavior of its own.
1. **Zero-row claim short-circuits.** `claimForProcessing` returns `false`: the method returns
   normally, and `WebhookClientPort`, `WebhookEnvelopeSerializerPort`, `WebhookSecretPort`,
   `BulkheadPort` and `DeliveryOutcomeWriter` are all never touched. This is the guard that
   prevents a double POST (A08), so assert it by interaction, not by return value alone.
2. **Bulkhead rejection defers and records nothing.** `tryAcquire` returns `false`:
   `deferDelivery` is called exactly once with an instant in `[now+10s, now+20s]`; the
   `DeliveryAttemptRepositoryPort` and `DeliveryOutcomeWriter` are never touched; no
   `scheduleRetry`, no `markDead`, no `markDelivered`; no HTTP call is made. Repeat with two
   different random seeds and assert the two instants differ (jitter is real, not a constant).
3. **`OPEN` circuit defers by the same path**, with the same assertions as scenario 2.
4. **`HALF_OPEN` proceeds as the probe.** The attempt runs, and the command handed to
   `DeliveryOutcomeWriter` carries `wasHalfOpenProbe = true`.
5. **Consecutive failures trip the circuit with exactly one conditional write.** Drive ten
   attempts against a fake `CircuitBreakerPort` that returns the trip edge on the tenth:
   `tripCircuit` is called **exactly once** across all ten, and not at all on attempts 11 and 12.
   This is the "no duplicate writes" property in unit form; the concurrent-workers variant is
   database-level and belongs to the deferred integration phase.
6. **A failed `HALF_OPEN` probe reopens the circuit.** With `circuit_state = HALF_OPEN` and a 500
   response, `reopenCircuit` is reached through the writer's command (`wasHalfOpenProbe = true`,
   outcome breaker-counting), and `tripCircuit` is **never** called on a probe — a trip applied to
   a `HALF_OPEN` circuit would double-count the cooldown escalation (ADR-006 Amendment B2). The
   doubled cooldown itself is computed in SQL by the merged adapter; assert here only that
   `reopenCircuit` is invoked with the configured base and max, and note in the handover that the
   `base * 2^consecutive_opens` arithmetic is already covered by the merged
   `SubscriptionCircuitOpsTest`.
7. **A successful probe closes the circuit**, through the writer's command, and `recordSuccess` is
   called on the breaker port.
8. **429 never reaches the breaker.** A 429 response leaves `recordFailure` and `tripCircuit`
   untouched, while the writer still receives a `RETRYABLE_THROTTLED` command.
9. **A `CLOSED`-circuit 5xx on a non-probe** calls `recordFailure` but, when no trip edge fires,
   calls `tripCircuit` never and writes nothing else to `SubscriptionRepositoryPort`.
10. **Unresolvable secret fails closed.** `WebhookSecretPort.resolve` returns empty: no call to
    `WebhookClientPort`, and the writer receives a non-retryable outcome.
11. **Rotation window.** With `previousSecretExpiresAt` in the future, the request headers contain
    both signature headers; with it in the past, only one. Assert on the `WebhookRequest` captured
    by the mocked client port.
12. **The permit is always released.** In the success path, the failure path, and when the client
    port throws, `release` is called exactly once. (The adapter is contracted not to throw; this
    test pins the `finally` anyway.)
13. **Attempt number comes from the row.** With `delivery.attemptCount() = 4` and
    `command.attemptHint() = 99`, the envelope's `attempt` is 5.
14. **Egress policy rejection is permanent and breaker-neutral.** (Applies once TASK-007-20 has
    wired the validator; if you reach this task first, write it then and say so in the handover.)
    A `POLICY_REJECTED` verdict: no call to `WebhookClientPort`, the writer receives the existing
    `NON_RETRYABLE` outcome with `httpStatus` absent, and **neither** `recordFailure` **nor**
    `tripCircuit` is called.
15. **Egress DNS failure is transient and breaker-counting.** A `DNS_FAILURE` verdict: no POST,
    the writer receives `RETRYABLE`, and `recordFailure` **is** called — the opposite of
    scenario 14, which is the whole reason the verdict has two failure states.
16. **One instant per attempt.** The instant used for the claim, the timestamp header and the
    writer's command is the same value.

## Out of Scope

- Anything needing Spring, a container, a socket or a database. A scenario that cannot be reached
  with mocks belongs to the deferred integration phase — name it in the handover, do not build it.
- The response-code-to-write matrix (TASK-007-13) and the listener's delete behavior
  (TASK-007-17).
- Modifying `AttemptDeliveryUseCaseImpl`. If a test needs a production change, report it and stop.

## Testing (phase rule)

Unit tests only: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no Docker.
**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileTestJava` and
report the tests as written and pending the Tech Lead's later explicit run.

## Acceptance Criteria

- [ ] All sixteen scenarios above exist as named test methods.
- [ ] Scenarios 14 and 15 assert opposite breaker behavior for the two validator failure states.
- [ ] Scenario 5 asserts `tripCircuit` is called exactly once across the whole failure run.
- [ ] Scenario 2 asserts both the deferral bounds and that two seeds give different instants.
- [ ] Every "must not happen" assertion uses `verifyNoInteractions` / `verify(..., never())`.
- [ ] A fixed `Clock` and a seeded `RandomGenerator` make every instant deterministic; no
      `Thread.sleep`, no wall-clock tolerance windows.
- [ ] No `@SpringBootTest`, no Testcontainers, no LocalStack, no real HTTP.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer).

## Definition of Done

Tests written but **not run**. **Do not run `git add` or `git commit`.** Set this task's `status`
to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
