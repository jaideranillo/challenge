---
id: TASK-008-26
feature: FEAT-008
title: Replay endpoint — Idempotency-Key required, 202/404/409 mapping
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-008-19, TASK-008-25]
date: 2026-09-21
---

# TASK-008-26: Replay Endpoint

## Feature

FEAT-008

## Assigned Agent

`backend-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/web/selfservice/NotificationEventController.java` (modified — one handler added)
  - `src/main/java/com/cobre/challenge/adapter/in/web/selfservice/dto/ReplayAcceptedResponse.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/in/web/selfservice/NotificationEventControllerTest.java` (modified)
- Concern: the third endpoint's HTTP contract.

## The endpoint (ADR-005 §1)

```
POST /notification_events/{notification_event_id}/replay
Idempotency-Key: <required>
```

`@PreAuthorize("hasAuthority('notifications:replay')")` — the **replay** scope, not the read
scope. ADR-007 §4: replay is not a read with a side effect, it is a write that creates pipeline
work and real outbound traffic, and `notifications:replay` **does not imply** `notifications:read`.
A token holding only the read scope must get 403 here.

The tenant arrives as a `TenantId` parameter, exactly as in TASK-008-25.

## `Idempotency-Key` is required (ADR-005 §1)

A missing or blank header is a **400**, before the use case is called. ADR-005 §1 requires the
header for "early HTTP-level rejection of a double click, on top of that DB-level guard" — so it
is mandatory, not optional-with-a-default, and the controller must not synthesize one.

Validate its shape conservatively (bounded length, printable ASCII) and reject otherwise — it is
client-supplied input that this feature will use as a cache key (TASK-008-27).

**The header alone is not the uniqueness guarantee.** The authority is
`idx_deliveries_live_pair`, evaluated at insert time inside the use case. TASK-008-27 adds the
short-TTL early rejection on top. Do not implement any caching or dedupe in this task.

## Status mapping (ADR-005 §1, ADR-007 §5.5)

| Use-case result | Response |
|---|---|
| `Accepted(newDeliveryId, PENDING)` | **202 Accepted**, body carrying the **new** row's id and `status = PENDING` |
| `Rejected(TARGET_NOT_FOUND)` | **404**, identical to a nonexistent id, naming nothing about ownership |
| `Rejected(TARGET_NOT_DEAD)` | **409** |
| `Rejected(LIVE_OR_DELIVERED_ROW_ALREADY_EXISTS)` | **409** |
| Missing/blank/malformed `Idempotency-Key` | **400** |

**The response is an acknowledgment, not an outcome** (ADR-005 §1, stated at length because it is
the thing most likely to be got wrong): the attempt happens later, asynchronously, through the
same relay and worker as any other delivery. There is no outcome to return synchronously — do not
block, do not poll, do not call the pipeline.

The body carries the **new** row's id, because that is now the live delivery to poll. The id the
client already held still resolves to the original `DEAD` record with its history intact.

**409 is only ever reachable for a delivery the caller already owns** (ADR-007 §5.5) — a foreign
id is a 404 long before the state check runs, because the use case resolves tenant-scoped first.
That ordering lives in TASK-008-19 and this controller must not reorder or second-guess it: map
the result it is given, nothing more.

Use the sealed `ReplayDeliveryResult` with an exhaustive switch (Java 21 pattern matching), so a
future variant is a compile error rather than a silent fall-through to a wrong status.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker, no
MockMvc.** Construct the controller with a mocked `ReplayDeliveryUseCase` and call the handler
directly. Every row of the mapping table above is unit-testable that way, including the 400 for a
missing header — **write all of them.**

`DEFERRED — Testcontainers`, owned by TASK-008-28 and named here so they are not lost:
**named test 4's HTTP half** (a real non-`DEAD` delivery returns 409 end to end) and **named test
5** (two rapid replays create one delivery), which depends on `idx_deliveries_live_pair` and
cannot be proven against a mock.

Verify with `./gradlew compileJava compileTestJava` plus this task's unit tests; **do not run
`./gradlew test` or `./gradlew build`.**

## Out of Scope

- The idempotency cache — TASK-008-27.
- The use case's decision logic and the row it builds — TASK-008-19.
- Any queue publish, any wait for an outcome.
- Any mutation of the original `DEAD` row.
- Any new `RejectionReason` beyond the three that now exist.

## Acceptance Criteria

- [ ] `POST /notification_events/{notification_event_id}/replay` exists and carries
      `@PreAuthorize("hasAuthority('notifications:replay')")`.
- [ ] A missing or blank `Idempotency-Key` is a 400 and the use case is **not** called.
- [ ] The header's shape is validated (bounded length, printable ASCII) and a malformed value is
      a 400.
- [ ] The controller never synthesizes or defaults an idempotency key.
- [ ] All four result mappings are implemented exactly as tabulated, via an **exhaustive switch**
      over the sealed result type.
- [ ] 202 carries the **new** delivery id and `PENDING`; it never carries a delivery outcome.
- [ ] The 404 body is identical to a nonexistent id's and names nothing about ownership.
- [ ] Nothing in this handler blocks on, publishes to, or waits for the pipeline.
- [ ] The tenant arrives as a `TenantId` parameter; no parameter is named or bound to a tenant
      spelling (ArchUnit rule 2).
- [ ] Unit tests cover every row of the mapping table, including the missing-header 400 and the
      not-called assertion.
- [ ] **DEFERRED — Testcontainers:** named test 4's HTTP 409 and named test 5 (two rapid replays
      create one delivery), both owned by TASK-008-28.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A01, A07).

## Definition of Done

Code written, unit tests passing. **Do not run `./gradlew test` or `./gradlew build`.** **Do not
run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
