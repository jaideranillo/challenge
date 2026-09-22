---
id: TASK-008-25
feature: FEAT-008
title: NotificationEventController — list and get, with response DTOs
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-008-05, TASK-008-17, TASK-008-18, TASK-008-21]
date: 2026-09-21
---

# TASK-008-25: `NotificationEventController` — List and Get

## Feature

FEAT-008

## Assigned Agent

`backend-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/web/selfservice/NotificationEventController.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/in/web/selfservice/dto/ListNotificationEventsRequest.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/in/web/selfservice/dto/NotificationEventResponse.java` (new — the list item, the detail and the attempt view; keep them as nested records in one file only if that stays readable, otherwise flag the split in your handover)
  - `src/test/java/com/cobre/challenge/adapter/in/web/selfservice/NotificationEventControllerTest.java` (new)
- Concern: the two read endpoints — HTTP in, command out, result in, HTTP out. **No business
  logic.**

## The two endpoints (ADR-005 §1)

```
GET /notification_events?created_from=&created_to=&delivery_status=&cursor=&limit=
GET /notification_events/{notification_event_id}
```

Both `@PreAuthorize("hasAuthority('notifications:read')")` — deliberately duplicating the URL rule
in TASK-008-21 (ADR-007 §2: two cheap checks that fail independently).

**The tenant is a `TenantId` method parameter**, resolved by TASK-008-05's argument resolver. The
handler never reads a claim, never constructs a `TenantId`, and **never accepts a tenant as
input** — TASK-008-06's ArchUnit rule 2 forbids a `@PathVariable`/`@RequestParam`/`@RequestHeader`
named or bound to `client_id`, `clientId`, `tenant` or `tenant_id`, and it will fail the build.

`ListNotificationEventsRequest` is the `@Valid` bound query object: `createdFrom`, `createdTo`,
`deliveryStatus`, `cursor`, `limit`. **`deliveryStatus` binds to the `DeliveryStatus` enum at the
boundary, never passed through as free text** (ADR-005's A05 row). An unparseable status is a 400,
not a silently ignored filter.

Map to `QueryNotificationEventsCommand` / `GetNotificationEventCommand` — remembering that the
command's fields are `eventCreatedFrom`/`eventCreatedTo` (ADR-005 Amendment D2), which the public
query parameters `created_from`/`created_to` map onto. Keep the public names as ADR-005 §1 spells
them; the rename lives on the inside.

**Clamping and the default window are the use case's** (TASK-008-17). Do not duplicate them here.
Pass the limit through as given — including an oversized one, which gets clamped, not rejected.

## Status codes (ADR-007 §5.5)

| Situation | Response |
|---|---|
| List, authorized | 200 with items and the next cursor |
| Get, row owned by the caller | 200 with the delivery, the event body and the **full attempt history** |
| Get, id belongs to another tenant | **404** |
| Get, id does not exist | **404**, identical to the above |
| Bad `delivery_status`, bad date, bad cursor format | 400 |

**404 not 403** (ADR-007 §5.5). There is no 403 branch to write here, because the use case
returned `Optional.empty()` and has no way to know whether the id exists elsewhere. If you find
yourself writing a 403 for a foreign id, something upstream has been bypassed.

**Response bodies carry no cross-tenant information**: a 404 body says not found and nothing
else, never echoes the `client_id`, and never names the owning tenant.

## The mapping step is mandatory even where fields are identical

`Delivery`, `NotificationEvent` and `DeliveryAttempt` are **domain models** and must not be
serialized directly as the response. Map them to the response records explicitly, field by field,
even where the shapes match today. `NotificationEventDetail` is a `port/in` record and is likewise
not a wire type.

The list response carries the items plus `nextCursor` (absent on the last page). The detail
response carries the delivery, the event body and every attempt — no cap, no paging, no
"latest only" (ADR-005 §1: this is what makes "you never called me at 14:02" answerable from one
endpoint).

Do not expose internal-only fields on the wire without deciding deliberately: `trace_context`
in particular is operational metadata, not client data. State what you included and excluded in
the handover.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker, no
MockMvc.** Test the controller as a plain object: construct it with a mocked use case, call the
handler methods directly, and assert the returned `ResponseEntity` and the command that was built.
That covers the mapping, the 404 on `Optional.empty()`, and the request-object binding shape.

`DEFERRED — Testcontainers` (specified here, owned by TASK-008-28): **named test 1** — a token for
client A requesting client B's delivery gets 404 — and every other assertion that needs a real
request, a real token or a real database.

Verify with `./gradlew compileJava compileTestJava` plus this task's unit tests; **do not run
`./gradlew test` or `./gradlew build`.**

## Out of Scope

- The replay endpoint — TASK-008-26.
- Cursor encoding. The cursor is an opaque string end to end; `DeliveryPageCursor` lives in the
  persistence adapter and this class must not import it.
- Clamping, the default date window, and any filtering logic.
- Security configuration, error bodies for 401/403/429 — TASK-008-21, -23, -24.
- Any OpenAPI/Swagger dependency or annotation (YAGNI; no such dependency is in the build).

## Acceptance Criteria

- [ ] Both endpoints are mapped exactly as ADR-005 §1 spells them, including the query-parameter
      names `created_from`, `created_to`, `delivery_status`, `cursor`, `limit`.
- [ ] Both carry `@PreAuthorize("hasAuthority('notifications:read')")`.
- [ ] The tenant arrives as a `TenantId` parameter; **no** parameter is named or bound to
      `client_id`, `clientId`, `tenant` or `tenant_id`.
- [ ] The request object is `@Valid`, and `delivery_status` is bound to the `DeliveryStatus` enum
      at the boundary; an invalid value is a 400.
- [ ] `created_from`/`created_to` map to the command's `eventCreatedFrom`/`eventCreatedTo`.
- [ ] No clamping, windowing or filtering logic exists in this class.
- [ ] `Optional.empty()` from the get use case becomes **404**, with a body that names nothing
      about ownership; there is **no 403 branch** for a foreign id.
- [ ] Domain models are never serialized directly; an explicit mapping step exists for every
      response field.
- [ ] The detail response includes every attempt, unpaged and uncapped.
- [ ] The list response carries `nextCursor`, absent on the last page.
- [ ] The handover states which fields were deliberately excluded from the wire, `trace_context`
      included.
- [ ] Unit tests: a controller constructed with a mocked use case returns the mapped response;
      empty becomes 404; the command is built with the resolved tenant and the mapped filter
      names; an oversized limit is passed through untouched.
- [ ] **DEFERRED — Testcontainers:** named test 1 (client A requesting client B's delivery gets
      404) and every other request-level assertion, owned by TASK-008-28.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A01, A05).

## Definition of Done

Code written, unit tests passing. **Do not run `./gradlew test` or `./gradlew build`.** **Do not
run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
