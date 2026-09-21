---
id: TASK-003-10
feature: FEAT-003
title: Inbound ports — ingest, dispatch, attempt
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-003-04, TASK-003-05]
date: 2026-09-20
---

# TASK-003-10: Inbound ports — ingest, dispatch, attempt

## Feature

FEAT-003

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

Three `port/in` interfaces from ADR-005 §1's list, the pipeline half. **Interfaces and their command/result records only — no implementation.**

> **Addendum (2026-09-20):** originally each command/result record was nested inside its interface file to stay at three files. Superseded by user request — every record now lives in its own file under a `dto` subpackage (see `docs/concerns.md`). File list below reflects the current layout.

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/in/pipeline/RegisterNotificationEventUseCase.java`
  - `src/main/java/com/cobre/challenge/application/port/in/pipeline/DispatchPendingDeliveriesUseCase.java`
  - `src/main/java/com/cobre/challenge/application/port/in/pipeline/AttemptDeliveryUseCase.java`
  - `src/main/java/com/cobre/challenge/application/port/in/pipeline/dto/RegisterNotificationEventCommand.java`
  - `src/main/java/com/cobre/challenge/application/port/in/pipeline/dto/RegisterNotificationEventResult.java`
  - `src/main/java/com/cobre/challenge/application/port/in/pipeline/dto/DispatchPendingDeliveriesCommand.java`
  - `src/main/java/com/cobre/challenge/application/port/in/pipeline/dto/DispatchPendingDeliveriesResult.java`
  - `src/main/java/com/cobre/challenge/application/port/in/pipeline/dto/AttemptDeliveryCommand.java`
  - `src/main/java/com/cobre/challenge/application/port/in/pipeline/dto/AttemptDeliveryResult.java`
- Concern: the inbound contracts of the delivery pipeline.

Shapes (ADR-005 §1: one method each, immutable command in, immutable result out, `Optional`/empty collection never `null`):

**`RegisterNotificationEventUseCase`** — gateway ingest (ADR-002 §1.1).
`RegisterNotificationEventResult register(RegisterNotificationEventCommand command)`.
Command: `eventId`, `clientId`, `eventType`, `content`, `occurredAt`.
Result: the created delivery ids (empty list when no active subscription matched — ADR-003 §2 says no `deliveries` row is written in that case, and the result must be able to say so without `null` and without an exception), plus a flag distinguishing "newly created" from "already existed" so the idempotent re-ingest path (ADR-003 §2) is expressible in the return value rather than as a thrown duplicate.

**`DispatchPendingDeliveriesUseCase`** — relay cycle (ADR-002 §2.1).
`DispatchPendingDeliveriesResult dispatch(DispatchPendingDeliveriesCommand command)`.
Command: `batchLimit` and the `Instant` the due-query runs as of (time is passed in, never read inside the domain).
Result: counts of claimed and published rows. Not the rows themselves — the relay does not need them and returning them would invite a caller to act on stale state.

**`AttemptDeliveryUseCase`** — worker, one pointer message (ADR-002 §2.2).
`AttemptDeliveryResult attempt(AttemptDeliveryCommand command)`.
Command: the four pointer-envelope fields of ADR-004 §1 — `deliveryId`, `subscriptionId`, `attemptHint`, `traceparent` (the last as `Optional<String>`). **Nothing else**: the envelope is a pointer, and target URL, secret and content are loaded from the database by the implementation.
Result: the resulting `DeliveryStatus` and the `AttemptOutcome`, both as domain types, so the adapter can decide whether to delete the message without re-deriving anything.

## Out of Scope

- **No implementation class anywhere.** No `application/usecase` package is created in this task.
- No `@Transactional`, no Spring stereotype annotation, no Bean Validation annotation on the command records — validation at the adapter boundary belongs to the controller's DTO, which is a later feature.
- No HTTP DTO, no JSON annotation, no Jackson import.
- No query/replay ports — TASK-003-11.
- No `VerifySubscriptionTargetUseCase` (not in ADR-005 §1's list; see FEAT-003 scope).

## Acceptance Criteria

- [ ] Exactly three interfaces, one method each, named as above.
- [ ] Every command and result is a `record`, immutable, with defensive copies of any collection component.
- [ ] No signature returns `null`; absence is `Optional` and emptiness is an empty collection (Effective Java Items 54-55).
- [ ] `AttemptDeliveryCommand` carries exactly the four pointer fields and no payload.
- [ ] No import from `org.springframework`, `jakarta`, `com.fasterxml`, or any HTTP/JDBC/AWS package in any of the three files.
- [ ] Tests written and passing — for interface-only files, a compile-level test is sufficient; add a small record-validation test if a compact constructor validates anything.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition — in particular single-method interfaces (ISP) and one reason to change each (SRP).
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable).

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
