---
id: TASK-008-16
feature: FEAT-008
title: Event and attempt query adapters on the API pool
status: Ready for Review
agent: dba
depends_on: [TASK-008-09, TASK-008-10, TASK-008-13]
date: 2026-09-21
---

# TASK-008-16: Event and Attempt Query Adapters

## Feature

FEAT-008

## Assigned Agent

`dba` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/NotificationEventQueryJdbcRepository.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/DeliveryAttemptQueryJdbcRepository.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/ClientFacingQueryAdaptersTest.java`
    (**deferred this phase** — specified below, not written now)
- Concern: implement TASK-008-13's two ports, on the API pool, tenant-bound.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**
Both adapters are SQL against a real schema, so their tests are `DEFERRED — Testcontainers`. Write
the production classes, verify with `./gradlew compileJava compileTestJava`, and report the
deferred tests as specified and pending. Do not imitate them with H2 or a mocked `JdbcTemplate`
that asserts an SQL string — a string-equality test on SQL proves nothing and locks the query
shape against future tuning.

## What to build

Both classes follow the same three rules as TASK-008-15: the qualified `apiJdbcTemplate`, a
`TenantSessionBinder.bind(tenant)` call before the query, and the tenant as a **bound named
parameter** in the SQL — never concatenated, never a post-hoc Java filter.

**`NotificationEventQueryJdbcRepository`** — `findById(String eventId, TenantId tenant)`:
`SELECT ... FROM notification_events WHERE event_id = :event_id AND client_id = :client_id`.
Reuse the merged `NotificationEventRowMapper`; do not write a second mapper for the same table.

> **Architect correction, 2026-09-21 (was `UUID eventId`).** `eventId` is a `String` bound directly
> against the `text` column — no `UUID`, no `.toString()`. See the resolution in
> `docs/concerns.md` and the corrected contract in TASK-008-13.

**`DeliveryAttemptQueryJdbcRepository`** — `findByDeliveryId(UUID deliveryId, TenantId tenant)`:
`delivery_attempts` has **no `client_id` column**, so the tenant predicate goes through the parent
`deliveries` row — an `EXISTS` or a join against `deliveries` on `delivery_id` with
`deliveries.client_id = :client_id`. The same shape the RLS policy uses (TASK-008-08). Ordered by
`attempt_number`, matching the existing `DeliveryAttemptRepositoryPort.findByDeliveryId` contract
and the `idx_delivery_attempts_delivery_attempt` index. Reuse `DeliveryAttemptRowMapper`.

Neither class is `@Transactional`.

Both return `Optional.empty()` / an empty list for a foreign row, and there must be no way for a
caller to learn the row exists elsewhere (ADR-007 §5.5). Concretely: no distinct exception, no
distinct log line at info or above, no count.

## Out of Scope

- `NotificationEventJdbcRepository` and `DeliveryAttemptJdbcRepository` — the merged pipeline
  adapters. They stay on the primary pool, keep their cross-tenant methods, and are **not**
  modified, not deprecated, and not made to delegate to these new classes.
- Any row mapper change.
- Any migration, index or policy change. If the attempt query needs an index that does not exist,
  report it in the handover rather than adding one — an index is its own task.
- Any use case or controller.

## Acceptance Criteria

- [ ] Both classes implement their TASK-008-13 port and take the qualified API template.
- [ ] `TenantSessionBinder.bind(...)` is called before every query in both classes.
- [ ] The tenant is a bound named parameter in both queries (A05).
- [ ] The attempt query expresses the tenant through the parent `deliveries` row and orders by
      `attempt_number`.
- [ ] Existing row mappers are reused; no duplicate mapper is written.
- [ ] Neither class is `@Transactional`.
- [ ] The merged pipeline adapters are untouched.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A01, A05).
- [ ] **DEFERRED — Testcontainers**, for both adapters: the owning tenant sees the row; **a
      different tenant gets `Optional.empty()` / an empty list**; a nonexistent id gets the
      identical result; an attempt list comes back ordered by `attempt_number`.
- [ ] **DEFERRED — Testcontainers:** the merged pipeline adapters' tests pass unchanged.

## Definition of Done

Production code written and compiling; deferred tests specified, not written. **Do not run
`./gradlew test` or `./gradlew build`** — verify with `./gradlew compileJava compileTestJava`.
**Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

### Architect follow-up (2026-09-21) — adapter corrected, no further work for dba

The mismatch flagged in this task's handover is resolved: the port parameter is now
`String eventId`, so the adapter binds it directly against the `text` column and the method is
functionally live instead of always-empty. The code change was **applied by the architect
directly** in `NotificationEventQueryJdbcRepository.java` (parameter type, the removed
`.toString()`, the removed `UUID` import, and the now-obsolete `concerns.md` javadoc note).
`./gradlew compileJava compileTestJava` passes. **No action is required from `dba` on this
task** — it stays at its current status. The deferred Testcontainers test for this adapter should
now assert a real `text` id (e.g. `EVT001`) round-trips, which was not previously possible.
