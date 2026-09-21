---
id: TASK-001-08
feature: FEAT-001
title: Minimal local SecurityConfig permitting the webhook stub under the local profile
status: Ready for Review
agent: security-engineer
depends_on: [TASK-001-06]
date: 2026-09-20
---

# TASK-001-08: Minimal local SecurityConfig permitting the webhook stub under the local profile

## Feature

FEAT-001 — Local development environment

## Assigned Agent

`security-engineer` — this task is only for this agent.

## Context

No `SecurityConfig` class exists yet in the codebase (ADR-007's full security config is separate, later work). Spring Security's default auto-configuration is therefore active: every endpoint, including the `local`-profile-only stub webhook receiver (`adapter/in/web/local/webhookstub/`), returns 401 and requires the random generated-password basic auth Spring Boot prints once at startup. This blocks manual testing/demo of the stub (verified live: `curl -X POST /local/webhook-stub/receive` -> 401).

TASK-001-03 already anticipated this and explicitly flagged it as out of its own scope, deferring the fix to whoever owns Spring Security:

> "the stub's endpoints will need explicit `permitAll` (still gated by profile) once ADR-007's `SecurityConfig` lands"

This task is that minimal fix, scoped to unblocking local testing only — not the full ADR-007 security implementation.

## Scope

A `SecurityConfig` (or profile-scoped `SecurityFilterChain` bean) that:
- Under the `local` profile only, permits all requests to `/local/webhook-stub/**` without authentication.
- Does NOT weaken security for any other profile or any other path. Under `default`/`prod`/any non-`local` profile, current behavior (default Spring Security auto-config, or nothing defined yet if that's cleaner) is unchanged.
- Does NOT implement ADR-007's full security design (that's separate, larger work) — this is the minimal local-only permit needed to unblock the stub.

- File(s): your call on exact file/class name and package (e.g. `adapter/in/web/config/LocalSecurityConfig.java` or similar, `@Profile("local")` on the config class itself, consistent with how the stub controller is gated) — keep it to 1-2 files, one concern.
- A test asserting: under `local` profile, `POST /local/webhook-stub/receive` (and the other stub endpoints) do not require authentication (200/204, not 401/403).

## Out of Scope

- Any production/ADR-007 security design (authn/authz for the real webhook ingest/delivery API, tenant isolation, HMAC signature verification, etc.) — that is separate, future work.
- Any change to the stub controller/recorder classes themselves (TASK-001-03/04/05/06's territory).
- Any change outside a minimal, profile-scoped permit for the stub's own paths.

## Acceptance Criteria

- [ ] Under `local` profile, all `/local/webhook-stub/**` endpoints are reachable without authentication
- [ ] Under any other profile, behavior is unchanged from today (no new exposure)
- [ ] A test proves the above (both the permit under `local`, and no accidental global permit-all)
- [ ] `./gradlew test` passes
- [ ] No OWASP Top 10:2025 exposure introduced beyond what TASK-001-03 already accepted (the `@Profile("local")` gate remains the actual control against this ever reaching a non-local environment)

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
