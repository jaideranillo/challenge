---
id: TASK-003-09
feature: FEAT-003
title: RetryPolicy property tests — jitter bounds, monotonicity, exhaustion
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-003-08]
date: 2026-09-20
---

# TASK-003-09: RetryPolicy property tests

## Feature

FEAT-003

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

Plain-JUnit property coverage of the backoff function. No Spring context, no clock manipulation, no sleeping.

- File(s):
  - `src/test/java/com/cobre/challenge/domain/policy/RetryPolicyTest.java` (new)
- Concern: proving the schedule, the jitter bound and the exhaustion signal.

Required properties:
1. **Jitter inside the bound.** For each attempt number 1..6, over many seeded draws (e.g. 1000 with a fixed-seed `RandomGenerator`), every returned duration lies within `[0.8 x nominal, 1.2 x nominal]` inclusive. Assert both edges, not just "roughly".
2. **Jitter is actually applied.** Over those draws, at least two distinct values are observed for a given attempt number — a policy that returned the nominal value every time would pass property 1 and still be wrong. ADR-004 §1 and ADR-006 §1 both state jitter is mandatory rather than cosmetic; this is the assertion that enforces it.
3. **Monotonic with jitter.** For every seeded draw, `nextBackoff(n) < nextBackoff(n+1)` for n = 1..5. This holds strictly given the ADR schedule: the largest jittered value of each step is below the smallest jittered value of the next (6s < 24s, 36s < 96s, 144s < 480s, 720s < 2880s, 4320s < 17280s), so a failure here means the schedule or the jitter factor was changed, and the test should say so in its failure message.
4. **Nominal midpoint.** With a `RandomGenerator` stubbed to return its midpoint draw, each attempt returns exactly the nominal `5s, 30s, 2m, 10m, 1h, 6h` — the schedule itself is pinned, not just its neighborhood.
5. **Exhaustion.** `nextBackoff(6)` (the sixth and last attempt having failed) and anything beyond returns `Optional.empty()`, never `null` and never a throw. `maxAttempts()` is 6 for the default schedule.
6. **Determinism.** Two policies built with the same seed produce an identical sequence — this is what makes the whole suite reproducible and is the reason the generator is injected.
7. **Constructor validation.** `null`, empty, `null`-element, non-positive and non-increasing schedules each throw `IllegalArgumentException`.
8. **`nextAttemptAt`** adds the jittered duration to the supplied `Instant` and returns empty on exhaustion; the test supplies a fixed `Instant` and never calls `Instant.now()`.

## Out of Scope

- No `@SpringBootTest`, no Testcontainers, no `Thread.sleep`, no wall-clock dependency — a test that sleeps for a backoff interval is a defect, not coverage.
- No test of the circuit-breaker cooldown or of `Delivery`.
- No new test dependency. Use plain JUnit with a seeded `RandomGenerator`; do not add jqwik or any property-testing library for this (A03 — a new dependency for eight assertions is not a trade worth making).

## Acceptance Criteria

- [ ] All eight properties above are covered, each as a named test.
- [ ] The suite is deterministic: repeated runs produce identical results, with no seed taken from the clock.
- [ ] No sleeping, no `Instant.now()`, no Spring import.
- [ ] `./gradlew test --tests "com.cobre.challenge.domain.policy.RetryPolicyTest"` passes.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced — and no new Gradle dependency (A03).

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
