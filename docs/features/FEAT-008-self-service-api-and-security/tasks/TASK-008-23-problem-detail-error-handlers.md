---
id: TASK-008-23
feature: FEAT-008
title: RFC 9457 authentication entry point and access-denied handler
status: Ready for Review
agent: security-engineer
depends_on: [TASK-008-21]
date: 2026-09-21
---

# TASK-008-23: RFC 9457 Error Handlers

## Feature

FEAT-008

## Assigned Agent

`security-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/web/security/ProblemDetailAuthenticationEntryPoint.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/in/web/security/ProblemDetailAccessDeniedHandler.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/in/web/security/ProblemDetailHandlersTest.java` (new)
- Concern: what a rejected request sees. Two handlers, wired into the chain's extension points
  (TASK-008-21 left them open).

## The rule that shapes both (ADR-007 §2, step 9)

**Every distinct authentication failure produces an identical 401 body.** An expired token, a
wrong audience, a bad signature, a missing `client_id` and an absent header are the same response,
byte for byte. Telling an attacker which check failed is free reconnaissance.

| Situation | Status | Body |
|---|---|---|
| No token, malformed, bad signature, expired, wrong `iss`/`aud`, missing `client_id` | 401 | one identical problem detail |
| Valid token, missing required scope | 403 | a problem detail saying the request was refused, naming no scope |
| Valid token and scope, another tenant's id or a nonexistent id | 404 | TASK-008-25 owns this one; it is listed so the whole table is visible in one place |
| Rate limit exhausted | 429 with `Retry-After` | TASK-008-24 owns it |

**RFC 9457 problem-detail JSON**, with **no stack trace, no exception class name, and no
indication of which validation failed**. Spring's `ProblemDetail` is the natural vehicle. Do not
include a `WWW-Authenticate` header that enumerates the failure reason (`error_description`) —
the default bearer-token entry point does exactly that, which is why this task replaces it.

**Bodies carry no cross-tenant information** (ADR-007 §5.5): never echo the `client_id`, never
name a tenant, and do not vary the body between the two 404 cases in any way that is cheap to
avoid.

## Logging (A09)

Authentication failures and authorization denials are logged **structurally** with `client_id`
(when a token was decodable), `sub`, trace id and outcome. **The bearer token, its signature and
any key material are never logged, at any level.** Note in the handover that ADR-007 §9 calls out
two things worth alerting on which are enabled by this logging: a sustained 401 rate from one
source, and **any 403 on the replay scope** — a client attempting an operation it was never
granted is a signal, not noise.

The log may record which check failed. The **response** may not. That asymmetry is the whole
point: operators need the reason, attackers do not get it.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**
Both handlers take a request, a response and an exception — call them directly with
`MockHttpServletRequest`/`MockHttpServletResponse` (or equivalents) and assert the status, the
content type and the exact body. No `@SpringBootTest`, no MockMvc.

The identical-body property is the headline assertion and is fully unit-testable: drive the entry
point with several different `AuthenticationException` subtypes and assert the serialized bodies
are equal.

Verify with `./gradlew compileJava compileTestJava` plus this task's unit tests; **do not run
`./gradlew test` or `./gradlew build`.**

## Out of Scope

- The 404 and 409 mappings — TASK-008-25 and TASK-008-26, which are controller concerns.
- The 429 body — TASK-008-24.
- Any `@ControllerAdvice` for business exceptions.
- Changing the chain's structure — TASK-008-21.

## Acceptance Criteria

- [ ] Both handlers emit RFC 9457 problem-detail JSON with the correct content type.
- [ ] **Every authentication failure yields a byte-identical 401 body**, proven by a test driving
      at least four distinct failure causes and asserting equality.
- [ ] No body contains a stack trace, an exception class name, a claim value, a scope name, or any
      hint of which validation failed.
- [ ] No `WWW-Authenticate` header enumerating the error reason is emitted.
- [ ] 403 is returned for a valid token lacking the authority, with a body that names no scope.
- [ ] Failures are logged structurally with `client_id` (when known), `sub`, trace id and outcome,
      and **never** the token, signature or key material.
- [ ] Unit tests only; no Spring context.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A07, A09).

## Definition of Done

Code written, unit tests passing. **Do not run `./gradlew test` or `./gradlew build`.** **Do not
run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
