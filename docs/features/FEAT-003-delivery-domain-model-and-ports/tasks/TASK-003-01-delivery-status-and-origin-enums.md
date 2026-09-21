---
id: TASK-003-01
feature: FEAT-003
title: Delivery status and origin enums
status: Ready for Review
agent: backend-engineer
depends_on: []
date: 2026-09-20
---

# TASK-003-01: Delivery status and origin enums

## Feature

FEAT-003

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

Create the two domain enums that the rest of the feature is built on. Values only plus a terminal-state accessor; no transition logic (that is TASK-003-02).

- File(s):
  - `src/main/java/com/cobre/challenge/domain/model/delivery/enums/DeliveryStatus.java` (new)
  - `src/main/java/com/cobre/challenge/domain/model/delivery/enums/DeliveryOrigin.java` (new)
- Concern: the delivery vocabulary, framework-free.

`DeliveryStatus` has exactly the seven internal states of ADR-003 §1, no more and no fewer:

`PENDING, QUEUED, PROCESSING, RETRYING, DELIVERED, DEAD, FAILED`

It exposes `boolean isTerminal()`, true for `DELIVERED`, `DEAD` and `FAILED` only (ADR-003 §1, "Terminal states are `DELIVERED`, `DEAD`, and `FAILED`").

`DeliveryOrigin` has exactly `INGEST, REPLAY, RECOVERED` (ADR-003 §3, ADR-005 §3).

## Out of Scope

- No transition rules, no `canTransitionTo`, no allowed-target sets — TASK-003-02 owns those.
- No mapping to the public `delivery_status` vocabulary (`pending`/`completed`/`failed`, ADR-003 §1). That mapping lives in the web adapter and is not part of this feature at all.
- No persistence annotations, no `@Enumerated`, no converters.
- Do not create `Delivery`, `Subscription` or any other record here.

## Acceptance Criteria

- [ ] `DeliveryStatus` declares exactly the seven ADR-003 §1 states, spelled exactly as in the ADR.
- [ ] `isTerminal()` returns true for `DELIVERED`, `DEAD`, `FAILED` and false for the other four.
- [ ] `DeliveryOrigin` declares exactly `INGEST`, `REPLAY`, `RECOVERED`.
- [ ] Neither file imports anything outside `java.*`. No Spring, no JDBC, no HTTP types.
- [ ] Tests written and passing (a small plain-JUnit test asserting the terminal set is enough; no Spring context).
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable).

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
