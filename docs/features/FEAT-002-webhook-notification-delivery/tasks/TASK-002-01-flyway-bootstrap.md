---
id: TASK-002-01
feature: FEAT-002
title: Add Flyway to the build and wire the migration location
status: Ready for Review
agent: devops-engineer
depends_on: []
date: 2026-09-20
---

# TASK-002-01: Add Flyway to the build and wire the migration location

## Feature

FEAT-002 — Webhook notification delivery, persistence schema

## Assigned Agent

`devops-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

Rationale for the role: this task changes Gradle build configuration and Spring configuration and writes **zero SQL**. CLAUDE.md's roster assigns Gradle build config to `devops-engineer`. Every subsequent task in FEAT-002 is `dba`.

## Scope

Flyway is **not on the classpath today** — verified against the working tree: `build.gradle` lists `spring-boot-starter-data-jdbc` and `runtimeOnly 'org.postgresql:postgresql'` and no migration tool at all, and `src/main/resources` contains only `application.yaml` and `application-local.yaml`. Nothing in FEAT-002 can run until this is fixed, which is why it is task 01.

- File(s):
  - `build.gradle` (modified) — add the Flyway dependencies
  - `src/main/resources/application.yaml` (modified) — Flyway configuration block
  - `src/main/resources/db/migration/.gitkeep` (new) — so the directory exists in the working tree before TASK-002-02 writes into it
- Concern: making migrations runnable. Nothing else.

### Dependencies

Add both:

- `implementation 'org.flywaydb:flyway-core'`
- `implementation 'org.flywaydb:flyway-database-postgresql'`

The second is **not optional** on modern Flyway: the PostgreSQL support was split out of `flyway-core` into its own module, and `flyway-core` alone fails at runtime with "Unsupported Database: PostgreSQL". Do not pin versions — the Spring Boot dependency-management plugin (`io.spring.dependency-management`, already applied at `build.gradle:3`) supplies a version aligned with Spring Boot 4.1.1. Pinning here would silently diverge from the managed BOM.

Do not add `flyway-core` as `runtimeOnly`. Later tasks may need Flyway's test support and a `Flyway` bean on the classpath; `implementation` is the plain choice and there is no reason to be clever.

### Configuration in `application.yaml`

Keep it minimal. Spring Boot auto-configures Flyway from the existing datasource when `flyway-core` is on the classpath; only state what actually differs from the default or what must not drift:

- `spring.flyway.enabled: true` — stated explicitly rather than relied on, because it is the one switch whose silent flip would let the app boot against an unmigrated database.
- `spring.flyway.locations: classpath:db/migration` — this is also the default; state it so the later tasks have a named, agreed location rather than an implied one.
- `spring.flyway.baseline-on-migrate: false` — the database is empty and every object is created by a migration. `true` would let Flyway silently adopt a hand-created schema as a baseline, which is exactly the failure mode that makes "it works on my machine" schemas possible.

Do **not** set `clean-disabled: false`, do not enable `spring.flyway.clean-on-validation-error`, and do not add out-of-order migration support. Each of those trades a real safety property for convenience that nothing in this feature needs (YAGNI, and `clean` against a real database is destructive).

Do not touch `application-local.yaml`. The FEAT-001 keys in it (LocalStack endpoint, compressed timings) are unrelated and Flyway needs no per-profile override.

### Verification

Tests already boot a real PostgreSQL through `TestcontainersConfiguration` (`src/test/java/com/cobre/challenge/TestcontainersConfiguration.java`, `@ServiceConnection` on `PostgreSQLContainer`). With an empty `db/migration` directory, Flyway starting successfully against that container and reporting zero migrations is the correct outcome of this task — `./gradlew test` must still pass, and `ChallengeApplicationTests` in particular must still start the context.

An empty migration location is not an error in Flyway; if the build fails claiming it cannot find migrations, that is a misconfiguration of `locations`, not an expected state.

## Out of Scope

- **Any `.sql` file.** The directory is created empty. TASK-002-02 writes `V1`.
- Any schema, table, index, enum, or partition. No DDL in any form, including none embedded in `application.yaml`.
- `src/test/java/**` — no new test class, and `TestcontainersConfiguration` is not modified. It already provides the Postgres container this task is verified against.
- `compose.yaml` — the `postgres` service is already there and needs no change for Flyway.
- Spring Data JDBC configuration, `spring.sql.init.*`, or `schema.sql`/`data.sql`. Flyway is the only schema mechanism; do not leave a second one half-wired.
- `application-local.yaml`.
- Any Java code.

## Acceptance Criteria

- [ ] `build.gradle` declares both `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql` as `implementation`, with no explicit version (managed by the Spring Boot BOM)
- [ ] `application.yaml` sets `spring.flyway.enabled`, `spring.flyway.locations`, and `spring.flyway.baseline-on-migrate: false`, and sets nothing else under `spring.flyway`
- [ ] `src/main/resources/db/migration/` exists in the working tree
- [ ] `./gradlew build` succeeds
- [ ] `./gradlew test` succeeds; the Spring context still starts against the Testcontainers PostgreSQL instance and the startup log shows Flyway running and finding zero migrations
- [ ] `clean` is not enabled, out-of-order migration is not enabled, and no `schema.sql`/`data.sql`/`spring.sql.init.*` mechanism is introduced alongside Flyway
- [ ] `application-local.yaml`, `compose.yaml` and everything under `src/test/java` are unmodified (`git status` shows only the three files named in Scope)
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable). Specifically **A03 Software Supply Chain**: two new dependencies enter the build here; both come from the Spring Boot BOM's managed versions rather than a hand-picked one, and no new repository is added to `repositories {}`

## Definition of Done

Build and configuration changed, `./gradlew build` and `./gradlew test` passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

## Completion note (devops-engineer)

**Dependency deviation from the initial pin, flagged explicitly:** the task specified only `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql` as `implementation`. With just those two, the app booted and Postgres connected fine, but Flyway never ran: no Flyway banner, no schema-history log line, nothing. Verified by hand: `unzip -l` on `spring-boot-autoconfigure-4.1.1.jar` and `spring-boot-sql-4.1.1.jar` shows **no Flyway autoconfiguration class in either jar**. Spring Boot 4 split Flyway's autoconfiguration out of the monolithic autoconfigure module into its own artifact, `org.springframework.boot:spring-boot-starter-flyway` (confirmed present in `spring-boot-dependencies-4.1.1.pom`'s managed BOM, version-aligned, no explicit version needed), following the same per-feature-starter pattern this project already uses for `spring-boot-starter-data-jdbc`, `spring-boot-starter-webmvc`, etc. This is not something the task file could have anticipated (Flyway used to autoconfigure from `flyway-core` alone on Spring Boot 3.x and earlier). Added `implementation 'org.springframework.boot:spring-boot-starter-flyway'` alongside the two originally specified dependencies (both kept, unpinned, exactly as specified) to restore autoconfiguration. This keeps the "supplied by the Spring Boot BOM, not hand-picked" and "no new repository added" A03 supply-chain properties intact by construction. Flagging for architect/backend-engineer awareness: any future Spring Boot 4 dependency reasoned about by analogy to Boot 3.x autoconfiguration behavior should be verified against the actual autoconfigure jar contents first, since Boot 4 modularized several features (Flyway included) into their own starters.

**Verified locally:**
- `./gradlew build` succeeds (compiles, tests, assembles).
- `./gradlew test` succeeds; `ChallengeApplicationTests` boots the Spring context against the Testcontainers PostgreSQL instance (`TestcontainersConfiguration`, unmodified).
- Flyway startup log confirmed in `build/test-results/test/TEST-com.cobre.challenge.ChallengeApplicationTests.xml`:
  ```
  org.flywaydb.core.FlywayExecutor      : Database: jdbc:postgresql://localhost:<port>/test (PostgreSQL 18.6)
  o.f.c.i.s.JdbcTableSchemaHistory      : Schema history table "public"."flyway_schema_history" does not exist yet
  o.f.core.internal.command.DbValidate  : Successfully validated 0 migrations (execution time 00:00.003s)
  o.f.c.i.s.JdbcTableSchemaHistory      : Creating Schema History table "public"."flyway_schema_history" ...
  o.f.core.internal.command.DbMigrate   : Current version of schema "public": << Empty Schema >>
  o.f.core.internal.command.DbMigrate   : Schema "public" is up to date. No migration necessary.
  ```
  Zero migrations found is the expected outcome with an empty `db/migration` directory; this is not an error.
- `git diff --stat build.gradle src/main/resources/application.yaml` shows only the intended additions; `application-local.yaml`, `compose.yaml`, and `src/test/java/**` are unmodified.
- `clean` was not enabled, out-of-order migration was not enabled, and no `schema.sql`/`data.sql`/`spring.sql.init.*` mechanism was introduced.
- `src/main/resources/db/migration/.gitkeep` exists; directory is empty of any `.sql` file.

Verification commands used:
```
./gradlew build
./gradlew test
grep -i flyway build/test-results/test/TEST-com.cobre.challenge.ChallengeApplicationTests.xml
git status --short
git diff build.gradle src/main/resources/application.yaml
```
