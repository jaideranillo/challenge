---
id: TASK-005-15
feature: FEAT-005
title: "EventIngestController and its validated request DTO: 202 on every accepted outcome"
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-005-13]
date: 2026-09-20
---

# TASK-005-15: the ingest endpoint

## Feature

FEAT-005

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/web/ingest/EventIngestController.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/in/web/ingest/dto/IngestEventRequest.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/in/web/ingest/dto/IngestEventResponse.java` (new)
- Concern: HTTP in, command out, `202` back. No business logic.

`POST /internal/events`, consuming and producing JSON. Blocking Spring MVC — no `DeferredResult`, no `CompletableFuture` return, no WebFlux type.

**The controller orchestrates and nothing else**: validate the DTO, map it to `RegisterNotificationEventCommand`, call the use case, map the result to `IngestEventResponse`, return `202 Accepted`. No subscription lookup, no idempotency check, no publish, no `clientId` inspection.

**Explicit mapping, both ways.** The request DTO is not the command and the result is not the response, even where the fields line up today — a domain or application type must never be serialized straight onto the wire.

`IngestEventRequest` fields and Bean Validation, per ADR-003 §3's column types:

| Field | Validation |
|---|---|
| `eventId` | `@NotBlank`, `@Size(max = ...)`, pattern restricted to a safe id charset |
| `clientId` | `@NotBlank`, `@Size(max = ...)` |
| `eventType` | `@NotBlank`, `@Size(max = ...)` |
| `content` | `@NotNull`, `@Size(max = ...)` — a producer-supplied free-text payload needs an explicit ceiling, not the servlet default |
| `occurredAt` | `@NotNull`, an `Instant` |

Pick the ceilings from the column types and state the number in a comment; the point is that every one is bounded.

**`202` on every accepted outcome, including the two that look empty.** A replay that created nothing and a client with no matching subscription are both `202` with the mapped result — not `200`, not `204`, not `409`. ADR-002 §1.1 step 4 and ADR-003 §2. A `409` on replay in particular would break the producer's at-least-once retry, which is the thing idempotency exists to serve.

The response carries `deliveryIds` and `newlyCreated`. Nothing else — no subscription details, no echo of `content`.

**Validation failures are `400`** with no payload echoed back in the message. If an exception handler is needed for that, keep it in this package and scoped to this controller; do not add a global `@ControllerAdvice` (that is a cross-cutting decision no ADR has made yet).

Tests: `@WebMvcTest`-style slice with the use case stubbed — `202` and body shape for a normal ingest, for an empty `deliveryIds`, and for `newlyCreated = false`; `400` for each missing or oversized field. The end-to-end proof is TASK-005-16.

## Out of Scope

- **Authentication and authorization between the producer and this endpoint.** Removed from FEAT-005 entirely by Tech Lead directive of 2026-09-20 — there is no security task in this feature to defer it to. This task must not add a `SecurityFilterChain`, a `@PreAuthorize`, or a permit-all matcher, and must not add a bypass for one either. ADR-002 §1.1 step 1's IAM/SigV4 requirement is unchanged and unimplemented; see the feature's Security Impact section.
- Any business rule. If a decision cannot be expressed as validation or mapping, it belongs in the use case.
- A global exception handler, an OpenAPI spec, a bulk endpoint, rate limiting, or an idempotency-key header.
- The self-service API's endpoints (ADR-005/ADR-007).

## Acceptance Criteria

- [ ] `POST /internal/events` returns `202` for a normal ingest, for a replay, and for a client with no matching subscription.
- [ ] The controller contains no conditional business logic, no repository or queue reference, and no `clientId` inspection.
- [ ] Request DTO and response DTO are distinct types from the command and result, with explicit mapping in both directions.
- [ ] Every request field is bounded by Bean Validation, `content` included, with the ceiling stated in a comment.
- [ ] `@Valid` is applied and a validation failure yields `400` without echoing the submitted value.
- [ ] No `@ControllerAdvice`, no security configuration, no `DeferredResult`, no reactive type.
- [ ] The response body carries `deliveryIds` and `newlyCreated` and nothing else.
- [ ] Tests written and passing: the three `202` shapes and the `400` cases.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced by this task's own code. **A05:** bounded, validated input is the first control; the adapters bind parameters. **A09:** no submitted value is echoed into an error body or a log. **A07:** the endpoint ships **unauthenticated** — an accepted exposure of this feature, not an oversight of this task, and recorded as such in the feature's Security Impact table and in `docs/concerns.md`. Do not close it here and do not weaken anything to work around it.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
