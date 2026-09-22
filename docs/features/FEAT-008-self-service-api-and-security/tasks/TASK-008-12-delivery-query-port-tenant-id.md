---
id: TASK-008-12
feature: FEAT-008
title: DeliveryQueryRepositoryPort takes TenantId (named test 2)
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-008-04]
date: 2026-09-21
---

# TASK-008-12: `DeliveryQueryRepositoryPort` Takes `TenantId`

## Feature

FEAT-008

## Assigned Agent

`backend-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/out/persistence/DeliveryQueryRepositoryPort.java` (modified)
  - `src/test/java/com/cobre/challenge/application/port/out/persistence/PersistencePortsTest.java` (modified)
- Concern: the compile-time half of option I-D on the delivery query port. Signature change only.

## The change (ADR-007 §5.2, Amendment E1)

| Before | After |
|---|---|
| `Optional<Delivery> findById(UUID deliveryId, String clientId)` | `Optional<Delivery> findById(UUID deliveryId, TenantId tenant)` |
| `DeliveryPage findPage(String clientId, DeliveryPageQuery query, int limit)` | `DeliveryPage findPage(TenantId tenant, DeliveryPageQuery query, int limit)` |

`DeliveryPageQuery`, `DeliveryPage`, the keyset tuple, the filter semantics and the
`clientId`-first parameter position of ADR-005 Amendment D3 are all **unchanged**. This is a type
change on one parameter, nothing else.

**No overload without a tenant is added, in this task or ever.** ADR-007 §5.2: *"There is no
`findById(DeliveryId)`. Not deprecated, not discouraged: absent."* A developer who wants to load a
delivery without a tenant has nothing on this interface to call.

Update the javadoc to say `TenantId` where it says `clientId`, and keep the existing paragraph
explaining why `DeliveryPipelineRepositoryPort.findById(UUID)` is safe without one.

## Named test 2 — the user's wording, verbatim

> **A repository call that omits the tenant parameter must not compile.**

That property is delivered structurally here: the absence of any unscoped overload means such a
call has no method to bind to and is a compile error. It is not something a runtime test can
assert, and no reflective "does this method exist" test should be written to simulate it. State
in the handover that the property holds because no such method exists. The complementary
mechanical guard is TASK-008-06's ArchUnit rule 4.

## Out of Scope

- `DeliveryPipelineRepositoryPort` — cross-tenant by design (ADR-007 Amendment E1). **Do not add
  a `TenantId` to it.**
- `SubscriptionRepositoryPort`, `NotificationEventRepositoryPort`,
  `DeliveryAttemptRepositoryPort` — same reason.
- The JDBC adapter — TASK-008-15. It will not compile until that task lands; that is expected and
  is why the two are separate tasks in adjacent waves. Coordinate in the handover rather than
  reaching into the adapter here.
- The new client-facing ports — TASK-008-13.
- `port/in` commands — TASK-008-14.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**
`PersistencePortsTest` is a reflective shape test and needs neither, so it is in scope now.
Named test 2 is a compile-time property and is not a test method at all. Verify with
`./gradlew compileJava compileTestJava`; **do not run `./gradlew test` or `./gradlew build`.**

## Acceptance Criteria

- [ ] Both methods take `TenantId`; neither takes a `String` tenant.
- [ ] No unscoped overload exists on this interface.
- [ ] `DeliveryPageQuery` and `DeliveryPage` are untouched.
- [ ] `TenantId` is the first parameter of `findPage` and the second of `findById`, preserving the
      existing positions exactly.
- [ ] `PersistencePortsTest` is updated to assert the new shape, and still asserts that
      `DeliveryPipelineRepositoryPort` carries **no** tenant parameter.
- [ ] Javadoc updated; no `clientId` wording left behind on this interface.
- [ ] The handover states that named test 2 holds structurally and names the ArchUnit rule that
      backs it.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A01).

## Definition of Done

Change written; `compileJava` on this file's dependents may fail until TASK-008-15 lands — say so
in the handover rather than widening scope. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

### Handover

- `findById` and `findPage` on `DeliveryQueryRepositoryPort` now take `TenantId` at the same
  positions as before (second and first parameter respectively). No unscoped overload added.
- Named test 2 ("a repository call that omits the tenant parameter must not compile") holds
  structurally: no overload of either method exists without a `TenantId` parameter, so such a
  call has nothing to bind to. The mechanical guard is
  `TenantBoundaryArchTest.client_facing_query_ports_require_tenant_id_parameter` (rule 4), which
  checks this interface (and the two new ones from TASK-008-13) by simple class name.
- `PersistencePortsTest` updated: replaced the old `String clientId`-shape assertions with
  `TenantId`-shape assertions (`everyMethodOnDeliveryQueryRepositoryPortHasTenantIdParameter`,
  `noMethodOnDeliveryQueryRepositoryPortHasStringClientIdParameter`, and position-specific
  `findByIdTakesTenantIdAsSecondParameter` / `findPageTakesTenantIdAsFirstParameter`). The
  `DeliveryPipelineRepositoryPort`-has-no-tenant assertion is untouched.
- **Known, expected breakage (out of scope for this task):** `DeliveryQueryJdbcRepository`
  (`src/main/java/com/cobre/challenge/adapter/out/persistence/DeliveryQueryJdbcRepository.java`)
  implements this interface with `String clientId` and no longer compiles
  (`./gradlew compileJava` fails there). This is exactly the TASK-008-15 dependency called out in
  this task's Definition of Done — not fixed here. Verified separately (via a throwaway local
  patch, reverted with `git checkout --`, not committed) that with the adapter's parameter types
  updated to `TenantId`, `compileJava` succeeds; the only remaining failures are in the
  Testcontainers integration tests `DeliveryFindPageTest` and `DeliveryQueryJdbcRepositoryTest`
  (also `String clientId` call sites), which belong to the adapter's own task wave.
- `./gradlew compileJava compileTestJava` on the current tree fails only at
  `DeliveryQueryJdbcRepository` (3 errors), as expected.
