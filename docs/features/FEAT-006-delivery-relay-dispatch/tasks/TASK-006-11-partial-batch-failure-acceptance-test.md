---
id: TASK-006-11
feature: FEAT-006
title: Acceptance test — partial batch failure leaves rows QUEUED and clock-recoverable
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-006-07]
date: 2026-09-21
---

# TASK-006-11: Acceptance test — partial batch failure and clock-based recovery

## Feature

FEAT-006

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/test/java/com/cobre/challenge/application/usecase/RelayPartialBatchFailureAcceptanceTest.java` (new)
- Concern: the Tech Lead's scenario 7 — entries SQS refuses stay `QUEUED` with their pushed clock
  and are picked up again once that time elapses.

`@SpringBootTest` with real PostgreSQL and real LocalStack SQS, `challenge.relay.enabled=false`,
`dispatch` driven directly with an explicit `asOf`. **No mocks, no stub `NotificationQueuePort`,
no stub `SqsClient`.**

## Scope — the scenario

**Cycle 1, at `asOf`.** Several due rows, of which at least one is made to fail at the SQS entry
level — same mechanism as TASK-006-05 (an invalid message attribute value, or an oversized entry;
see that task for the preference order and the fallback). The simplest way to target a specific
row is through its persisted `trace_context`, which the relay uses as the traceparent fallback
when no span is active.

Assert, after cycle 1:
- `dispatch` did **not** throw;
- the result is `(claimedCount = N, publishedCount = N - F)` where `F` is the number of refused
  entries;
- **every** row — published and refused alike — is `QUEUED` with `next_attempt_at = asOf + 5
  minutes`. This is the heart of the scenario: there is no compensation, no rollback, no
  status difference between a published row and a refused one;
- `attempt_count` is unchanged on every row, and **no `delivery_attempts` row exists** for any of
  them. Nothing was attempted; the relay never attempts;
- exactly `N - F` messages are receivable from the queue.

**Cycle 2, at `asOf2 = asOf + 5 minutes + 1 second`** (the pushed clock has elapsed). Before this
cycle, repair the fixture so the previously refused rows can publish — the point of the assertion
is that the *row* is eligible again, not that the same broken entry fails forever.

Assert, after cycle 2:
- the previously refused rows are claimed again — `claimedCount` includes them;
- their `next_attempt_at` is now `asOf2 + 5 minutes`;
- they are receivable from the queue;
- their `attempt_count` is still unchanged, and there is still no `delivery_attempts` row.

**Cycle 1b, a control.** Run a `dispatch` at `asOf + 1 minute` — after cycle 1, before the pushed
clock elapses — and assert zero rows are claimed. Without this, the test cannot distinguish "the
pushed clock is the recovery mechanism" from "the rows were always claimable".

## Out of Scope

- The worker, `delivery_attempts` writes, and every delivery outcome. This test asserts that
  those do **not** happen; it must not create them.
- Circuit promotion — TASK-006-10.
- Adapter-level batch mechanics — TASK-006-05.
- Any production file. An assertion failure here is a defect in the task that owns the file
  (04 or 07); name it in your handoff rather than fixing it under this task.
- The whole-request failure path (`publishBatch` throwing). Reproducing an SQS outage against
  LocalStack is not worth the fixture cost, and TASK-006-07's catch is small and reviewable.

## Acceptance Criteria

- [ ] All three cycles (1, 1b, 2) implemented, against real PostgreSQL and real LocalStack.
- [ ] Refused and published rows are asserted to be indistinguishable in the `deliveries` table
      after cycle 1.
- [ ] `attempt_count` unchanged and `delivery_attempts` empty, asserted in both cycles.
- [ ] The control cycle proves the rows are not claimable before the pushed clock elapses.
- [ ] Every cycle driven by an explicit `asOf`; no `Thread.sleep`, no wall-clock assertion.
- [ ] No Mockito, no stub port, no H2.
- [ ] `./gradlew test` passes.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable)

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
