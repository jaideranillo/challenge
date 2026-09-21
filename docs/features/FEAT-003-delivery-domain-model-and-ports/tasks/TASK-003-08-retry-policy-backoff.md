---
id: TASK-003-08
feature: FEAT-003
title: RetryPolicy — backoff schedule with jitter
status: Ready for Review
agent: backend-engineer
depends_on: []
date: 2026-09-20
---

# TASK-003-08: RetryPolicy — backoff schedule with jitter

## Feature

FEAT-003

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

The pure backoff function of ADR-004 §1 (restated in ADR-006 §1), framework-free and deterministic under an injected randomness source.

- File(s):
  - `src/main/java/com/cobre/challenge/domain/policy/RetryPolicy.java` (new)
- Concern: how long until the next attempt, and when the budget is spent.

**The spec is in the ADRs; do not invent numbers.** ADR-004 §1: `5s -> 30s -> 2m -> 10m -> 1h -> 6h`, six steps, each with +/-20% jitter; reaching `max_attempts` moves the row to `DEAD`. ADR-006 §1 restates the same schedule and states jitter is mandatory.

Three points the ADRs do **not** state, fixed here as labelled implementation choices (see FEAT-003's "Open item flagged" section) — put each in a comment naming it as such, so no reader mistakes them for derived numbers:
1. Jitter is a **uniform** draw over `[0.8 x nominal, 1.2 x nominal]`.
2. Randomness comes from an injected `java.util.random.RandomGenerator`, so tests are deterministic and the domain holds no static global state.
3. `max_attempts` is the schedule length, six. No seventh interval is invented.

Shape:
- Construct with `RetryPolicy(List<Duration> schedule, RandomGenerator random)` — the schedule is passed in, not hard-coded, because ADR-004 §1's note says it is `@ConfigurationProperties`-bound at the edge and handed to the framework-free object at construction. Defensively copy the list; reject empty, `null` entries, non-positive durations and a non-increasing sequence.
- A static factory `RetryPolicy.defaultSchedule(RandomGenerator)` returning the ADR's six values, so a caller without config still gets the documented behavior.
- `Optional<Duration> nextBackoff(int attemptNumber)` — `attemptNumber` is 1-based (the attempt that just failed). Returns the jittered interval for the next attempt, or **`Optional.empty()` when the budget is exhausted**, which is the caller's signal to move the row to `DEAD`. Never returns `null`, never throws to signal exhaustion.
- `int maxAttempts()` returning the schedule length.
- `Instant nextAttemptAt(int attemptNumber, Instant now)` convenience, returning `Optional<Instant>`, so the use case does not do the arithmetic itself. `Instant` is passed in; the class holds no `Clock` and calls no `now()` of its own.

## Out of Scope

- **No Spring.** No `@Component`, no `@ConfigurationProperties`, no `@Value`. Binding config to this constructor is a later wiring task in the adapter layer.
- No `Clock` field, no `Instant.now()`, no `System.currentTimeMillis()` anywhere in the file.
- No `ThreadLocalRandom`, no `Math.random()`, no static `Random` — those defeat deterministic testing and, per the agent's virtual-thread rules, no static mutable state is held across blocking calls.
- No per-subscription schedule override. ADR-004 §1 explicitly leaves the schedule global for v1 (YAGNI). Do not add a subscription parameter.
- No circuit-breaker cooldown (`base * 2^consecutive_opens`, ADR-006 §1.2) — a different mechanism at a different granularity, not this class.
- No tests in this task — TASK-003-09 owns them.

## Acceptance Criteria

- [ ] `nextBackoff` returns a jittered duration within `[0.8 x nominal, 1.2 x nominal]` for every in-range attempt number, and `Optional.empty()` beyond the schedule length.
- [ ] `defaultSchedule` yields exactly `5s, 30s, 2m, 10m, 1h, 6h`.
- [ ] Randomness is injected; the file contains no static random and no clock call.
- [ ] Constructor validation rejects `null`, empty, non-positive and non-increasing schedules with `IllegalArgumentException`.
- [ ] The class is immutable apart from the `RandomGenerator` it was given, and holds no mutable field of its own.
- [ ] No import outside `java.*`.
- [ ] Tests written and passing (the property suite lands in TASK-003-09; the build must be green here).
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. Note: this jitter is a load-shaping device, not a security control, so a non-cryptographic `RandomGenerator` is correct here — do not reach for `SecureRandom`.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
