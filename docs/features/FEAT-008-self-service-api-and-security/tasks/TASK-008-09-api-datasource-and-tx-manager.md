---
id: TASK-008-09
feature: FEAT-008
title: API connection pool, and the credentials each pool and Flyway connect with
status: Ready for Review
agent: dba
depends_on: [TASK-008-07]
date: 2026-09-21
---

# TASK-008-09: API Connection Pool and Transaction Manager

## Feature

FEAT-008

## Assigned Agent

`dba` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/config/ApiDataSourceConfig.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/out/persistence/config/ApiDataSourceProperties.java` (new)
  - `src/main/resources/application.yaml` (modified — the three principals, see below)
  - `src/test/java/com/cobre/challenge/adapter/out/persistence/config/ApiDataSourceConfigTest.java`
    (**deferred this phase** — specified below, not written now)
- Concern: connection identity. A second pool connecting as `challenge_api`, and the primary pool
  and Flyway each connecting as the right one of the other two roles. This is the "two connection
  pools" of ADR-007 §5.4 plus the credential half the three-role model of TASK-008-07 requires.

## Which principal connects where (Tech Lead review of 2026-09-21)

`V5` splits ownership from the pipeline's RLS exemption. That split only means anything once the
runtime connections stop being the owner, which is this task:

| Connection | Connects as | Why |
|---|---|---|
| Flyway (`spring.flyway.user`) | the migration user, a **member of `challenge_owner`** | DDL. Runs at startup, never serves a request |
| Primary `DataSource` (pipeline: ingest, relay, worker, DLQ consumer) | `challenge_pipeline` | `BYPASSRLS`, DML only, **no DDL** |
| `apiDataSource` (the three client endpoints) | `challenge_api` | `NOBYPASSRLS`, `SELECT` only, subject to every policy |

Give Flyway its **own** `spring.flyway.user`/`password` rather than letting it inherit the primary
`DataSource`'s: that inheritance is precisely what made the runtime pipeline connection the table
owner. Keep the URL shared.

This is the one place FEAT-008 changes the pipeline's runtime configuration. It changes no pipeline
code and no pipeline SQL — only the principal the existing primary pool logs in as. If a merged
pipeline test fails on a missing privilege once the suite runs, the fix is `V5`'s grant list
(TASK-008-07), never the test and never a wider role.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**
Everything this task can prove needs a live database or a live context, so its tests are
**DEFERRED — Testcontainers**. Write the configuration, verify with `./gradlew compileJava
compileTestJava`, and report the deferred tests as specified and pending.

A unit test of `ApiDataSourceProperties`' binding defaults, in the style of the merged
`WorkerPropertiesTest` / `RelayPropertiesTest`, **is** writable now and is worth writing.

## What to build

Three beans, all qualified so nothing resolves them by accident:

| Bean | Notes |
|---|---|
| `apiDataSource` | a `DataSource` pointing at the **same** database URL as the primary, connecting as `challenge_api`. **Never `@Primary`.** Small pool — this path serves three read endpoints |
| `apiJdbcTemplate` | a `NamedParameterJdbcTemplate` over `apiDataSource`, qualified, injected by name into the tenant-scoped adapters (TASK-008-15, TASK-008-16) |
| `apiTransactionManager` | a `DataSourceTransactionManager` over `apiDataSource`, qualified. The client-API use cases name it explicitly in `@Transactional` (TASK-008-17, -18, -19) |

`ApiDataSourceProperties` is `@ConfigurationProperties("challenge.security.api-datasource")`
carrying the role's username and password. The **URL is derived from the primary `DataSource`**,
not configured separately: two URLs is a way to point the API pool at a different database and
never notice. State how you derived it in the handover.

**The primary `DataSource`, its template and its transaction manager stay exactly as they are and
stay `@Primary`.** Every merged pipeline adapter resolves them by type today and must keep
working untouched. Adding a second `DataSource` without `@Primary` on the first is the classic way
to break an entire Spring Boot context; verify the existing tests still pass.

## The startup assertion ADR-007's Consequences section asks for

ADR-007 lists "the API path accidentally wired to the pipeline pool silently disables RLS" as the
new way to be wrong, and asks for a startup assertion. Add it here, in this config: on
initialization, open a connection from `apiDataSource` and fail the context if any of these holds
for `current_user`:

- `rolbypassrls` or `rolsuper` is true (`SELECT rolbypassrls, rolsuper FROM pg_roles WHERE rolname
  = current_user`);
- it owns any of the four tenant tables, or is a member of the owning role
  (`pg_has_role(current_user, 'challenge_owner', 'member')`).

Fail loudly at startup, not at first request (A02/A10). The ownership half matters as much as the
`BYPASSRLS` half: with `FORCE ROW LEVEL SECURITY` set (TASK-008-08) an owner is no longer exempt,
but membership in the owning role still carries DDL, and an API pool holding DDL is a finding
whether or not RLS applies to it.

Keep it to a `@PostConstruct`-style check or an `InitializingBean` on this configuration class —
do not introduce a separate validator class (TASK-008-22's validator is about JWT configuration
and must not grow a database concern).

## Out of Scope

- `SET LOCAL` / the session binder — TASK-008-10.
- Changing any existing adapter to use the new template — TASK-008-15, TASK-008-16.
- Any change to a pipeline adapter, its SQL or its bean wiring. This task changes the primary
  pool's **credentials only**.
- Any change to `TestcontainersConfiguration` — TASK-008-11 owns the test-side role wiring.
- Any second database, any read replica.

## Acceptance Criteria

- [ ] `apiDataSource`, `apiJdbcTemplate` and `apiTransactionManager` exist, are qualified, and
      none is `@Primary`.
- [ ] The primary `DataSource`, `JdbcTemplate`/`NamedParameterJdbcTemplate` and transaction
      manager remain `@Primary` and unchanged.
- [ ] The API pool's URL is derived from the primary `DataSource`, not separately configured.
- [ ] Credentials come from configuration properties; no password literal in Java.
- [ ] The startup assertion is written: the context fails when the API pool's role has
      `BYPASSRLS`, is a superuser, owns a tenant table, or is a member of `challenge_owner`.
- [ ] Flyway has its own user/password property, a member of `challenge_owner`, not inherited from
      the primary `DataSource`.
- [ ] The primary `DataSource` connects as `challenge_pipeline`, which is not the table owner.
- [ ] No pipeline adapter, SQL statement or bean definition is modified.
- [ ] Pool size is explicitly set and modest, with a one-line comment saying why.
- [ ] A plain-JUnit properties-binding test exists for `ApiDataSourceProperties` (defaults and
      required values), needing no container.
- [ ] **DEFERRED — Testcontainers:** `ApiDataSourceConfigTest` proves the API pool connects as
      `challenge_api` (`SELECT current_user`) against a real Postgres container.
- [ ] **DEFERRED — Testcontainers:** the startup assertion is proven by wiring a bypassing role
      and expecting a startup failure.
- [ ] **DEFERRED — Testcontainers:** every pre-existing test passes unchanged with the second
      pool present.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A01/A02).

## Definition of Done

Configuration written, properties unit test passing, deferred tests specified but not written.
**Do not run `./gradlew test` or `./gradlew build`** — verify with `./gradlew compileJava
compileTestJava`. **Do not run `git add` or `git commit`.** Set this task's `status` to
`Ready for Review` and stop.

## Status

Not Started <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
