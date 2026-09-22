---
id: TASK-008-13
feature: FEAT-008
title: Client-facing event and attempt query ports
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-008-04]
date: 2026-09-21
---

# TASK-008-13: Client-Facing Event and Attempt Query Ports

## Feature

FEAT-008

## Assigned Agent

`backend-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/out/persistence/NotificationEventQueryRepositoryPort.java` (new)
  - `src/main/java/com/cobre/challenge/application/port/out/persistence/DeliveryAttemptQueryRepositoryPort.java` (new)
  - `src/test/java/com/cobre/challenge/application/port/out/persistence/PersistencePortsTest.java` (modified)
- Concern: the two remaining reads the GET detail endpoint needs, as tenant-mandatory contracts.

## Why new interfaces instead of adding methods to the existing ones

ADR-005 §1 requires `GET /notification_events/{id}` to return the event body **and the full
attempt history**. Both reads already exist on the pipeline side —
`NotificationEventRepositoryPort.findById` and `DeliveryAttemptRepositoryPort.findByDeliveryId` —
and both are **cross-tenant**, take no tenant, and run on the pipeline pool.

Adding a tenant-scoped overload to either would put a client-facing read on the same interface a
pipeline component injects, which is precisely the hole ADR-007 §5.2 closes and precisely what
Amendment E1 split the delivery port to avoid. Two interfaces means the split is in the type
system, and it is also what lets the two live on different connection pools (TASK-008-16).

## The contracts

```
Optional<NotificationEvent> findById(String eventId, TenantId tenant);

List<DeliveryAttempt> findByDeliveryId(UUID deliveryId, TenantId tenant);
```

> **Architect correction, 2026-09-21 (was `UUID eventId`).** The original snippet typed the first
> parameter as `UUID eventId`. That was a defect in this task file, not in the implementation:
> `NotificationEvent.eventId` and `Delivery.eventId` are `String` business keys and
> `notification_events.event_id` is a `text` column (V1). The corrected type is `String`.
> `findByDeliveryId`'s `UUID deliveryId` is **unchanged and correct** — `deliveries.delivery_id`
> is a real UUID. See the resolution in `docs/concerns.md`.

- `Optional`, never `null`; an empty `List`, never `null` (ADR-005 §1's port conventions,
  Effective Java Items 54-55).
- Cross-tenant absence and genuine absence are **the same result**: `Optional.empty()` / an empty
  list. The port must offer no way to tell them apart, because ADR-007 §5.5's 404 depends on the
  use case being unable to know (A01).
- `delivery_attempts` has no `client_id` column, so the javadoc must say the tenant predicate is
  expressed through the parent `deliveries` row — both in SQL (TASK-008-16) and in the RLS policy
  (TASK-008-08).
- One-line javadoc per repo convention, plus the tenant-mandatory paragraph the existing
  `DeliveryQueryRepositoryPort` carries.

## Out of Scope

- The adapters — TASK-008-16.
- Any change to `NotificationEventRepositoryPort`, `DeliveryAttemptRepositoryPort`,
  `SubscriptionRepositoryPort` or `DeliveryPipelineRepositoryPort`. They stay cross-tenant and
  keep every current caller.
- `DeliveryQueryRepositoryPort` — TASK-008-12.
- Any new DTO. `NotificationEvent` and `DeliveryAttempt` are returned as they are.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**
`PersistencePortsTest` is a reflective shape test and is in scope now. The adapters' behavioral
tests are `DEFERRED — Testcontainers` under TASK-008-16. Verify with `./gradlew compileJava
compileTestJava`; **do not run `./gradlew test` or `./gradlew build`.**

## Acceptance Criteria

- [ ] Two new interfaces, each with exactly one method, each taking `TenantId`.
- [ ] Neither has an unscoped overload.
- [ ] Return types are `Optional<NotificationEvent>` and `List<DeliveryAttempt>`; `null` is never
      a valid return and the javadoc says so.
- [ ] The javadoc states that a foreign row and a nonexistent row are indistinguishable through
      these ports, and why (ADR-007 §5.5).
- [ ] The `delivery_attempts` port's javadoc states the parent-row tenant predicate.
- [ ] `PersistencePortsTest` asserts both new shapes and still asserts the four pipeline ports
      carry no tenant parameter.
- [ ] No pipeline port is modified.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition (ISP is doing
      real work here — say so in one javadoc line, not a paragraph).
- [ ] No new OWASP Top 10:2025 exposure introduced (A01).

## Definition of Done

Code written and port tests passing locally. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

### Handover

- Two new one-method interfaces, both tenant-mandatory, no unscoped overload:
  `NotificationEventQueryRepositoryPort.findById(UUID, TenantId): Optional<NotificationEvent>` and
  `DeliveryAttemptQueryRepositoryPort.findByDeliveryId(UUID, TenantId): List<DeliveryAttempt>`.
- Javadoc on both states the cross-tenant-absence-equals-genuine-absence property and why (ADR-007
  §5.5, A01). `DeliveryAttemptQueryRepositoryPort`'s javadoc states the tenant predicate is
  expressed through the parent `deliveries` row since `delivery_attempts` has no `client_id`.
- Neither `NotificationEventRepositoryPort` nor `DeliveryAttemptRepositoryPort` (the existing
  cross-tenant pipeline ports) was touched. `PersistencePortsTest` gained explicit
  no-`TenantId`-parameter assertions for both, plus for `DeliveryPipelineRepositoryPort` and
  `SubscriptionRepositoryPort`, alongside shape tests for the two new ports.
- **Deviation to flag, not fixed here (task file said not my responsibility to arrange):** the
  task's literal contract snippet types the parameter as `UUID eventId` on
  `NotificationEventQueryRepositoryPort.findById`. The domain model's own event identifier
  (`NotificationEvent.eventId`, and `Delivery.eventId`) is a `String` business key, not a `UUID` —
  see `NotificationEventRepositoryPort.findById(String eventId)` on the existing cross-tenant
  port. I implemented the signature exactly as specified in this task file rather than reconcile
  the type, per "work only from what the task file says." Flagging this now so whoever writes the
  TASK-008-16 adapter and the use case wiring it (TASK-008-1x) sees the mismatch before assuming
  it is a typo-free contract; logging in `docs/concerns.md` as well.
- `./gradlew compileJava compileTestJava` fails only at the same, unrelated,
  already-documented `DeliveryQueryJdbcRepository` break from TASK-008-12 (TASK-008-15's scope).
  No failure originates from these two new files or the test additions — confirmed separately
  with a throwaway local patch to that adapter file (reverted, not committed).

### Architect follow-up (2026-09-21) — signature corrected, no further work for backend-engineer

The deviation flagged in the handover above was correct and is resolved. The port now reads:

```
Optional<NotificationEvent> findById(String eventId, TenantId tenant);
```

The code change was **applied by the architect directly** (it is a two-line signature change):
`NotificationEventQueryRepositoryPort.java` (parameter type + javadoc naming the `text` column)
and `PersistencePortsTest.java` (the reflective lookup now asks for `String.class`).
`./gradlew compileJava compileTestJava` passes. **No action is required from `backend-engineer`
on this task** — it stays `Ready for Review`.
