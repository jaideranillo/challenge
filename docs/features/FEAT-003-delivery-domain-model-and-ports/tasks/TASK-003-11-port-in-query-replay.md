---
id: TASK-003-11
feature: FEAT-003
title: Inbound ports — query, get, replay
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-003-04, TASK-003-05]
date: 2026-09-20
---

# TASK-003-11: Inbound ports — query, get, replay

## Feature

FEAT-003

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

The remaining three `port/in` interfaces from ADR-005 §1, the self-service API half. **Interfaces and their command/result records only — no implementation.**

> **Addendum (2026-09-20):** originally records were nested inside their interface file. Superseded by user request — DTOs now live under `dto`; `ReplayDeliveryResult` and its `Accepted`/`Rejected` variants stay top-level in this package (behavioral hierarchy, not a plain DTO). See `docs/concerns.md`.

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/in/selfservice/QueryNotificationEventsUseCase.java`
  - `src/main/java/com/cobre/challenge/application/port/in/selfservice/GetNotificationEventUseCase.java`
  - `src/main/java/com/cobre/challenge/application/port/in/selfservice/ReplayDeliveryUseCase.java`
  - `src/main/java/com/cobre/challenge/application/port/in/selfservice/ReplayDeliveryResult.java`
  - `src/main/java/com/cobre/challenge/application/port/in/selfservice/Accepted.java`
  - `src/main/java/com/cobre/challenge/application/port/in/selfservice/Rejected.java`
  - `src/main/java/com/cobre/challenge/application/port/in/selfservice/dto/QueryNotificationEventsCommand.java`
  - `src/main/java/com/cobre/challenge/application/port/in/selfservice/dto/QueryNotificationEventsResult.java`
  - `src/main/java/com/cobre/challenge/application/port/in/selfservice/dto/GetNotificationEventCommand.java`
  - `src/main/java/com/cobre/challenge/application/port/in/selfservice/dto/NotificationEventDetail.java`
  - `src/main/java/com/cobre/challenge/application/port/in/selfservice/dto/ReplayDeliveryCommand.java`
  - `src/main/java/com/cobre/challenge/application/port/in/selfservice/dto/RejectionReason.java`
- Concern: the inbound contracts of the three client-facing endpoints.

**`QueryNotificationEventsUseCase`** — `GET /notification_events` (ADR-005 §1).
`QueryNotificationEventsResult query(QueryNotificationEventsCommand command)`.
Command: `clientId`, `createdFrom`/`createdTo` (`Optional<Instant>`), `status` filter (`Optional<DeliveryStatus>` or a small domain filter type — **never a free-text string**, A05), `cursor` (`Optional<String>`), `limit` (int).
Result: the page of deliveries plus `Optional<String> nextCursor` — keyset pagination, empty when the page is the last.
`clientId` is a **mandatory, non-optional component**: ADR-005 §1 says the query is always scoped to the authenticated caller, taken from the security context and never from a request parameter. The contract must make an unscoped query inexpressible.

**`GetNotificationEventUseCase`** — `GET /notification_events/{id}` (ADR-005 §1).
`Optional<NotificationEventDetail> get(GetNotificationEventCommand command)`.
Command: `deliveryId` and `clientId`, both mandatory.
Result: a detail record carrying the `Delivery`, its `NotificationEvent`, and the full `List<DeliveryAttempt>` history. **Returns `Optional.empty()` both when the row does not exist and when it belongs to another client** — ADR-005 §1 requires a 404 rather than a 403 so the endpoint does not leak other tenants' ids. The port must not offer a way to distinguish those two cases, or the controller will eventually leak the difference.

**`ReplayDeliveryUseCase`** — `POST /notification_events/{id}/replay` (ADR-005 §1).
`ReplayDeliveryResult replay(ReplayDeliveryCommand command)`.
Command: `deliveryId`, `clientId`, `idempotencyKey` (mandatory, per ADR-005 §1's required header).
Result: the **new** row's `deliveryId` and its status (`PENDING`) — an acknowledgment that the replay was accepted, not a delivery outcome. Rejection cases (target not `DEAD`, or a live/delivered row already exists for the pair) are expressed as a typed rejection reason on the result or as a dedicated domain exception; either way the reason must be a typed value the controller maps to `409`, never a bare boolean and never a raw SQL constraint name.

## Out of Scope

- **No implementation class anywhere.** No `application/usecase` package in this task.
- No mapping to the public `delivery_status` vocabulary (`pending`/`completed`/`failed`) — controller concern, later feature.
- No page-size clamping (default 50, max 200 per ADR-005 §1) — the port takes a `limit`; clamping is adapter-side input handling.
- No Bean Validation annotations, no Spring `Pageable`, no `Sort`, no Spring Data types of any kind.
- No pipeline ports — TASK-003-10.

## Acceptance Criteria

- [ ] Exactly three interfaces, one method each, named as above.
- [ ] `clientId` is a mandatory component of all three commands; no overload or alternate factory allows omitting it (A01 / IDOR, ADR-005 §1 and ADR-007).
- [ ] `GetNotificationEventUseCase` returns `Optional`, with no signal distinguishing "missing" from "other tenant's".
- [ ] The status filter is a typed domain value, not a `String` (A05).
- [ ] All commands and results are immutable records with defensive collection copies; no `null` returns.
- [ ] No import from `org.springframework`, `jakarta`, `com.fasterxml`, or any HTTP/JDBC package.
- [ ] Tests written and passing — compile-level for interface-only files; add record-validation tests where a compact constructor validates.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. This task carries the A01 tenant-scoping and A05 typed-filter contract decisions; if either cannot be met as written, flag to `security-engineer` rather than relaxing it.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
