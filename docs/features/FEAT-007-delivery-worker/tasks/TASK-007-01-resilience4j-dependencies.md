---
id: TASK-007-01
feature: FEAT-007
title: Add pinned Resilience4j bulkhead and circuit-breaker dependencies
status: Ready for Review
agent: devops-engineer
depends_on: []
date: 2026-09-21
---

# TASK-007-01: Resilience4j dependencies

## Feature

FEAT-007

## Assigned Agent

`devops-engineer` — this task is only for this agent. If it needs work from another role, that's
a separate task, not scope creep on this one.

## Scope

- File(s):
  - `build.gradle` (modified)
- Concern: make the two Resilience4j primitives ADR-006 §1.2 and §1.3 name available on the
  runtime classpath, and nothing else.

### What to add

```
implementation 'io.github.resilience4j:resilience4j-bulkhead:<exact version>'
implementation 'io.github.resilience4j:resilience4j-circuitbreaker:<exact version>'
```

Pick one version, apply it to both artifacts, and pin it literally — no version range, no
`+`, no reliance on a BOM this project does not import. Choose the latest release compatible
with Java 21 and verify it resolves.

**Do not add `resilience4j-spring-boot3` / `-spring-boot2` / `-all` / `-micrometer`.** FEAT-007
uses the programmatic `BulkheadRegistry` and `CircuitBreakerRegistry` only, behind this project's
own `port/out` interfaces (TASK-007-10, TASK-007-11). A Spring Boot starter would pull
annotation-driven AOP, its own auto-configuration and a Boot-version coupling this design does not
use and cannot currently verify against Spring Boot 4.1.1.

`resilience4j-circuitbreaker` transitively brings `resilience4j-core` and `vavr`; that is expected.
Report the resolved transitive set in your handover so TASK-007-19 can review it (A03).

## Out of Scope

- Any Java file. The registries and adapters are TASK-007-10 and TASK-007-11.
- Any other dependency change, including the open `software.amazon.awssdk:sqs` supply-chain
  review already logged in `docs/concerns.md`.
- Dependency-scanning or CI configuration.

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker. This task adds no test. **Do not run `./gradlew test` or `./gradlew build`** — the Tech
Lead triggers the suite explicitly once every FEAT-007 task is complete.

## Acceptance Criteria

- [ ] Both artifacts are declared as `implementation` with one identical, exact pinned version.
- [ ] No Resilience4j Spring Boot starter, `-all`, or `-micrometer` artifact is added.
- [ ] `./gradlew dependencies --configuration runtimeClasspath` resolves (dependency resolution
      only — not a build, not a test run) and the resolved Resilience4j tree is recorded in the
      handover note.
- [ ] No other dependency is added, removed, or version-changed.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced beyond the A03 surface this task exists to
      create, which is flagged to `security-engineer` in TASK-007-19.

## Definition of Done

Change written. **Do not run `git add` or `git commit`.** Set this task's `status` to
`Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
