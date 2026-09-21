---
id: TASK-005-03
feature: FEAT-005
title: "NotificationEventRepositoryPort: the missing outbound contract for notification_events"
status: Ready for Review
agent: backend-engineer
depends_on: []
date: 2026-09-20
---

# TASK-005-03: `NotificationEventRepositoryPort`

## Feature

FEAT-005

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/out/persistence/NotificationEventRepositoryPort.java` (new)
  - `src/test/java/com/cobre/challenge/application/port/out/persistence/PersistencePortsTest.java` (extend)
- Concern: the interface. `notification_events` is reachable from no port today (ADR-003 Amendment A5).

```java
public interface NotificationEventRepositoryPort {

    boolean insertIfAbsent(NotificationEvent event);

    Optional<NotificationEvent> findById(String eventId);
}
```

Contract, to be stated in javadoc with its ADR citations:

1. **`insertIfAbsent` returns `true` only when this call inserted the row**, `false` when the event was already stored. `false` is a normal outcome, never an exception — the same convention every conditional write on `DeliveryPipelineRepositoryPort` already follows. This is ADR-002 §1.1 step 3's `ON CONFLICT (event_id) DO NOTHING`, and `V1`'s own comment on `notification_events.event_id` names the primary key as the mechanism.
2. **`insertIfAbsent` never updates.** `notification_events` is append-only and immutable after insert (ADR-003 §3, and `V1`'s table comment: "Never updated. No updated_at by design"). The name says `IfAbsent`, not `upsert`, for that reason.
3. **`findById` exists because the stored `createdAt` is authoritative.** On a re-ingest whose command carries a different `occurredAt`, the use case must use the stored value, or new delivery rows would disagree with their parent event about when the event happened (FEAT-005 decision 5). Returns `Optional`, never `null`.
4. **Cross-tenant by design, no `clientId` parameter.** The gateway holds the producer's IAM principal, not a client principal — ADR-007 §5.2's carve-out for internal pipeline ports, the same one that governs `DeliveryPipelineRepositoryPort`. Say so in the interface javadoc so it is not later "fixed" by adding a tenant parameter.

Extend `PersistencePortsTest` in the style it already uses for the other ports: assert the interface's shape (method count, return types, no framework import).

## Out of Scope

- Any implementation. TASK-005-06 owns `NotificationEventJdbcRepository`.
- Any change to the `NotificationEvent` record. It is sufficient as committed.
- Any change to `DeliveryPipelineRepositoryPort`. TASK-005-04.
- A `findByClientId`, a delete, a paged read, or any method ingest does not call.

## Acceptance Criteria

- [ ] Two methods, exactly as above, in `application/port/out/persistence`.
- [ ] Zero framework imports; the file references only `java.*` and the domain record.
- [ ] Javadoc states: `false` is normal and never throws; the row is never updated; why `findById` exists; why there is no `clientId` parameter, citing ADR-007 §5.2.
- [ ] `Optional` return, never `null` (Effective Java Item 55).
- [ ] Tests written and passing: `PersistencePortsTest` covers the new interface the way it covers the existing ones.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A01:** the absence of a tenant parameter is deliberate and documented, not an omission.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
