---
id: TASK-003-12
feature: FEAT-003
title: Outbound ports — persistence
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-003-04, TASK-003-05]
date: 2026-09-20
---

# TASK-003-12: Outbound ports — persistence

## Feature

FEAT-003

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

The three persistence `port/out` interfaces from ADR-005 §1. **Interfaces only. No implementation, no SQL, no adapter.** The Spring Data JDBC / `NamedParameterJdbcTemplate` implementations are the `dba` agent's work in a later feature.

> **Addendum (2026-09-20):** `DeliveryRepositoryPort`'s page-read result type was originally nested. Superseded by user request — extracted to `dto/DeliveryPage.java`. See `docs/concerns.md`.

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/out/persistence/DeliveryRepositoryPort.java`
  - `src/main/java/com/cobre/challenge/application/port/out/persistence/DeliveryAttemptRepositoryPort.java`
  - `src/main/java/com/cobre/challenge/application/port/out/persistence/SubscriptionRepositoryPort.java`
  - `src/main/java/com/cobre/challenge/application/port/out/persistence/dto/DeliveryPage.java`
- Concern: the outbound persistence contracts.

**`DeliveryRepositoryPort`** — keep it to what the current use cases need (YAGNI); no speculative finders.
- Insert a new delivery, returning the persisted `Delivery`. Used by ingest, replay and recovery alike — one insert shape, not three.
- A **state-guarded** status transition: something of the shape `boolean transitionStatus(UUID deliveryId, DeliveryStatus expected, DeliveryStatus target, ...)` returning whether one row was affected. The zero-rows case is a normal, expected outcome (ADR-002 §2.2 step 2), so it is a return value, never an exception.
- Claim due deliveries for dispatch, bounded by a batch limit and an as-of `Instant` passed in by the caller.
- `Optional<Delivery> findById(UUID deliveryId, String clientId)` — **tenant-scoped in the signature**, so an unscoped read is inexpressible (A01/IDOR, ADR-003 §2, ADR-005 §1).
- A keyset page read for the list endpoint, taking `clientId`, the optional filters, a cursor and a limit, returning a list plus the next cursor.

**`DeliveryAttemptRepositoryPort`** — append-only (ADR-003 §3).
- Insert one attempt.
- `List<DeliveryAttempt> findByDeliveryId(UUID deliveryId)`, ordered by attempt number, empty list when none. **No update and no delete method** — the table is append-only by design and the port must not offer a way to violate that.

**`SubscriptionRepositoryPort`**
- `List<Subscription> findActiveForEvent(String clientId, String eventType)` — `clientId` is part of the query predicate, not a post-hoc filter. ADR-003 §2 makes this the single point where a cross-tenant leak could originate; the signature is the control.
- `Optional<Subscription> findById(UUID subscriptionId)` for the worker's per-attempt row load (ADR-002 §2.2 step 1).
- Conditional writes for the subscription-level side effects the classification table produces: deactivate (404/410), set `throttledUntil` (429), and the circuit-state transitions of ADR-006 §1.2 — each returning whether one row was affected, matching the first-writer-wins conditional-`UPDATE` pattern.

## Out of Scope

- **No implementation of any of these.** No `adapter/out/persistence` package, no `JdbcClient`, no `@Repository`, no SQL string anywhere in this task.
- No migration and no schema change — the tables already exist from FEAT-002.
- No `ClockPort`. Every method that needs time takes an `Instant` parameter (see FEAT-003 scope).
- No generic `save(Delivery)` upsert. A blanket upsert would let a caller bypass the state-guarded transition that ADR-003 §1.1 makes the safety property of the whole pipeline. Do not add one.
- No pagination type borrowed from Spring Data (`Page`, `Pageable`, `Slice`).

## Acceptance Criteria

- [ ] Exactly three interfaces; every method returns a domain type, `Optional`, an empty-able collection, or a primitive — never `null`.
- [ ] Every read that can reach another tenant's data takes `clientId` as a parameter; there is no unscoped `findById(UUID)` on `DeliveryRepositoryPort`.
- [ ] The state transition method returns a boolean/affected-row count rather than throwing when zero rows match.
- [ ] `DeliveryAttemptRepositoryPort` exposes no mutation beyond insert.
- [ ] No import from `org.springframework`, `javax.sql`, `java.sql`, or any JDBC type.
- [ ] Tests written and passing — compile-level for interface-only files; no Testcontainers in this task (there is nothing to integrate yet).
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition — split by consumer rather than one fat repository (ISP), and no method added for a use case that does not exist yet.
- [ ] No new OWASP Top 10:2025 exposure introduced. This task carries the A01 structural tenant-scoping decision; if a signature cannot carry `clientId`, flag it to `security-engineer` instead of dropping it.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
