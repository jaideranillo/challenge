# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

Spring Boot 4.1.1 skeleton (`com.cobre:challenge`), Java 21, Gradle. No business logic, controllers, or entities yet — only the generated `ChallengeApplication` main class. Package root: `com.cobre.challenge`.

## Commands

- Build: `./gradlew build`
- Run app (needs Docker for dev services, see below): `./gradlew bootRun`
- Run all tests: `./gradlew test`
- Run a single test class: `./gradlew test --tests "com.cobre.challenge.ChallengeApplicationTests"`
- Run a single test method: `./gradlew test --tests "com.cobre.challenge.ChallengeApplicationTests.methodName"`

## Architecture

- Standard single-module Gradle/Spring Boot layout: `src/main/java`, `src/main/resources/application.yaml`, `src/test/java`.
- Dependencies wired but unused so far: Spring Web MVC, Spring Data JDBC, Spring Security, Bean Validation, Actuator, OpenTelemetry (Micrometer tracing), PostgreSQL driver.
- **Dev services via Docker Compose** (`compose.yaml`, auto-started by `spring-boot-docker-compose` on `bootRun`): `postgres:latest` (db `mydatabase`, user `myuser`, pass `secret`) and `grafana/otel-lgtm:latest` (Grafana/Loki/Tempo/Mimir stack for traces/metrics/logs, ports 3000/4317/4318).
- **Tests use Testcontainers**, not the compose file: `TestcontainersConfiguration` (`src/test/java`) declares `@ServiceConnection` beans for `PostgreSQLContainer` and `LgtmStackContainer`, auto-wiring datasource/OTel config for any `@SpringBootTest`. `TestChallengeApplication` boots the app locally with these containers instead of compose (useful for local dev without editing `application.yaml`).
- Requires Docker running for both `bootRun` (compose) and tests that touch Testcontainers.

## Architecture direction (target, not yet implemented)

- **Hexagonal (Ports & Adapters)**: `domain/model` (framework-free) ← `application/port` + `application/usecase` ← `adapter/in/web` (controllers) and `adapter/out/persistence` (Spring Data JDBC / `NamedParameterJdbcTemplate`).
- **Blocking Spring MVC on virtual threads** (`spring.threads.virtual.enabled=true`) — no WebFlux, no reactive types anywhere.
- **Spring Data JDBC**, not JPA/Hibernate. No lazy loading, explicit SQL per repository call.

## Subagents (`.claude/agents/`)

- `software-architect` (Atlas) — feature design, port contracts, ADRs, hexagonal governance.
- `backend-engineer` (Forge) — domain/use case/adapter implementation.
- `dba` (Vault) — schema, migrations, Spring Data JDBC persistence adapters, query optimization.
- `security-engineer` (Sentinel) — Spring Security, authn/authz, injection review, secrets hygiene.
- `devops-engineer` (Harbor) — Gradle build, Docker Compose dev services, observability wiring.

## Project knowledge base

Context/decisions/errors persist to QMD, collection `challenge` (`.claude/context/topics/`), via the `/recall`, `/save-context`, `/sync-context`, `/topic`, `/start` commands in `.claude/commands/`. Don't duplicate this into any other memory system.

## Delivery workflow (mandatory — full detail in `docs/README.md`)

All planning artifacts live under `docs/` (ADRs, RFCs, features, tasks) — never only in chat.

1. `software-architect` (Atlas) generates an ADR at `docs/architecture/adr/ADR-NNN-slug.md`, status `Proposed`.
2. **The user reviews it and sets `Status` to `Accepted` or `Rejected` themselves** — no agent self-approves.
3. Once `Accepted`, Atlas generates `docs/features/FEAT-NNN-slug/feature.md` plus one `tasks/TASK-NNN-XX-slug.md` per unit of work, each assigned to exactly one implementing agent (`backend-engineer`, `dba`, `security-engineer`, `devops-engineer`), in dependency order.
4. Implementing agents work only from their assigned task file, **never run `git add`/`git commit`**, and mark their task `Ready for Review` when done. The user reviews the working tree and commits manually.

Task sizing is a hard constraint: at most ~3 files / one concern per task, reviewable by one person in one sitting. If it doesn't fit, it's split into more tasks, not one large one.
