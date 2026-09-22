---
id: TASK-008-21
feature: FEAT-008
title: Four security filter chains — actuator, internal, client API, terminal deny
status: Ready for Review
agent: security-engineer
depends_on: [TASK-008-20]
date: 2026-09-21
---

# TASK-008-21: Security Filter Chains

## Feature

FEAT-008

## Assigned Agent

`security-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/web/security/config/SecurityConfig.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/in/web/local/webhookstub/LocalWebhookStubSecurityConfig.java` (modified)
  - `src/test/java/com/cobre/challenge/adapter/in/web/security/config/SecurityConfigTest.java` (new)
- Concern: the chain topology of ADR-007 §2. One concern — which paths are reachable by whom.

## The four chains, in order (ADR-007 §2)

Separate chains, not one chain with many `requestMatchers`, because the three surfaces
authenticate differently — a JWT, an IAM SigV4 signature, and nothing at all. Merging them means
one chain whose rules encode "authenticated, but by which of three mechanisms", which is where
permit-all mistakes are made.

| Order | Chain | Matcher | Rules |
|---|---|---|---|
| 1 | Actuator | `/actuator/**` | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` permitted unauthenticated (orchestrator probes carry no token). **Every other actuator endpoint** requires authentication **and the `ops` authority**. `management.endpoint.health.show-details` set to `never` — a probe needs the status code, not a component breakdown that names internal dependencies (A02) |
| 2 | Ingest | `/internal/**` | `authenticated()`, with **no client JWT accepted**. ADR-007 §2 reserves this path for AWS IAM SigV4 (ADR-002 Q10); **this task does not design or implement that verification**. Wire the chain so the existing `/internal/events` endpoint is not newly broken, and state precisely what you did in the handover so the follow-up owner inherits a known state |
| 3 | Client API | `/notification_events/**` | the chain below |
| 4 | Terminal | `/**` | `denyAll()`. A new controller on a new path is dead on arrival until someone deliberately adds it to a chain. This is what deny-by-default means concretely: the default for an unlisted path is 403, not "whatever the last chain happened to say" |

**Chain 3, in this order** (ADR-007 §2):

1. `securityMatcher("/notification_events/**")`
2. `csrf().disable()` — stateless bearer-token API, no cookie is ever issued or read, so there is
   no ambient credential for a cross-site request to ride
3. `sessionManagement(STATELESS)` — no `JSESSIONID`, no `HttpSession`, no context persistence
4. `cors` disabled — server-to-server; if a dashboard ever needs it, that is an explicit
   allow-list, never `*`, never `allowCredentials(true)` with a reflected origin
5. Response headers: HSTS, `X-Content-Type-Options: nosniff`, **`Cache-Control: no-store` on every
   response** (delivery history is tenant data and must not sit in an intermediary),
   `Content-Security-Policy: default-src 'none'` (the API returns only JSON)
6. `oauth2ResourceServer().jwt(...)` with TASK-008-20's decoder and converter
7. the rate-limit filter slot, **after** authentication (TASK-008-24 fills it; leave the
   placement correct even if the filter lands later)
8. authorization rules: `GET /notification_events` and `GET /notification_events/{id}` require
   `notifications:read`; `POST /notification_events/{id}/replay` requires
   `notifications:replay`; then `anyRequest().denyAll()` **inside this chain too**
9. exception handling hooks — TASK-008-23 supplies the entry point and handler; wire the
   extension points here

## The merged local config must be reconciled, not left to collide

`LocalWebhookStubSecurityConfig` currently defines a stub chain at `@Order(1)` **and a catch-all
`defaultFilterChain` at `@Order(2)` that authenticates everything with HTTP Basic**. That
catch-all must go: chain 4 above is now the terminal rule, and leaving both means the ordering
decides the security posture of the whole application by accident.

Keep the stub chain itself (it is `local`-profile only and FEAT-007's demo needs it), renumber the
orders so the stub sits before the terminal deny chain, and delete `defaultFilterChain`. Update
that class's javadoc, which currently explains why the catch-all exists.

## Method-level authorization is used in addition, not instead

`@PreAuthorize` on the controller handlers (TASK-008-25, TASK-008-26) deliberately duplicates
rule 8: the URL rule protects the path, the annotation protects the handler if a mapping is ever
changed or a second mapping is added. Two cheap checks that fail independently. Enable method
security here (`@EnableMethodSecurity`).

## Virtual-thread constraints (ADR-007 §2)

`SecurityContextHolder` stays `MODE_THREADLOCAL` — **never** `MODE_INHERITABLETHREADLOCAL`, which
would copy a principal into spawned threads and, on virtual threads, spread it in ways nobody
reasoned about. No security context is propagated into the relay, worker or DLQ beans; they run
with no principal on purpose. Nothing in this chain holds a lock across I/O.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**
A filter chain is hard to test without a context, so keep this phase's test to what is genuinely
unit-level: the configuration class's structure — that four chain beans exist with the expected
orders and matchers, asserted by calling the bean methods with a stubbed/builder `HttpSecurity`
where practical, or by a simple reflective check of the declared `@Order` values and matchers.

`DEFERRED — Testcontainers` (specify, do not write): every request-level assertion — an unlisted
path returning 403, an unauthenticated actuator metrics call being refused, a probe path being
permitted, a missing scope returning 403. TASK-008-28 owns the end-to-end ones.

Verify with `./gradlew compileJava compileTestJava`; **do not run `./gradlew test` or
`./gradlew build`.**

## Out of Scope

- SigV4 verification for `/internal/**` (ADR-007 §8) — reserve the chain, implement nothing.
- The decoder and validators — TASK-008-20.
- The startup validator — TASK-008-22.
- Error bodies — TASK-008-23.
- The rate-limit filter itself — TASK-008-24.
- Any controller.

## Acceptance Criteria

- [ ] Four chain beans exist, ordered, with disjoint matchers, and the terminal chain is
      `denyAll()` on `/**`.
- [ ] Only the three health paths are unauthenticated; every other actuator endpoint requires
      authentication and the `ops` authority; health details are `never`.
- [ ] The `/internal/**` chain is `authenticated()` and **does not accept a client JWT**.
- [ ] Chain 3 implements steps 1–9 above in that order, including `anyRequest().denyAll()` inside
      the chain and the four response headers.
- [ ] `Cache-Control: no-store` is set on every response from chain 3.
- [ ] `notifications:read` gates both GETs; `notifications:replay` gates the POST; neither implies
      the other.
- [ ] Method security is enabled so TASK-008-25/-26 can annotate handlers.
- [ ] `LocalWebhookStubSecurityConfig`'s `defaultFilterChain` is **deleted**, the stub chain
      survives with a corrected order, and its javadoc is updated.
- [ ] `SecurityContextHolder`'s strategy is not changed anywhere; no `MODE_INHERITABLETHREADLOCAL`.
- [ ] No `permitAll()` appears anywhere except the three health probe paths and the `local`-profile
      stub chain.
- [ ] **DEFERRED — Testcontainers:** request-level assertions for unlisted-path 403, actuator
      split, probe access, and missing-scope 403.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A01, A02, A07).

## Definition of Done

Code written, structural unit test passing. **Do not run `./gradlew test` or `./gradlew build`.**
**Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
