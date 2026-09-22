---
id: TASK-008-15
feature: FEAT-008
title: DeliveryQueryJdbcRepository on the API pool, bound to TenantId
status: Ready for Review
agent: dba
depends_on: [TASK-008-09, TASK-008-10, TASK-008-12]
date: 2026-09-21
---

# TASK-008-15: `DeliveryQueryJdbcRepository` on the API Pool

## Feature

FEAT-008

## Assigned Agent

`dba` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/DeliveryQueryJdbcRepository.java` (modified)
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/DeliveryQueryJdbcRepositoryTest.java`
    (modified — construction sites and types only)
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/DeliveryFindPageTest.java`
    (modified — construction sites and types only)
- Concern: make the merged query adapter satisfy TASK-008-12's signature, run on the API pool,
  and bind the tenant into the session.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**
The two existing test classes here are Testcontainers tests. **Update them only so they compile**
against the new `TenantId` signature — change construction sites and types, change no assertion,
delete no assertion, and **do not run them**. Any *new* behavioral assertion is
`DEFERRED — Testcontainers`.

## The three changes

1. **Signature:** `findById(UUID, TenantId)` and `findPage(TenantId, DeliveryPageQuery, int)`.
   The existing SQL binds `client_id` already — bind `tenant.value()` (or whatever the accessor is
   named) instead of the old `String`. **The predicate stays in the SQL**: it is never a post-hoc
   Java filter, and the class javadoc's existing paragraph saying so stays true.
2. **Pool:** inject the qualified `apiJdbcTemplate` (TASK-008-09), not the primary template. This
   is the wiring that makes RLS apply to this class. ADR-007's Consequences section names
   "the API path accidentally wired to the pipeline pool" as the new way to be wrong, so make the
   qualifier explicit and obvious at the constructor, not resolved by type.
3. **Session binding:** call `TenantSessionBinder.bind(tenant)` (TASK-008-10) at the start of each
   method, before the query. Layer 3 and layer 4 are both required by option I-D; the bound SQL
   predicate does not replace the session variable and the session variable does not replace the
   bound predicate.

**Nothing else changes.** The keyset SQL, the `(event_created_at, delivery_id)` tuple, the cursor
codec, the `DeliveryPageQuery` filter handling, the `event_created_at` bounds of ADR-005 Amendment
D2, the column list, the row mapper — all unchanged. This is a type, a pool and a call, not a
rewrite of a tested query.

Do not add `@Transactional` here. Transactions belong to the use cases (TASK-008-17, -18), and the
`SET LOCAL` is transaction-scoped: with no transaction the variable is not set, the policy matches
nothing, and the result is zero rows — fail closed, exactly as ADR-007 §5.4 designs.

## Out of Scope

- `DeliveryPipelineJdbcRepository` and every other merged adapter. They stay on the primary pool
  and keep working unchanged.
- The two new query adapters — TASK-008-16.
- Any SQL change beyond the parameter's Java type.
- Any index, migration or policy change.

## Acceptance Criteria

- [ ] Both methods take `TenantId` and bind it as a **named parameter** in the `WHERE` clause;
      nothing is concatenated into SQL text (A05).
- [ ] The class takes the qualified API `NamedParameterJdbcTemplate` in its constructor, by an
      explicit qualifier.
- [ ] `TenantSessionBinder.bind(...)` is called before every query in this class.
- [ ] No `@Transactional` is added to this class.
- [ ] The keyset SQL, cursor handling and filter semantics are byte-for-byte unchanged apart from
      the parameter's type.
- [ ] The existing test classes compile against the new signature with **construction-site and
      type changes only**; **no existing assertion about pagination, ordering or filtering is
      weakened or deleted.**
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A01, A05).
- [ ] **DEFERRED — Testcontainers:** a `findById` for a delivery belonging to a different tenant
      returns `Optional.empty()` when run through the API pool.
- [ ] **DEFERRED — Testcontainers:** the updated existing tests pass unchanged in substance.

## Definition of Done

Production code written, tests updated to compile, nothing run. **Do not run `./gradlew test` or
`./gradlew build`** — verify with `./gradlew compileJava compileTestJava` and report the deferred
assertions as specified and pending. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
