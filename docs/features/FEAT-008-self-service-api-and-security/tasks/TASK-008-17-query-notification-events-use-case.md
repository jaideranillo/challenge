---
id: TASK-008-17
feature: FEAT-008
title: QueryNotificationEventsUseCaseImpl — clamped page size, default date window
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-008-12, TASK-008-14]
date: 2026-09-21
---

# TASK-008-17: `QueryNotificationEventsUseCaseImpl`

## Feature

FEAT-008

## Assigned Agent

`backend-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/usecase/QueryNotificationEventsUseCaseImpl.java` (new)
  - `src/main/java/com/cobre/challenge/application/usecase/config/SelfServiceQueryProperties.java` (new)
  - `src/test/java/com/cobre/challenge/application/usecase/QueryNotificationEventsUseCaseImplTest.java` (new)
- Concern: the list endpoint's business rules. No SQL, no HTTP.

## Behavior (ADR-005 §1 and Amendment D2)

Map `QueryNotificationEventsCommand` to `DeliveryPageQuery` + `limit`, call
`DeliveryQueryRepositoryPort.findPage(tenant, query, limit)`, return
`QueryNotificationEventsResult` carrying the page's deliveries and its `nextCursor`.

Two rules the use case owns, because ADR-005 Amendment D2 places them "on the way in by the web
adapter or use case rather than by the persistence adapter":

| Rule | Value | Behavior |
|---|---|---|
| Bounded page size | default 50, max 200 | a `limit` above the max is **clamped, not rejected** (ADR-005 §1, explicitly). An absent limit becomes the default |
| Default date window | last 30 days | applied **only when both** `eventCreatedFrom` and `eventCreatedTo` are absent, so an unbounded query can never be issued by accident against a write-hot table. One bound supplied means the caller has expressed intent; do not silently add the other |

Both numbers are **labelled proposals**, not measured (ADR-005 §1 says so in the same class as
ADR-004's Q5 and ADR-006's Q7). Bind them to `SelfServiceQueryProperties`
(`@ConfigurationProperties("challenge.self-service.query")`) with those defaults and a comment
saying they are proposals. Do not invent a third knob.

The window is computed against an injected `Clock` — the repo has no `ClockPort` (ADR-005 §1
resolved it as not needed), so take a `java.time.Clock` in the constructor and make the tests
deterministic with a fixed one. Never call `Instant.now()` directly.

`@Transactional(transactionManager = "apiTransactionManager", readOnly = true)` on the
implementation. **The qualifier is mandatory**: the default transaction manager is the pipeline's,
and a read on the pipeline manager would use the pipeline pool, silently bypass RLS, and leave the
session variable unset. This is the single most consequential line in the file — the
`TenantSessionBinder`'s `SET LOCAL` needs this transaction to exist, and it must be this one.

## Out of Scope

- Anything HTTP — parameter parsing, the `limit` query parameter, response DTOs, status codes:
  TASK-008-25.
- Cursor encoding/decoding. `DeliveryPageCursor` is merged and lives in the adapter; the cursor is
  an opaque `String` at this layer and this use case must not parse it.
- Validating that a caller-supplied cursor belongs to the caller. It is opaque and the query is
  tenant-scoped regardless; a foreign cursor yields the tenant's own rows from that keyset
  position, not another tenant's.
- The other two use cases.
- Any change to `DeliveryPageQuery`.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**
Every rule this use case owns — clamping, the default window, cursor propagation — is
unit-testable against a fake or mocked `DeliveryQueryRepositoryPort` and a fixed `Clock`, so
nothing here is deferred. Do not write a `@SpringBootTest` to prove the `@Transactional`
qualifier; assert it by reading the annotation if you assert it at all, and leave the live
behavior to the deferred suite. Verify with `./gradlew compileJava compileTestJava` plus this
task's unit tests; **do not run `./gradlew test` or `./gradlew build`.**

## Acceptance Criteria

- [ ] Implements `QueryNotificationEventsUseCase`; one public method.
- [ ] `@Transactional` names `apiTransactionManager` and is `readOnly = true`.
- [ ] A limit above the max is clamped to the max; a limit at or below is passed through; an
      absent/zero limit becomes the default. **No request is rejected for an oversized limit.**
- [ ] With both date bounds absent, a 30-day window ending now is applied.
- [ ] With **either** bound supplied, no default is injected for the other.
- [ ] `eventCreatedFrom` / `eventCreatedTo` map to `DeliveryPageQuery`'s same-named components —
      `deliveries.created_at` is never involved.
- [ ] The tenant is passed through unchanged; the use case never constructs a `TenantId` and never
      reads one from anywhere but the command.
- [ ] `nextCursor` is propagated as received, `Optional.empty()` when the page is the last.
- [ ] Time comes from an injected `Clock`; tests use a fixed one.
- [ ] Unit tests against a fake/mock port: clamping at and above the max, default applied, default
      not applied when one bound is given, cursor propagation, empty page yields an empty list and
      `Optional.empty()`, never `null`.
- [ ] Nothing in this class imports `org.springframework.security.**` or `org.springframework.jdbc.**`.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A01, A06).

## Definition of Done

Code and tests written, tests passing locally. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

### Handover

- `QueryNotificationEventsUseCaseImpl` (new): three-step body — clamp `limit` via
  `SelfServiceQueryProperties` (`defaultPageSize`, `maxPageSize`), apply the default 30-day window
  only when both `eventCreatedFrom`/`eventCreatedTo` are absent (via injected `Clock`), delegate to
  `DeliveryQueryRepositoryPort.findPage`. `@Transactional(transactionManager =
  "apiTransactionManager", readOnly = true)`.
- `SelfServiceQueryProperties` (new, `application/usecase/config`):
  `@ConfigurationProperties("challenge.self-service.query")` record with `@DefaultValue`-annotated
  components (`defaultPageSize=50`, `maxPageSize=200`, `defaultWindow=30d`); no third knob added.
  Not wired into `application.yaml` — out of this task's file scope, and Spring's `@DefaultValue`
  binding means it needs no entry there to resolve.
- Registered via `@EnableConfigurationProperties(SelfServiceQueryProperties.class)` on the use case
  class itself (same pattern as `OutboundUrlValidator`/`EgressProperties`), not a separate
  `@Configuration` class — YAGNI, one consumer.
- **Contract note, not fixed here (see `docs/concerns.md`):** `QueryNotificationEventsCommand`'s
  compact constructor throws for `limit <= 0` (TASK-008-14, unchanged by design), so the "absent/zero
  limit becomes the default" rule can never actually be exercised through the command as merged —
  a caller can never construct a zero/negative-limit command to reach this use case. The clamp
  method still guards `<= 0` defensively (matches the literal ADR-005 §1 rule, costs one branch,
  harmless), but the unit test suite only exercises reachable inputs (above max, at max, below max)
  since a zero-limit command cannot be built to test the branch directly.
- Unit tests (`QueryNotificationEventsUseCaseImplTest`, 10 tests, fake
  `DeliveryQueryRepositoryPort`, fixed `Clock`): clamp above max, at max, below max unchanged;
  default window applied only when both bounds absent; no default injected when only one bound is
  given (both directions); tenant passed through unchanged; cursor propagated; empty page yields
  empty list and `Optional.empty()`; deliveries from the page are propagated.
- `./gradlew compileJava compileTestJava` passes; targeted test run
  (`--tests QueryNotificationEventsUseCaseImplTest`) passes, 10/10.
- Nothing in this class imports `org.springframework.security.**` or `org.springframework.jdbc.**`.
