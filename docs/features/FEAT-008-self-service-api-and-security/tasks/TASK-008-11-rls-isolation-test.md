---
id: TASK-008-11
feature: FEAT-008
title: RLS isolation fixture and fail-closed test (named test 3)
status: Not Started
agent: dba
depends_on: [TASK-008-08, TASK-008-09, TASK-008-10]
date: 2026-09-21
---

# TASK-008-11: RLS Isolation Fixture and Fail-Closed Test

## DEFERRED IN THIS PHASE — DO NOT IMPLEMENT YET

**This entire task is Testcontainers work and is deferred on Tech Lead direction of 2026-09-21,
the same phase rule FEAT-007 ran under.** Nothing in it can be written as a unit test: RLS
policies, role privileges and session variables are exactly the things a fake database does not
have, and an H2 or mocked-JDBC imitation would go green while proving nothing — which is worse
than an absent test.

**What to do now: nothing.** The specification below is the durable record so the work is not
lost. When the Tech Lead says the Testcontainers suite is in scope, implement it exactly as
written, starting with scenario 1. Leave this task's `status` at `Not Started` until then.

## Feature

FEAT-008

## Assigned Agent

`dba` — this task is only for this agent.

## Scope (when implemented)

- File(s):
  - `src/test/java/com/cobre/challenge/TestcontainersConfiguration.java` (modified — the
    `challenge_api` credentials registered as test properties)
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/RowLevelSecurityIsolationTest.java` (new)
- Concern: proving, behaviorally and through the right role, that layer 4 works.

## Why this task exists as its own task (ADR-007 §5.4)

> "A test that runs as the table owner proves nothing."

This is the most likely way the whole design is implemented and verified incorrectly: a test
connecting as the owner bypasses every policy and goes green for the wrong reason. This task
exists to make that impossible to do by accident.

## Named test 3 — the user's wording, verbatim

> **With RLS active and no tenant context set, a direct query returns zero rows.**

That is this task's headline assertion and it must exist as its own test method with a name that
says so.

## What to write

Fixture: register the credentials for all three principals as test properties — Flyway's
owner-member user, `challenge_pipeline` for the primary pool, and `challenge_api` for the API pool
(TASK-008-09) — so the container runs under the same identity split as production. The roles
themselves are created by the `V5` migration, which Flyway runs inside the container, so no role
DDL belongs in the fixture.

`RowLevelSecurityIsolationTest`, `@SpringBootTest` with `TestcontainersConfiguration`, seeding
rows for two distinct clients (call them `CLIENT_A` and `CLIENT_B`) through the **primary/pipeline**
connection, then querying through the **API pool**:

| # | Scenario | Expected |
|---|---|---|
| 1 | **Guard, asserted before anything else:** the API pool's `current_user` is `challenge_api`, is not a table owner, is not a member of `challenge_owner`, has `rolbypassrls = false` and `rolsuper = false`; and the primary pool's `current_user` is `challenge_pipeline`, not the owner and not a member of `challenge_owner` | all true, else fail immediately with a message saying the rest of this suite would be meaningless |
| 2 | **No tenant context set**, a direct `SELECT * FROM deliveries` through the API pool inside a transaction | **zero rows** — named test 3 |
| 3 | Same, for `notification_events`, `subscriptions` and `delivery_attempts` | zero rows each |
| 4 | Tenant bound to `CLIENT_A` via `TenantSessionBinder`, then the same unfiltered `SELECT *` | only `CLIENT_A`'s rows; zero rows of `CLIENT_B`'s |
| 5 | Tenant bound to `CLIENT_A`, a query that deliberately asks for a `CLIENT_B` row **by primary key**, with **no `WHERE client_id`** | zero rows. This is the "forgot the predicate in the SQL string" hole that layer 4 exists to close |
| 6 | `delivery_attempts` for a `CLIENT_B` delivery, tenant bound to `CLIENT_A` | zero rows, proving the parent-join policy shape works |
| 7 | The same rows read through the **pipeline** connection with no tenant bound | **all rows visible** — the pipeline is cross-tenant by design (ADR-007 §5.2) and this asserts its explicit exemption survives `FORCE ROW LEVEL SECURITY` |
| 8 | The pipeline connection attempts `ALTER TABLE deliveries ADD COLUMN ...` inside a rolled-back transaction | **refused** — the pipeline holds DML and no DDL. This is the assertion that makes the owner/pipeline split real rather than declared |

Scenario 5 is the important one: write the query without a `client_id` predicate on purpose, and
comment it saying so, or a later reader will "fix" it.

## Out of Scope

- Anything HTTP, any token, any controller — TASK-008-28 owns the end-to-end 404.
- Any use case or port.
- Changing the policies or roles. If a scenario fails, report it; the fix belongs in TASK-008-07
  or TASK-008-08, not here.
- Adding a second container or a second database.

## Acceptance Criteria

**Every criterion below is `DEFERRED — Testcontainers`. None is to be satisfied in this phase.**

- [ ] Scenario 1 runs first and fails loudly, with an explanatory message, if the API pool is not
      subject to RLS.
- [ ] **Named test 3 exists as its own named method**: RLS active, no tenant context, direct
      query returns zero rows.
- [ ] All eight scenarios above are implemented, including scenario 5's deliberately
      predicate-less query, scenario 7's pipeline visibility check and scenario 8's DDL refusal.
- [ ] Rows are seeded through the pipeline connection, not through the API pool (which holds
      `SELECT` only).
- [ ] Testcontainers against real PostgreSQL. No H2, no mock, no embedded database.
- [ ] `TestcontainersConfiguration`'s existing beans and properties are unchanged apart from the
      added API-role properties.
- [ ] Every pre-existing test still passes.
- [ ] No new OWASP Top 10:2025 exposure introduced.

## Definition of Done

**In this phase: nothing to do.** Leave `status` at `Not Started`.

When the Tech Lead lifts the deferral: fixture and tests written and passing locally against
Testcontainers. **Do not run `git add` or `git commit`.** Set this task's `status` to
`Ready for Review` and stop.

## Status

Not Started <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
