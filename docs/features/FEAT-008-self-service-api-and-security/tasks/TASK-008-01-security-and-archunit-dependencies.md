---
id: TASK-008-01
feature: FEAT-008
title: OAuth2 resource server and ArchUnit dependencies
status: Ready for Review
agent: devops-engineer
depends_on: []
date: 2026-09-21
---

# TASK-008-01: OAuth2 Resource Server and ArchUnit Dependencies

## Feature

FEAT-008

## Assigned Agent

`devops-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `build.gradle` (modified)
- Concern: the two dependencies FEAT-008 needs, and nothing else.

Add exactly two entries:

| Entry | Configuration | Why |
|---|---|---|
| `org.springframework.boot:spring-boot-starter-oauth2-resource-server` | `implementation` | ADR-007 T-B: `oauth2ResourceServer().jwt()` and the `JwtDecoder` it brings. Version comes from the Boot BOM — **do not pin a version string** |
| ArchUnit (JUnit 5 flavour, e.g. `com.tngtech.archunit:archunit-junit5`) | `testImplementation` | ADR-007 §5.1's three structural rules, which must fail the build rather than a review. **Pin an explicit version** — it is not in the Boot BOM (A03) |

## Out of Scope

- **No token-bucket library.** ADR-007 §6 leaves the choice open and FEAT-008 takes the
  hand-rolled option (TASK-008-24). Adding Bucket4j or an equivalent is a defect here.
- No secrets-scanning plugin. None is configured in this repository today; ADR-007 §7's
  allow-list note has no scanner to configure, and inventing one is out of scope.
- No dependency-lock, no version-catalog migration, no unrelated upgrades.
- No configuration keys — TASK-008-03 owns `application*.yaml`.

## Acceptance Criteria

- [ ] `spring-boot-starter-oauth2-resource-server` is on `implementation` with **no** version.
- [ ] ArchUnit is on `testImplementation` with an explicit pinned version.
- [ ] No other dependency is added, removed, or has its version changed.
- [ ] `./gradlew compileJava compileTestJava` succeeds.
- [ ] Spring Security's auto-configuration behavior is unchanged by this task alone: adding the
      resource-server starter without any `issuer-uri` or `public-key-location` property must not
      break the existing context. If it does, say so in the handover rather than adding a
      property — TASK-008-03 owns that.
- [ ] No new OWASP Top 10:2025 exposure introduced.

## Definition of Done

Change made, `compileJava compileTestJava` green. **Do not run `git add` or `git commit`.** Set
this task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
