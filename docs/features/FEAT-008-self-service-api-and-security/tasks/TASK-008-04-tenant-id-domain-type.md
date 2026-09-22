---
id: TASK-008-04
feature: FEAT-008
title: TenantId domain type
status: Ready for Review
agent: backend-engineer
depends_on: []
date: 2026-09-21
---

# TASK-008-04: `TenantId` Domain Type

## Feature

FEAT-008

## Assigned Agent

`backend-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/domain/model/tenant/TenantId.java` (new)
  - `src/test/java/com/cobre/challenge/domain/model/tenant/TenantIdTest.java` (new)
- Concern: one value type, its format rule, and its tests. Nothing consumes it in this task.

## What it is (ADR-007 §5.1)

A plain Java record, **zero framework imports**, holding the `client_id` claim. It is not a
`String`, precisely so it cannot be confused at a call site with a delivery id, an event id or a
subscription id.

Format, validated in the compact constructor (ADR-007 §3): **1 to 64 characters, `[A-Za-z0-9_-]`
only.** Matches the `CLIENT001` shape of `docs/challenge/notification_events.json`. Null, blank,
over-length or out-of-alphabet input throws — this value reaches both a bound SQL parameter and a
database session-configuration call (TASK-008-10), and the format check is the second layer behind
binding (A05).

Keep it minimal: the record component and validation. A single accessor is enough; do not add
convenience factories, no `of(String)` alongside the canonical constructor, no `toString`
override that hides the value, no `Comparable`, no serialization annotations (YAGNI, Effective
Java Item 17 — immutable and small).

**Package placement:** `domain/model/tenant` follows the repo's package-by-feature-then-kind
convention alongside `domain/model/delivery`, `.event` and `.subscription`. The tenant is not a
delivery concern and must not be filed under one.

## Out of Scope

- Anything that constructs a `TenantId` — TASK-008-05 owns the one production factory.
- Any port signature change — TASK-008-12, -13, -14.
- The ArchUnit rule restricting construction — TASK-008-06.
- Any Spring, Jackson or Bean Validation annotation on this type.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**
Everything this task needs is unit-testable, so nothing here is deferred. Verify with
`./gradlew compileJava compileTestJava` plus this task's own unit tests; **do not run
`./gradlew test` or `./gradlew build`.**

## Acceptance Criteria

- [ ] `TenantId` is a record in `domain/model/tenant` with no import from `org.springframework.*`,
      `jakarta.*` or `com.fasterxml.*`.
- [ ] Valid values accepted: `CLIENT001`, a 1-character value, a 64-character value, values
      containing `_` and `-`.
- [ ] Rejected with a clear exception: `null`, empty, blank/whitespace, 65 characters, and values
      containing any of `'`, `;`, a space, `%`, `"`, a newline, or a non-ASCII letter.
- [ ] Two instances with the same value are equal and have the same hash code (record default,
      asserted so a later hand-written override cannot silently break map keys).
- [ ] Unit tests only: plain JUnit, no Spring context.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A05: the format rule is this type's job).

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
