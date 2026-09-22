---
id: TASK-008-10
feature: FEAT-008
title: TenantSessionBinder — bind app.client_id for the transaction
status: Ready for Review
agent: dba
depends_on: [TASK-008-04, TASK-008-09]
date: 2026-09-21
---

# TASK-008-10: `TenantSessionBinder`

## Feature

FEAT-008

## Assigned Agent

`dba` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/TenantSessionBinder.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/TenantSessionBinderTest.java` (new —
    the mock-template half now, the container half deferred)
- Concern: layer 3 of ADR-007 §5 — putting the tenant into the database session so layer 4's
  policies can read it.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**

Writable now, and required: a plain-JUnit test with a mocked `NamedParameterJdbcTemplate`
asserting that the tenant value is passed as a **bound parameter** and that the SQL text contains
no interpolated tenant value. That is the A05 property and it needs no database.

**DEFERRED — Testcontainers:** everything about what PostgreSQL actually does with the setting —
the value being readable inside the transaction, and being `NULL` again on a later transaction
from the same pool.

## What it does (ADR-007 §5.3)

One collaborator with one method, taking a `TenantId`, that sets the transaction-scoped session
variable `app.client_id` on the current connection of the **API pool**.

```
void bind(TenantId tenant);
```

Two properties are fixed by the ADR and are not implementation latitude:

1. **Transaction-scoped.** The value must be discarded at commit or rollback so it cannot leak to
   the next borrower of a pooled connection. Use `SET LOCAL`, or equivalently
   `select set_config('app.client_id', :value, true)` — the third argument `true` is what makes it
   local. **This is the form to prefer**, because it takes the value as a **bound parameter**:
   `SET LOCAL` requires a literal, and interpolating a tenant into a session-configuration
   statement is exactly the A05 path ADR-007 §3 calls out the `client_id` format rule for.
   Interpolating the value into SQL text is a defect even though `TenantId` validates its format —
   both layers, not one.
2. **A collaborator, not an interceptor.** ADR-007 §5.3 is explicit: not AOP, not a
   `@Transactional` aspect. The binding must be visible in the call path so it cannot be silently
   disabled by an annotation being forgotten. The tenant-scoped adapters call it (TASK-008-15,
   TASK-008-16).

**A transaction that never binds is not a security hole**, because layer 4 fails closed: the
variable is `NULL` and the policy matches nothing. Do not add a guard that throws when no
transaction is active, and above all do not add a fallback value — a default tenant here would
convert a fail-closed design into a fail-open one.

It uses `apiJdbcTemplate` (TASK-008-09). It must never touch the primary/pipeline pool: binding a
tenant on a pipeline connection would be meaningless (that role is outside the policies) and
misleading.

## Out of Scope

- Calling it from any adapter — TASK-008-15 and TASK-008-16.
- Any `@Transactional` annotation. The use cases own transaction boundaries.
- Reading the tenant from anywhere. It takes a `TenantId` parameter and uses it.
- Any policy or role change — TASK-008-07, TASK-008-08.

## Acceptance Criteria

- [ ] One public method taking a `TenantId`; no overload taking a `String`.
- [ ] The value is passed as a **bound parameter**, never concatenated or interpolated into SQL
      text (A05).
- [ ] The statement uses the transaction-local form (`SET LOCAL`, or `set_config(..., true)`),
      asserted now against a mocked template by inspecting the SQL and the bound parameters.
- [ ] No fallback, no default, no throw-on-missing-transaction.
- [ ] **DEFERRED — Testcontainers:** within a transaction, after `bind`,
      `current_setting('app.client_id', true)` returns the tenant's value.
- [ ] **DEFERRED — Testcontainers:** after commit or rollback, a fresh transaction on a
      connection from the same pool sees `current_setting('app.client_id', true)` as `NULL`.
      This is the property that prevents cross-request tenant leakage through the pool and it
      must be proven against a real database when the suite is run.
- [ ] It uses only the API pool's template.
- [ ] The class javadoc is one line, per repo convention, and says the binding is deliberately a
      call, not an aspect.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A05, A10).

## Definition of Done

Code written, the mock-template unit test passing, the container assertions specified but not
written. **Do not run `./gradlew test` or `./gradlew build`** — verify with `./gradlew compileJava
compileTestJava`. **Do not run `git add` or `git commit`.** Set this task's `status` to
`Ready for Review` and stop.

## Status

Not Started <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
