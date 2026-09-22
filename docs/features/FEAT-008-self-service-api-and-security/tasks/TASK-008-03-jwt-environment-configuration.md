---
id: TASK-008-03
feature: FEAT-008
title: Per-environment JWT configuration — issuer, audience, local public key
status: Ready for Review
agent: devops-engineer
depends_on: [TASK-008-02]
date: 2026-09-21
---

# TASK-008-03: Per-Environment JWT Configuration

## Feature

FEAT-008

## Assigned Agent

`devops-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/resources/application.yaml` (modified)
  - `src/main/resources/application-local.yaml` (modified)
- Concern: which trust anchor each environment uses, and the audience. Configuration only — no
  Java, no bean, no validation logic (TASK-008-22 owns the startup validator that enforces this
  table).

## The table this task implements (ADR-007 §7)

| Environment | Mechanism | Keys set |
|---|---|---|
| Production / staging (default profile) | JWKS from the IdP | `spring.security.oauth2.resourceserver.jwt.issuer-uri`, **no public-key property** |
| `local` | one static RSA public key | `spring.security.oauth2.resourceserver.jwt.public-key-location`, pointing at `tools/dev-jwt/dev-public.pem` (a `file:` location — the key is outside the jar by design, TASK-008-02) |
| Tests | the same static key | configured by the test slice, **not here** |

Plus one application-owned key, in both files as appropriate:

- `challenge.security.jwt.audience` — the audience identifier the decoder requires. **Must be
  present in every profile**; an absent audience is one of TASK-008-22's six startup failures.
- `challenge.security.jwt.max-lifetime` — the `exp - iat` ceiling, default `1h` (ADR-007 §3, a
  labelled proposal, not a measured number).

`application.yaml` must carry `issuer-uri` as a placeholder resolved from the environment
(`${CHALLENGE_JWT_ISSUER_URI:}` shape), so a deployment supplies it and the startup validator
refuses to boot when it is blank. **`application.yaml` must not contain `public-key-location` at
all** — not commented out, not as an empty value. Its presence outside `local`/`test` is a
startup failure by ADR-007 §7 rule 1, and a commented-out line is an invitation to uncomment it.

`application-local.yaml` sets `public-key-location`, a local issuer string and an audience, and
must **not** set `issuer-uri` to a remote host.

Comment both blocks the way the existing local file comments its compressed timings: say that the
local key is a publicly known test key and that production uses JWKS only.

## Out of Scope

- The `SecurityConfigurationValidator` bean (TASK-008-22) and the `JwtDecoder` (TASK-008-20).
- Any test-resources configuration.
- Any change to `compose.yaml`, any IdP container.
- Any change to an existing `challenge.*` key.

## Acceptance Criteria

- [ ] `application.yaml` sets `issuer-uri` from an environment placeholder and sets
      `challenge.security.jwt.audience` and `challenge.security.jwt.max-lifetime: 1h`.
- [ ] `application.yaml` contains no `public-key-location` key in any form, including comments.
- [ ] `application-local.yaml` sets `public-key-location` pointing at `tools/dev-jwt/dev-public.pem`,
      a local issuer, and an audience, and sets no remote `issuer-uri`.
- [ ] The local block states in a comment that the key is a publicly known test key with no
      security value.
- [ ] **DEFERRED — needs a running context:** the application starts under the `local` profile
      with the TASK-008-02 key present. Verify by inspection this phase; do not boot the app.
- [ ] No secret, password or private key value appears in either file.
- [ ] No new OWASP Top 10:2025 exposure introduced (A02: the two mechanisms are not expressible
      in the same profile).

## Definition of Done

Files edited and checked by inspection. **Do not boot the application and do not run
`./gradlew test` or `./gradlew build`** — this phase verifies with `./gradlew compileJava
compileTestJava`. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
