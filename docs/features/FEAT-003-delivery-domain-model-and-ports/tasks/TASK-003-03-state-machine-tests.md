---
id: TASK-003-03
feature: FEAT-003
title: State transition tests, legal and illegal
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-003-02]
date: 2026-09-20
---

# TASK-003-03: State transition tests, legal and illegal

## Feature

FEAT-003

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

Exhaustive plain-JUnit coverage of the state machine. No Spring context, no Testcontainers, no mocks.

- File(s):
  - `src/test/java/com/cobre/challenge/domain/model/delivery/enums/DeliveryStatusTransitionTest.java` (new)
- Concern: proving every one of the 49 ordered pairs behaves as ADR-003 §1 says.

Required coverage:
1. **Every legal transition** from TASK-003-02's table succeeds and returns the target state. Drive it from a parameterized source listing the pairs, not seven hand-written methods.
2. **Every illegal transition throws** `IllegalDeliveryTransitionException`. Generate the complement: iterate all `DeliveryStatus` x all `DeliveryStatus`, subtract the legal set, assert a throw for each remaining pair. This is what guarantees no pair is left untested as states or rules change.
3. **Named regression cases**, asserted explicitly even though the complement above already covers them, because each encodes a decision that a future reader might undo by accident:
   - `DEAD -> PENDING` throws (replay is insert-not-mutate, ADR-005 §1).
   - `FAILED -> PENDING` throws (recovery is insert-not-mutate, ADR-003 §1.1).
   - `QUEUED -> PENDING` and `PROCESSING -> PENDING` throw (no reset-to-`PENDING` path exists, ADR-003 §1).
   - `QUEUED -> QUEUED` succeeds (the relay's in-place re-publish).
   - Each terminal state has an empty `allowedTransitions()`.
4. The exception's `from()`/`to()` carry the attempted pair on at least one case.

## Out of Scope

- No test of the `Delivery` aggregate — that ships with TASK-003-04.
- No `@SpringBootTest`, no `@DataJdbcTest`, no Testcontainers. If this test needs a container, something is wrong with the domain, not with the test.
- No assertions about SQL, repositories or the public `delivery_status` vocabulary.

## Acceptance Criteria

- [ ] All 49 ordered `(from, to)` pairs are exercised, each asserted either legal or throwing — with the illegal set derived as the complement, not hand-listed.
- [ ] The named regression cases above each have their own explicitly named test.
- [ ] The test class imports no Spring type and runs in milliseconds.
- [ ] `./gradlew test --tests "com.cobre.challenge.domain.model.DeliveryStatusTransitionTest"` passes.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable).

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
