---
id: TASK-003-02
feature: FEAT-003
title: State machine transition rules and illegal-transition exception
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-003-01]
date: 2026-09-20
---

# TASK-003-02: State machine transition rules and illegal-transition exception

## Feature

FEAT-003

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

Encode ADR-003 §1's state machine as data on `DeliveryStatus`, and add the exception an illegal transition throws.

- File(s):
  - `src/main/java/com/cobre/challenge/domain/model/delivery/enums/DeliveryStatus.java` (modify — add transition rules)
  - `src/main/java/com/cobre/challenge/domain/model/delivery/exception/IllegalDeliveryTransitionException.java` (new)
- Concern: which status may follow which, and failing loudly when it may not.

**The legal transition set, taken verbatim from ADR-003 §1's diagram. Nothing else is legal.**

| From | Legal targets |
| --- | --- |
| `PENDING` | `QUEUED` |
| `QUEUED` | `PROCESSING`, `QUEUED`, `FAILED` |
| `PROCESSING` | `DELIVERED`, `RETRYING`, `DEAD`, `QUEUED`, `FAILED` |
| `RETRYING` | `QUEUED` |
| `DELIVERED` | none (terminal) |
| `DEAD` | none (terminal) |
| `FAILED` | none (terminal) |

Notes that the implementation must respect, all from ADR-003 §1:
- `QUEUED -> QUEUED` **is legal** — the relay's due-query re-publishes a stale row in place. It is a self-transition, not a no-op to be filtered out.
- `DEAD -> PENDING` is **not** legal. Replay inserts a new row (ADR-005 §1); it never resurrects the old one.
- `FAILED` has no outgoing edge. Recovery also inserts a new row (ADR-003 §1.1).
- No transition resets anything to `PENDING`. `PENDING` is only ever reached by insert.

API shape:
- `Set<DeliveryStatus> allowedTransitions()` — returns an immutable set, empty (never `null`) for terminal states.
- `boolean canTransitionTo(DeliveryStatus target)`.
- `DeliveryStatus transitionTo(DeliveryStatus target)` — returns `target` when legal, otherwise throws `IllegalDeliveryTransitionException`. **Never silently ignores an illegal transition and never returns the current state as a fallback.**

`IllegalDeliveryTransitionException` extends `RuntimeException`, carries `from` and `to` as typed fields with accessors, and its message names both states.

## Out of Scope

- No `Delivery` aggregate — TASK-003-04.
- No tests in this task — TASK-003-03 owns the transition test suite.
- No persistence of the transition, no conditional-`UPDATE` SQL, no repository call. The state-guarded `UPDATE ... WHERE status = <expected>` (ADR-003 §1.1) is the persistence adapter's business, in a later feature.
- Do not add a `DeliveryStatus.fromPublicName(...)` or any public-vocabulary mapping.

## Acceptance Criteria

- [ ] The allowed-target sets match the table above exactly; an exhaustive reading of the seven states leaves no state without an explicit rule.
- [ ] `allowedTransitions()` returns an immutable empty set for `DELIVERED`, `DEAD` and `FAILED`, never `null` (Effective Java Item 54).
- [ ] `transitionTo` throws `IllegalDeliveryTransitionException` for every pair not in the table, including every outgoing pair from a terminal state.
- [ ] `IllegalDeliveryTransitionException` exposes `from()` and `to()` and its message contains both state names.
- [ ] No imports outside `java.*`.
- [ ] Tests written and passing (exhaustive coverage arrives in TASK-003-03; this task must at minimum compile with a green build).
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced — note that this is the A10 (Mishandling of Exceptional Conditions) control from ADR-003's OWASP table: an illegal transition must fail closed, loudly.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
