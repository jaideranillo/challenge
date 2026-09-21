---
id: TASK-004-06
feature: FEAT-004
title: "DeliveryPipelineJdbcRepository: insert with trace_context and event_created_at, plus the worker's cross-tenant findById"
status: Ready for Review
agent: dba
depends_on: [TASK-004-03, TASK-004-05]
date: 2026-09-20
---

# TASK-004-06: pipeline adapter — `insert` and `findById`

## Feature

FEAT-004

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

The pipeline adapter class and its two non-conditional methods.

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/DeliveryPipelineJdbcRepository.java` (new; later tasks add methods to it)
- Concern: creating and loading a delivery row.

`@Repository`, constructor-injected `NamedParameterJdbcTemplate` (or `JdbcClient` over it) and `DeliveryRowMapper`. Implements `DeliveryPipelineRepositoryPort`. **No `@Transactional`** — transaction boundaries are the use case's (CLAUDE.md, ADR-005 §1).

### `insert(Delivery)`

One `INSERT ... RETURNING`, returning the persisted `Delivery` through `DeliveryRowMapper`.

- **`event_created_at` is bound from `delivery.eventCreatedAt()`**, never from `now()` and never from `created_at`. This is the whole point of ADR-003 A4: for a `REPLAY` or `RECOVERED` row the two differ, and using the wrong one files the delivery under the day the replay was requested.
- **`trace_context` is bound from `delivery.traceContext()`**, `Optional.orElse(null)` (ADR-003 A3).
- `created_at`/`updated_at` take their column defaults; they are not components of `Delivery`.
- The native enum columns (`status`, `origin`) need an explicit cast, e.g. `:status::delivery_status`. Binding a Java enum through `setObject` fails against a native Postgres enum.
- `replayed_from` is nullable and self-referential; bind null for `origin = 'INGEST'`.

**Do not catch `DuplicateKeyException`.** ADR-003 §2's partial unique index is the idempotency guard and its violation is a real outcome the use case must see (ADR-005 §1 maps it to `409`). Swallowing it here would make a double replay look like a success. Let it propagate.

### `findById(UUID deliveryId)`

One `SELECT ... WHERE delivery_id = :id`, returning `Optional<Delivery>`; empty rather than throwing when absent.

**No `client_id` predicate, deliberately.** This is the worker's load of the row it just claimed (ADR-002 §2.2 steps 5-6) and it runs with no principal. Put a comment on the method naming ADR-007 Amendment E1 and stating the two facts that make it safe: it is on the pipeline port, which no client-facing use case injects, and `DeliveryQueryRepositoryPort.findById` is the tenant-scoped one. A reviewer who sees an unscoped `findById` should not have to go looking for that argument.

Select the columns the mapper needs and name them explicitly. No `SELECT *`.

## Out of Scope

- `claimForProcessing` (TASK-004-07), the four outcome writes (TASK-004-09), `deferDelivery` (TASK-004-10), `claimDue` (TASK-004-11). Same file, later tasks; do not pre-implement them.
- Any method of `DeliveryQueryRepositoryPort` (TASK-004-13, -14).
- No `@Transactional` and no `TransactionTemplate`.
- No `notification_events` write. The gateway's two-table insert is the use case's transaction; this method inserts one `deliveries` row.
- No retry, no `DuplicateKeyException` handling, no fallback on a constraint violation.
- No Spring Data `CrudRepository` and no `@Table`-annotated entity.

## Acceptance Criteria

- [ ] `DeliveryPipelineJdbcRepository` implements `DeliveryPipelineRepositoryPort`, is `@Repository`-annotated, has no `@Transactional`, and holds no mutable state.
- [ ] `insert` binds `event_created_at` from the aggregate, never from `now()` or `created_at`, and binds `trace_context` from the aggregate's `Optional`.
- [ ] Native enum columns are cast explicitly in the SQL.
- [ ] `DuplicateKeyException` is not caught anywhere.
- [ ] `findById` returns `Optional.empty()` for a missing row and carries the comment justifying the absent tenant predicate with its ADR-007 E1 citation.
- [ ] Every value reaches SQL as a named bound parameter. No string concatenation or interpolation in any statement.
- [ ] Explicit column lists; no `SELECT *`.
- [ ] No `synchronized`, no `ThreadLocal` (virtual-thread pinning, ADR-002 §2).
- [ ] Tests written and passing: TASK-004-08 covers both methods. This task's own gate is `./gradlew build` compiling and the existing suite staying green; do not duplicate TASK-004-08's assertions here.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition. DIP: the class is referenced by its port everywhere upstream.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A05:** bound parameters only. **A01:** the unscoped read is justified in-code per ADR-007 E1. **A09:** no statement logs `content` or a payload; log by id if at all.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
