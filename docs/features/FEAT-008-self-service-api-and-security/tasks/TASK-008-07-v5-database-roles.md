---
id: TASK-008-07
feature: FEAT-008
title: V5 migration — challenge_owner, challenge_pipeline and challenge_api roles
status: Ready for Review
agent: dba
depends_on: []
date: 2026-09-21
---

# TASK-008-07: `V5` — Database Roles (three roles)

## Feature

FEAT-008

## Assigned Agent

`dba` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/resources/db/migration/V5__database_roles.sql` (new)
  - `src/test/java/com/cobre/challenge/schema/V5RoleMigrationTextTest.java` (**writable now** —
    plain JUnit over the migration text, no database; specified below)
  - `src/test/java/com/cobre/challenge/schema/ApiRoleGrantsTest.java` (**deferred this phase** —
    specified below, not written now)
- Concern: the role model of ADR-007 §5.4 and its grants. **No policy is created here** —
  TASK-008-08 owns RLS.

## Three roles, not two (Tech Lead review of 2026-09-21)

The earlier two-role shape made the pipeline principal the **owner** of the four tenant tables and
relied on owner-exemption (`FORCE ROW LEVEL SECURITY` left off) as the pipeline's escape from the
policies. That is rejected, for the reason the Tech Lead gave and for one ADR-007 already states:

- **The escape was implicit and over-broad.** A role that escapes RLS *by being the owner* also
  holds `ALTER`, `DROP` and `TRUNCATE` on those tables. The privilege actually wanted (read across
  tenants) and the privilege actually granted (own the schema) were not the same privilege, and
  nothing in the catalog said so.
- **ADR-007 §5.4 forbids exactly that combination**: "`FORCE ROW LEVEL SECURITY` on the tables is
  required if the owner and the pipeline role are ever the same principal." The two-role shape had
  owner == pipeline *and* `FORCE` off, which is the one pairing the ADR rules out. No amendment is
  needed to fix it; the three-role model is what the ADR sentence already asks for.

Ownership and RLS-exemption are therefore separated into three roles, each holding one thing.

## What the migration creates

| Role | Properties |
|---|---|
| `challenge_owner` | **`NOLOGIN` group role.** Owner of the schema and of the four tenant tables (plus their indexes, sequences and enum types). Never used as a runtime connection principal. Flyway reaches it by membership, not by logging in as it — see below |
| `challenge_pipeline` | `LOGIN`. **`BYPASSRLS`, granted explicitly.** **Not** the owner and **not** a member of `challenge_owner`, so it holds **no DDL** on any table. `USAGE` on the schema and `SELECT, INSERT, UPDATE` on the four tenant tables — the DML the ingest, relay, worker and DLQ consumer actually issue. No `DELETE`, no `TRUNCATE` (nothing in ADR-002 deletes a delivery) |
| `challenge_api` | `LOGIN`. **Not** the owner, **not** a member of `challenge_owner`, **`NOBYPASSRLS`** stated explicitly, **not** a superuser. `USAGE` on the schema. `SELECT` — and only `SELECT` — on `notification_events`, `deliveries`, `subscriptions`, `delivery_attempts` |

**Ownership transfer.** `V1`–`V4` created the four tables under whatever principal Flyway ran as.
`V5` must `ALTER ... OWNER TO challenge_owner` for the schema, the four tables, their sequences and
the enum types, then `GRANT challenge_owner TO <the Flyway/migration user>` so `V7` and every later
migration still has DDL. This is the only way the runtime pipeline connection stops being the owner
without giving Flyway a brand-new identity.

**`challenge_pipeline` escapes the policies explicitly, by `BYPASSRLS`.** It is visible in
`pg_roles`, it is one grant to revoke, and it carries no DDL with it. Two operational notes belong
in the handover:

- `CREATE ROLE ... BYPASSRLS` and `ALTER ROLE ... BYPASSRLS` require a **superuser**. The
  Testcontainers and compose Postgres run as superuser so the migration applies cleanly here. On a
  managed instance where `BYPASSRLS` cannot be granted, the **documented fallback** is an explicit
  permissive policy `TO challenge_pipeline USING (true)` on each table (TASK-008-08 owns the policy
  file). Both forms are explicit and both are asserted by a test; pick `BYPASSRLS` where it is
  available and say in the handover which form the migration uses.
- Nothing in `V5` may widen what any role holds beyond the table above. In particular
  `challenge_api` must never appear on the left of a `GRANT` of anything but `SELECT` and `USAGE`.

**`challenge_api` gets `SELECT` only, deliberately.** The replay write goes through
`DeliveryPipelineRepositoryPort` and therefore the pipeline pool (FEAT-008 feature.md, mechanism
choice 2), so the API pool needs no `INSERT`, `UPDATE` or `DELETE` anywhere.

No password may be a literal in the migration. Use a placeholder resolved by Flyway configuration
(`${...}`) or create the login roles without a password and set it from the environment; state
which you chose and why in the handover. A committed credential is an A04/A03 finding even in a
dev-only file.

Add `COMMENT ON ROLE` (or a header comment, if the Postgres version in use disallows it) for all
three, stating what each is for, that `challenge_api` must never gain `BYPASSRLS` or table
ownership, and that `challenge_pipeline` must never gain DDL or `challenge_owner` membership.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**

The Tech Lead asked for a test that an accidental change cannot silently break the isolation. That
check splits in two, and the split is worth stating plainly: **the authoritative check is a catalog
query and therefore needs a real Postgres**, because `rolbypassrls`, `pg_class.relowner` and
`has_table_privilege` exist only in a real server. There is no honest unit-test substitute for it,
and an H2 imitation would go green while proving nothing.

What **is** writable now, and is required, is a *text* guard over the migration itself. It catches
the realistic accident — someone editing `V5` — in this phase, at zero cost, with no Docker.

### `V5RoleMigrationTextTest` — write this now

Plain JUnit. Read `db/migration/V5__database_roles.sql` from the test classpath and assert, on the
SQL text with comments stripped:

- The statement creating `challenge_api` contains `NOBYPASSRLS` and does **not** contain
  `BYPASSRLS` as a granted attribute, `SUPERUSER`, or `CREATEROLE`.
- `challenge_api` never appears as the target of an `ALTER ... OWNER TO`, and never appears in a
  `GRANT challenge_owner TO ...`.
- Every `GRANT ... TO challenge_api` grants only `SELECT` or `USAGE` — no `INSERT`, `UPDATE`,
  `DELETE`, `TRUNCATE`, `REFERENCES` or `ALL`.
- `challenge_pipeline` is granted its RLS exemption explicitly (either `BYPASSRLS` here, or the
  fallback policy in `V6` — assert whichever form the migration uses, and assert the other is
  absent so the two cannot both drift in).
- `challenge_pipeline` never appears in a `GRANT challenge_owner TO ...`.

One line of javadoc, per repo convention, saying this is a text guard and that
`ApiRoleGrantsTest` is the authoritative version.

## The catalog test — DEFERRED, Testcontainers, implement only when the Tech Lead requests it

`ApiRoleGrantsTest` will be a Testcontainers schema test, in the style of the existing
`src/test/java/com/cobre/challenge/schema/*` tests. It queries the catalog and asserts:

- All three roles exist. `challenge_owner` has `rolcanlogin = false`.
- `challenge_api`: `LOGIN`, `rolbypassrls` **false**, `rolsuper` **false**.
- `challenge_api` is **not** the owner of any of the four tenant tables (`pg_class.relowner`), and
  is **not** a member of `challenge_owner` (`pg_auth_members`).
- `challenge_api` has `SELECT` on all four tenant tables and has **no** `INSERT`, `UPDATE`,
  `DELETE` or `TRUNCATE` on any of them (`has_table_privilege`).
- `challenge_pipeline`: `rolbypassrls` **true** (or, under the fallback form, a permissive policy
  `TO challenge_pipeline` exists), `rolsuper` **false**, **not** a member of `challenge_owner`, and
  holds **no** DDL — `has_table_privilege(..., 'TRUNCATE')` is false and it is not the owner of any
  of the four tables.
- `challenge_owner` is the owner of all four tenant tables.

These assertions are the ones that make TASK-008-08's policies mean anything. A role with
`BYPASSRLS` on the API side, or an owner-exempt runtime role, turns every policy in this feature
into decoration.

## Out of Scope

- Enabling RLS or creating any policy — TASK-008-08, which also owns `FORCE ROW LEVEL SECURITY`
  and the fallback pipeline policy.
- Which credentials each pool and Flyway actually use — TASK-008-09.
- Any table, column, index or constraint change. There is **no** DDL on the four tables here
  beyond `GRANT` and `ALTER ... OWNER TO`.

## Acceptance Criteria

- [ ] `V5` creates all three roles and is idempotent enough to run on a fresh database (Flyway runs
      it exactly once; do not add `IF NOT EXISTS` gymnastics beyond what a clean run needs).
- [ ] `challenge_owner` is `NOLOGIN`, owns the schema and the four tenant tables, and the
      Flyway/migration user is granted membership in it so later migrations still have DDL.
- [ ] `challenge_pipeline`: `LOGIN`, RLS exemption granted **explicitly** (`BYPASSRLS`, or the
      documented `V6` fallback policy), not the owner, not a member of `challenge_owner`, and
      holding only `SELECT, INSERT, UPDATE` plus schema `USAGE`.
- [ ] `challenge_api`: `LOGIN`, `NOBYPASSRLS` stated explicitly, no `SUPERUSER`, not a table owner,
      not a member of `challenge_owner`.
- [ ] `challenge_api` holds `SELECT` and nothing else on the four tenant tables, plus schema
      `USAGE`.
- [ ] No password literal is committed.
- [ ] Role comments state the constraint on `challenge_api` and on `challenge_pipeline`.
- [ ] `V5RoleMigrationTextTest` is written and passing (plain JUnit, no container).
- [ ] No new OWASP Top 10:2025 exposure introduced (A01: this is the role half of the runtime
      layer).
- [ ] **DEFERRED — Testcontainers:** `ApiRoleGrantsTest` asserts every catalog property above
      against a real Postgres container and fails if any is violated.
- [ ] **DEFERRED — Testcontainers:** every merged pipeline test still passes unchanged with `V5`
      applied. If one breaks on a missing privilege, the fix is the grant list here, never the test.

## Definition of Done

Migration written and compiling, `V5RoleMigrationTextTest` written and passing. The Testcontainers
test is specified, not written. **Do not run `./gradlew test` or `./gradlew build`** — verify with
`./gradlew compileJava compileTestJava` plus this task's own unit test, and report the deferred
test as specified and pending the Tech Lead's later explicit run. **Do not run `git add` or
`git commit`.** Set this task's `status` to `Ready for Review` and stop.

## Status

Not Started <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
