---
id: TASK-004-13
feature: FEAT-004
title: "DeliveryQueryJdbcRepository.findById with a mandatory client_id predicate, plus its cross-tenant test"
status: Ready for Review
agent: dba
depends_on: [TASK-004-05]
date: 2026-09-20
---

# TASK-004-13: the tenant-scoped `findById`

## Feature

FEAT-004

## Assigned Agent

`dba` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

The client-facing query adapter and its single-row read.

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/DeliveryQueryJdbcRepository.java` (new; TASK-004-14 adds `findPage`)
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/DeliveryQueryJdbcRepositoryTest.java` (new; TASK-004-15 extends it)
- Concern: tenant-scoped single-row read.

`@Repository`, constructor-injected `NamedParameterJdbcTemplate` and `DeliveryRowMapper`, implements `DeliveryQueryRepositoryPort`. No `@Transactional`.

### `findById(UUID deliveryId, String clientId)`

```sql
SELECT <explicit columns> FROM deliveries
 WHERE delivery_id = :delivery_id AND client_id = :client_id
```

Returns `Optional<Delivery>`.

**Both predicates are mandatory and `client_id` is bound, never interpolated.** This is ADR-007 §I-B's layer 2 and the layer that carries the isolation guarantee for every client-facing read. ADR-007's own stated weakness of option I-B is that the compiler cannot verify the adapter actually used the parameter it was handed, which is exactly why the cross-tenant test below is a required deliverable and not a nice-to-have.

**A row belonging to another client returns `Optional.empty()`, identically to a row that does not exist.** No exception, no distinguishable error, no log line naming the other tenant. ADR-005 §1 turns this into a `404` rather than a `403` specifically so the endpoint does not leak the existence of another tenant's ids, and that property starts here: an adapter that threw a distinguishable "wrong tenant" exception would hand the web layer the means to leak it.

Add a class-level comment stating the invariant: **every method on this class binds `client_id`**, no unscoped overload will be added, and the cross-tenant read that does exist lives on `DeliveryPipelineRepositoryPort` and is unreachable from a client-facing use case (ADR-007 Amendment E1).

### Required tests

1. Own-tenant row returns the delivery, every component mapped, including `eventCreatedAt` and `traceContext`.
2. **Another client's row returns `Optional.empty()`.** Seed the same `delivery_id` fixture under a different `client_id`, query with the wrong tenant, assert empty. This is the A01 control.
3. A non-existent id returns `Optional.empty()`.
4. **Tests 2 and 3 are indistinguishable at the adapter boundary:** same return, no exception in either. Assert explicitly, since this is what lets ADR-005 §1 return 404 rather than 403.
5. A `client_id` containing SQL metacharacters (`' OR 1=1 --`) returns empty and does not error. Proof the value is bound, not concatenated (A05).

Real Postgres via `TestcontainersConfiguration`. No H2, no mocked JDBC.

## Out of Scope

- `findPage`, the cursor codec, and the keyset (TASK-004-14, -15).
- Any pipeline-port method. Different class.
- `TenantId`. The parameter stays `String clientId` (deferred to ADR-007's feature, logged in `docs/concerns.md`).
- RLS, `SET LOCAL app.client_id`, `TenantSessionBinder`, the second connection pool. Deferred; this adapter runs on the single existing pool.
- No `AuthenticatedTenantResolver` and no security context read. The tenant arrives as a parameter.
- No 404/403 decision. That is the web adapter's; this returns `Optional.empty()`.
- No attempt-history join. `NotificationEventDetail` is assembled by the use case from three ports.

## Acceptance Criteria

- [ ] `DeliveryQueryJdbcRepository` implements `DeliveryQueryRepositoryPort`, is `@Repository`-annotated, has no `@Transactional`, holds no mutable state.
- [ ] `findById`'s `WHERE` clause contains both `delivery_id` and `client_id`, both bound as named parameters.
- [ ] A foreign-tenant row returns `Optional.empty()` and throws nothing.
- [ ] The class comment states the bind-`client_id`-always invariant and cites ADR-007 E1.
- [ ] Explicit column list; no `SELECT *`.
- [ ] Tests written and passing: all five above, against real Postgres via `TestcontainersConfiguration`. `./gradlew test --tests "com.cobre.challenge.adapter.out.persistence.DeliveryQueryJdbcRepositoryTest"` green.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition. Item 55 (`Optional` over null).
- [ ] No new OWASP Top 10:2025 exposure introduced. **A01** is the operative category: test 2 is the control and test 4 is what keeps the 404-not-403 property honest. **A05:** test 5. **A09:** no log line names a tenant or a payload on the not-found path.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
