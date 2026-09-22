---
id: TASK-008-18
feature: FEAT-008
title: GetNotificationEventUseCaseImpl — delivery, event body and full attempt history
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-008-12, TASK-008-13, TASK-008-14]
date: 2026-09-21
---

# TASK-008-18: `GetNotificationEventUseCaseImpl`

## Feature

FEAT-008

## Assigned Agent

`backend-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/usecase/GetNotificationEventUseCaseImpl.java` (new)
  - `src/test/java/com/cobre/challenge/application/usecase/GetNotificationEventUseCaseImplTest.java` (new)
- Concern: assembling the detail view of ADR-005 §1's second row.

## Behavior (ADR-005 §1, ADR-007 §5.5)

Three tenant-scoped reads, in this order, all with the command's `TenantId`:

1. `DeliveryQueryRepositoryPort.findById(deliveryId, tenant)` — **empty ends it: return
   `Optional.empty()` immediately.** No further read runs, no state is inspected.
2. `NotificationEventQueryRepositoryPort.findById(delivery.eventId(), tenant)` — the event body.
   `delivery.eventId()` is a **`String`** business key (e.g. `EVT001`), and the port's parameter is
   `String eventId` — see the note below.
3. `DeliveryAttemptQueryRepositoryPort.findByDeliveryId(deliveryId, tenant)` — the **full** attempt
   history, all rows, ordered by attempt number.

Assemble into the merged `NotificationEventDetail` record and return it wrapped in `Optional`.

### Two identifiers, deliberately (architect note, 2026-09-21 — read before writing step 2)

This use case handles **two different id types, and neither is interchangeable with the other**:

| Identifier | Type | Where it comes from | Used for |
|---|---|---|---|
| **delivery id** | `UUID` | `GetNotificationEventCommand.deliveryId`, i.e. the `{notification_event_id}` path variable of ADR-005 §1 | steps 1 and 3 (`deliveries.delivery_id`, `delivery_attempts.delivery_id`) |
| **event id** | `String` | `delivery.eventId()`, read off the row found in step 1 | step 2 only (`notification_events.event_id`, a `text` column, e.g. `EVT001`) |

The public path variable is named `notification_event_id` but **carries the delivery row's UUID** —
that is the id the list endpoint returns per item and the id replay echoes back as the new row to
poll (ADR-005 §1). The domain's own event id is never accepted from the client and never appears in
a URL; the only way to reach it is the step-1 delivery row. Do not add a lookup that takes an event
id from the command, and do not convert either id into the other type.

`NotificationEventQueryRepositoryPort.findById` previously took `UUID eventId`, which was a defect
in TASK-008-13 (a `UUID` can never equal a `text` id like `EVT001`, so the adapter was always
returning empty). It is now `findById(String eventId, TenantId tenant)`, already corrected in the
code — see the resolution in `docs/concerns.md`. The three-step flow above is **unchanged**; it was
already right.

**Why the full history and not just the current row** (ADR-005 §1, stated so nobody trims it as
an optimization): it is what makes a client's "you never called me at 14:02" complaint answerable
from this one endpoint instead of requiring a second call. Do not paginate it, do not cap it, do
not return only the last attempt.

**404, never 403** (ADR-007 §5.5). The use case returns `Optional.empty()` for a nonexistent id
and for another tenant's id, and it has **no way to tell them apart** because the row was filtered
out at the query. There is no 403 branch to write here; if you find yourself wanting to write one,
the tenant scoping has been bypassed somewhere.

If step 1 succeeds but step 2 returns empty, that is a data-integrity violation (a delivery with
no event), not a tenant condition — fail loudly with a clear exception; do not degrade to a 404
that would hide it.

`@Transactional(transactionManager = "apiTransactionManager", readOnly = true)` — the qualifier is
mandatory for the same reason as TASK-008-17, and here it additionally makes all three reads share
one transaction, so the session binding happens once and all three see a consistent snapshot.

## Out of Scope

- Any HTTP concern, including the 404 status itself — TASK-008-25 maps `Optional.empty()` to it.
- Any response DTO. `NotificationEventDetail` is a `port/in` record and is returned as is; the web
  DTO is TASK-008-25's.
- Changing `NotificationEventDetail`.
- The other two use cases.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**
All three reads are mocked ports, so the whole of this task's behavior — including the
short-circuit and the foreign-id result — is in scope now. This is the **unit-level half of named
test 1**: a foreign id yields `Optional.empty()` and the other two ports are never called. The
HTTP 404 itself is `DEFERRED — Testcontainers` under TASK-008-28. Verify with `./gradlew
compileJava compileTestJava` plus this task's unit tests; **do not run `./gradlew test` or
`./gradlew build`.**

## Acceptance Criteria

- [ ] Implements `GetNotificationEventUseCase`; one public method returning
      `Optional<NotificationEventDetail>`.
- [ ] `@Transactional` names `apiTransactionManager` and is `readOnly = true`.
- [ ] The delivery lookup runs **first**, and an empty result short-circuits before either other
      read.
- [ ] All three reads pass the command's `TenantId`; none is unscoped.
- [ ] The full attempt list is returned — no cap, no paging, no "latest only".
- [ ] A foreign id and a nonexistent id produce **identical** results, and no log line at info or
      above distinguishes them (A01, A09).
- [ ] A delivery whose event is missing throws rather than returning empty, with a message that
      names neither the tenant nor the event content.
- [ ] Unit tests against fake/mock ports: owned id returns the assembled detail with all attempts;
      unknown id returns empty; foreign id returns empty **and the second and third ports are
      never called**; a delivery with zero attempts returns an empty list, never `null`; a missing
      event throws.
- [ ] Nothing in this class imports `org.springframework.security.**` or `org.springframework.jdbc.**`.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A01).

## Definition of Done

Code and tests written, tests passing locally. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

### Handover

- `GetNotificationEventUseCaseImpl` (new): three tenant-scoped reads in the fixed order —
  `DeliveryQueryRepositoryPort.findById(deliveryId, tenant)` first (empty short-circuits to
  `Optional.empty()`), then `NotificationEventQueryRepositoryPort.findById(delivery.eventId(),
  tenant)` (the corrected `String eventId` signature per `docs/concerns.md`'s 2026-09-21
  resolution), then `DeliveryAttemptQueryRepositoryPort.findByDeliveryId(deliveryId, tenant)` for
  the full, uncapped attempt list. Assembled into `NotificationEventDetail`.
  `@Transactional(transactionManager = "apiTransactionManager", readOnly = true)` so all three reads
  share one transaction/snapshot.
- A missing event after a found delivery throws `IllegalStateException` (data-integrity violation,
  not a tenant condition); the message names neither tenant nor event content.
- No branch, log line or metric distinguishes "nonexistent id" from "foreign id" — both are the
  single `Optional.isEmpty()` result of step 1.
- Unit tests (`GetNotificationEventUseCaseImplTest`, 5 tests, fake ports keyed by
  `(id, tenant)`): owned id returns the assembled detail with all attempts; unknown id returns
  empty; foreign id (row exists only under `OTHER_TENANT`) returns empty **and** asserts the event
  and attempt fake repositories' call counters are zero; zero attempts returns an empty list, never
  `null`; a delivery whose event is missing throws `IllegalStateException`.
- `./gradlew compileJava compileTestJava` passes; targeted test run
  (`--tests GetNotificationEventUseCaseImplTest`) passes, 5/5.
- Nothing in this class imports `org.springframework.security.**` or `org.springframework.jdbc.**`.
