---
id: TASK-008-14
feature: FEAT-008
title: Self-service port/in commands take TenantId, filter names follow ADR-005 D2
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-008-04]
date: 2026-09-21
---

# TASK-008-14: `port/in` Commands Take `TenantId`

## Feature

FEAT-008

## Assigned Agent

`backend-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/in/selfservice/dto/QueryNotificationEventsCommand.java` (modified)
  - `src/main/java/com/cobre/challenge/application/port/in/selfservice/dto/GetNotificationEventCommand.java` (modified)
  - `src/main/java/com/cobre/challenge/application/port/in/selfservice/dto/ReplayDeliveryCommand.java` (modified)
  - `src/test/java/com/cobre/challenge/application/port/in/selfservice/QueryGetReplayUseCasePortsTest.java` (modified)
- Concern: the tenant type on the inbound side, plus one rename ADR-005 Amendment D2 already
  requires. Three small records and their test.

## The changes

| Record | Change |
|---|---|
| `QueryNotificationEventsCommand` | `String clientId` → `TenantId tenant`; `createdFrom` → `eventCreatedFrom`; `createdTo` → `eventCreatedTo` |
| `GetNotificationEventCommand` | `String clientId` → `TenantId tenant` |
| `ReplayDeliveryCommand` | `String clientId` → `TenantId tenant` |

The rename is not cosmetic. ADR-005 Amendment D2 is explicit: the filter bounds
`deliveries.event_created_at`, **not** `deliveries.created_at`, and the two differ for a replayed
row — the delivery row's own `created_at` is when the replay was requested. The port's filter
parameters are named `eventCreatedFrom` / `eventCreatedTo` *"so the call site cannot confuse the
two timestamps"*, and the inbound command must not undo that by keeping the ambiguous name one
layer up. `DeliveryPageQuery` already uses the correct names.

Everything else stays: `Optional` components (never `null`), the `int limit` with its
positive-value check, the `idempotencyKey` on `ReplayDeliveryCommand` (mandatory, ADR-005 §1),
and the compact-constructor validation style already in these files.

## Out of Scope

- The use cases — TASK-008-17, -18, -19.
- `ReplayDeliveryResult`, `Accepted`, `Rejected`, `RejectionReason`, `NotificationEventDetail`,
  `QueryNotificationEventsResult` — unchanged, and the sealed hierarchy must not gain a variant.
- Any `port/out` port — TASK-008-12, TASK-008-13.
- Clamping the limit or defaulting the date window. Those are use-case/web-adapter behavior
  (ADR-005 Amendment D2's closing line), not command-record validation. **Do not** put a max-200
  clamp in a compact constructor.
- Any HTTP DTO.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**
Record validation and port shape are fully unit-testable, so nothing here is deferred. Verify
with `./gradlew compileJava compileTestJava`; **do not run `./gradlew test` or `./gradlew build`.**

## Acceptance Criteria

- [ ] All three commands take `TenantId`; none takes a `String` tenant.
- [ ] `QueryNotificationEventsCommand` exposes `eventCreatedFrom` / `eventCreatedTo` and no
      `createdFrom` / `createdTo` remains anywhere in the file, including javadoc.
- [ ] `idempotencyKey` stays mandatory on `ReplayDeliveryCommand`.
- [ ] `Optional` components are still rejected as `null` with the existing message style; the
      `limit` positivity check is unchanged.
- [ ] No clamping and no date defaulting appears in any of these records.
- [ ] `QueryGetReplayUseCasePortsTest` is updated and still asserts the use-case interfaces'
      one-method shape and the sealed result hierarchy.
- [ ] Nothing in `application/**` imports `org.springframework.security.**` after this change.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A01).

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

### Handover

- `QueryNotificationEventsCommand`, `GetNotificationEventCommand`, `ReplayDeliveryCommand`: `String
  clientId` -> `TenantId tenant` in all three, same positions as before. `QueryNotificationEventsCommand`
  also renamed `createdFrom`/`createdTo` -> `eventCreatedFrom`/`eventCreatedTo` per ADR-005
  Amendment D2, matching `DeliveryPageQuery`'s naming.
- `idempotencyKey` on `ReplayDeliveryCommand` stays mandatory; `Optional` components still
  null-checked with the existing message style; `limit` positivity check unchanged. No clamping,
  no date defaulting added.
- `QueryGetReplayUseCasePortsTest` updated: `TenantId` import added, all three commands'
  construction sites use `new TenantId("client-1")` instead of a raw string, added
  `queryCommandExposesEventCreatedFromAndTo` and `replayCommandRequiresTenant` tests, renamed
  `queryCommandRequiresClientId` -> `queryCommandRequiresTenant` and
  `getCommandRequiresBothDeliveryIdAndClientId` -> `getCommandRequiresBothDeliveryIdAndTenant`.
  The `Delivery`/`NotificationEvent` domain-model construction sites in
  `notificationEventDetailCarriesDeliveryEventAndAttemptHistory` keep their `String clientId`
  arguments unchanged — those records' `clientId` field is out of this task's scope (domain model,
  not `port/in`). The use-case one-method-shape and sealed-result-hierarchy assertions
  (`ReplayDeliveryResult`/`Accepted`/`Rejected`) are untouched.
- `application/**` still imports no `org.springframework.security.**` — grepped, none found.
- No caller outside `application/port/in/selfservice` and its test references these three records
  by field name in a way that breaks; `GetNotificationEventUseCase`, `QueryNotificationEventsUseCase`,
  `ReplayDeliveryUseCase` only reference the record types in method signatures, not field accessors,
  so they compile unaffected. No use case implementation exists yet (TASK-008-17/-18/-19), so there
  is nothing else to update.
- `./gradlew compileJava compileTestJava` fails only at the pre-existing, already-documented
  `DeliveryQueryJdbcRepository` break from TASK-008-12 (TASK-008-15's scope) — unrelated to this
  task's files.
