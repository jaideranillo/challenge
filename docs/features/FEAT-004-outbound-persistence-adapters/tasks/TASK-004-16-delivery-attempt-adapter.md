---
id: TASK-004-16
feature: FEAT-004
title: "DeliveryAttemptJdbcRepository: append-only attempt insert and history read, with tests"
status: Ready for Review
agent: dba
depends_on: [TASK-004-05]
date: 2026-09-20
---

# TASK-004-16: the attempt-history adapter

## Feature

FEAT-004

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/DeliveryAttemptJdbcRepository.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/DeliveryAttemptJdbcRepositoryTest.java` (new)
- Concern: the append-only attempt log.

Implements `DeliveryAttemptRepositoryPort` unchanged (the revision did not touch it). `@Repository`, no `@Transactional`. Cross-tenant: `delivery_attempts` has no `client_id` column, and isolation for the client-facing path is enforced by resolving the parent delivery through `DeliveryQueryRepositoryPort` first (ADR-007 §5.5's ordering). Comment that.

### `insert(DeliveryAttempt)`

One `INSERT ... RETURNING`.

- **`id` is an identity column and is never bound.** Take it from `RETURNING`.
- **`response_excerpt` is `varchar(1000)`; truncate before binding.** A client returning a 2 MB error page must not fail the insert — that would turn a failed delivery into a failed *transaction* and lose the outcome write paired with it (A10). Truncate to the column width, and do not log what was cut.
- Native enum columns cast explicitly.
- Append-only: this class has **no** update and **no** delete method, and none may be added. ADR-003 §3 makes this table the audit trail a client can query (ADR-005 §1); an `UPDATE` path would make it unable to serve that purpose.

### `findByDeliveryId(UUID)`

```sql
SELECT <explicit columns> FROM delivery_attempts
 WHERE delivery_id = :delivery_id ORDER BY attempted_at ASC
```

Chronological, because this feeds the "you never called me at 14:02" answer (ADR-005 §1). Empty list, never null.

### Required tests

1. Insert round trip, every column, including a null `response_status` (a connection failure has no status) and a null `response_excerpt`.
2. **A `response_excerpt` longer than 1000 characters is truncated and the insert succeeds.** Assert the stored length and that no exception was thrown.
3. `findByDeliveryId` returns attempts in ascending `attempted_at` order; seed out of insertion order to prove the `ORDER BY` does the work.
4. An unknown `delivery_id` returns an empty list, not null.
5. Two attempts for the same delivery both persist, with distinct identity ids. Append-only, no upsert.
6. The FK to `deliveries` is enforced: inserting an attempt for a non-existent `delivery_id` fails. Proves the fixture is honest about the parent relationship.

Real Postgres via `TestcontainersConfiguration`. No H2, no mocked JDBC.

## Out of Scope

- The `deliveries` status write that accompanies an attempt (TASK-004-09). Pairing the two in one transaction is the use case's.
- **No `delivery_attempts` row for a deferral.** TASK-004-10 asserts the absence; this task must not provide a path that would create one.
- No aggregate or monitoring query (`COUNT`, success rate, percentiles). Deferred with ADR-003 §3's monitoring notes.
- No update, no delete, no upsert method. Append-only.
- No `client_id` predicate; the column does not exist.
- No response-classification logic (ADR-004 §1's table is the domain's).

## Acceptance Criteria

- [ ] Implements `DeliveryAttemptRepositoryPort`; `@Repository`; no `@Transactional`; no mutable state.
- [ ] `id` is never bound and comes from `RETURNING`.
- [ ] `response_excerpt` is truncated to the column width before binding, and the truncated content is not logged.
- [ ] No update, delete or upsert method exists on the class.
- [ ] `findByDeliveryId` orders by `attempted_at ASC` and returns an empty list for no matches.
- [ ] Explicit column lists; all values bound as named parameters; enums cast explicitly.
- [ ] A comment states why there is no tenant predicate and where isolation is enforced instead (ADR-007 §5.5).
- [ ] Tests written and passing: all six above against real Postgres via `TestcontainersConfiguration`. No H2, no mocked JDBC. `./gradlew test --tests "com.cobre.challenge.adapter.out.persistence.DeliveryAttemptJdbcRepositoryTest"` green.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition. Item 54 (empty collection over null).
- [ ] No new OWASP Top 10:2025 exposure introduced. **A10:** truncation is the control that keeps an oversized client response from failing the outcome transaction. **A09:** `response_excerpt` is the PII layer (ADR-002 §3.1) and is never logged; no fixture contains a real payload. **A05:** bound parameters only.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
