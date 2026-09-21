---
id: TASK-006-10
feature: FEAT-006
title: Acceptance test — OPEN to HALF_OPEN promotion and the one-probe rule
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-006-06, TASK-006-07]
date: 2026-09-21
---

# TASK-006-10: Acceptance test — half-open promotion and the one-probe rule

## Feature

FEAT-006

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/test/java/com/cobre/challenge/application/usecase/RelayHalfOpenProbeAcceptanceTest.java` (new)
- Concern: the Tech Lead's scenario 6 — a subscription past its open-circuit cooldown is promoted
  to `HALF_OPEN` and exactly one delivery is admitted.

`@SpringBootTest` with real PostgreSQL and real LocalStack SQS via the merged
`TestcontainersConfiguration`. **No mocks anywhere.** Set `challenge.relay.enabled=false` so the
live scheduler cannot race the fixtures; drive `DispatchPendingDeliveriesUseCase.dispatch`
directly with an explicit `asOf`.

## Scope — the scenarios

**1. Promotion and one-probe admission (the named requirement).**
Fixture: one subscription, `active`, `VERIFIED`, `max_concurrency = 10`, `circuit_state = 'OPEN'`,
`circuit_opened_at = asOf - 5 minutes`, `circuit_backoff = 1 minute` — cooled down. Three due
`PENDING` rows for it, all older than the 30s grace window.

Assert, after one `dispatch`:
- exactly **one** row moved to `QUEUED`, the other two are untouched and still `PENDING` with
  their original `next_attempt_at`;
- the claimed row's `next_attempt_at` is `asOf + 5 minutes`;
- the subscription's `circuit_state` is now `HALF_OPEN`, and `circuit_opened_at`,
  `circuit_backoff` and `consecutive_opens` are **unchanged** — `promoteToHalfOpen` writes
  `circuit_state` and nothing else (ADR-006 §1.2);
- exactly one message is receivable from the queue, and its body's `deliveryId` is the claimed
  row's;
- the result is `(claimedCount = 1, publishedCount = 1)`.

**2. Still cooling — no promotion, no claim.** Same fixture but `circuit_opened_at = asOf - 10s`
with `circuit_backoff = 1 minute`. Assert zero rows claimed, zero messages published,
`circuit_state` still `OPEN`, and the result is `(0, 0)`.

**3. A second cycle does not admit a second probe.** Run `dispatch` again at
`asOf2 = asOf + 1 second`, with the circuit now `HALF_OPEN` from scenario 1. Assert zero
additional rows claimed — the first probe is still in flight (`QUEUED`) and consumes the entire
effective cap of `1`. This is the assertion that proves the one-probe rule holds *across* cycles
and not merely within one.

**4. A `CLOSED` circuit is unaffected.** A second subscription, `CLOSED`, `max_concurrency = 2`,
with three due rows, claimed in the same `dispatch` as scenario 1. Assert exactly 2 of its rows
are claimed and its `subscriptions` row is **byte-for-byte unchanged** — no `circuit_state`, no
`updated_at` movement. ADR-006 §1.2: a `CLOSED`-circuit delivery writes nothing to
`subscriptions`, and the relay's blanket `promoteToHalfOpen` call must not violate that. This is
the assertion that catches a promotion whose guard was weakened.

## Out of Scope

- The worker. Nothing in this test attempts a delivery, completes a probe, or drives
  `HALF_OPEN -> CLOSED` / `HALF_OPEN -> OPEN`. Those transitions are the worker's writes
  (ADR-006 §1.2) and belong to a later feature. Assert on the table and the queue, never on a
  delivery outcome.
- Partial batch failure — TASK-006-11.
- The scheduler's own timing. You call `dispatch` directly.
- Changing any production file. If an assertion fails, the fix belongs to the task that owns that
  file (01, 06 or 07); say which in your handoff.

## Acceptance Criteria

- [ ] All four scenarios implemented, against real PostgreSQL and real LocalStack.
- [ ] `challenge.relay.enabled=false` in the test context.
- [ ] Every cycle is driven by an explicit `asOf`; no `Thread.sleep`, no wall-clock assertion.
- [ ] Scenario 1 asserts the full column set on `subscriptions`, not just `circuit_state`.
- [ ] Scenario 4 asserts the `CLOSED` subscription row is untouched.
- [ ] No Mockito, no stub port, no H2.
- [ ] `./gradlew test` passes.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable)

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
