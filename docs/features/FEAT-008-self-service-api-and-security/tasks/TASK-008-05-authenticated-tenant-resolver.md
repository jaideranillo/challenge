---
id: TASK-008-05
feature: FEAT-008
title: AuthenticatedTenantResolver and the TenantId argument resolver
status: Ready for Review
agent: security-engineer
depends_on: [TASK-008-01, TASK-008-04]
date: 2026-09-21
---

# TASK-008-05: `AuthenticatedTenantResolver` and the Argument Resolver

## Feature

FEAT-008

## Assigned Agent

`security-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/web/security/AuthenticatedTenantResolver.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/in/web/security/TenantIdArgumentResolver.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/in/web/security/config/TenantWebMvcConfig.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/in/web/security/AuthenticatedTenantResolverTest.java` (new)
- Concern: the single place a tenant enters the application. Three small production files, one
  concern, per the repo's package-by-kind convention.

## What it does (ADR-007 §5.1)

`AuthenticatedTenantResolver` reads the `client_id` claim from the `Jwt` on the current
`Authentication` and returns a `TenantId`. **This is the only production construction site of
`TenantId` in the entire codebase** — that is the property TASK-008-06's ArchUnit rule asserts,
and it is the single class a security review has to read to know where tenancy comes from.

`TenantIdArgumentResolver` is a Spring MVC `HandlerMethodArgumentResolver` that supports the
`TenantId` parameter type and delegates to the resolver, so a handler signature reads:

```
list(TenantId tenant, @Valid ListQuery query)
```

`TenantWebMvcConfig` registers it via `WebMvcConfigurer#addArgumentResolvers`.

**The handler never sees the claim and never constructs a tenant.** Declaring the parameter must
be the easiest way to obtain one — an enforced path that is inconvenient gets routed around, and
that is the whole premise of ADR-007 §5.

## Failure behavior — fails closed

| Situation | Behavior |
|---|---|
| No `Authentication`, or it is not a `JwtAuthenticationToken` | throw; never return a default, never return `null`, never fall back to a request parameter |
| `client_id` claim absent or blank | throw |
| `client_id` present but malformed | `TenantId`'s own constructor throws (TASK-008-04) |

In practice the second and third cases are unreachable in production because TASK-008-20's
decoder-level validator rejects such a token with 401 before any handler runs. Implement them
anyway: a control that relies on another control having run is not a layer.

Whatever is thrown must surface as a 401 or 500 with no cross-tenant detail and must not echo the
claim value. Do not add an `@ExceptionHandler` here — TASK-008-23 owns error bodies.

## Virtual-thread note (ADR-007 §2)

`SecurityContextHolder` must stay `MODE_THREADLOCAL`. Do not set
`MODE_INHERITABLETHREADLOCAL` anywhere, and do not cache a resolved `TenantId` in a static, a
thread-local or a field — resolve per call, from the current `Authentication`. Nothing here may
hold a lock across I/O.

## Out of Scope

- The `JwtDecoder`, the validators and the authority mapping — TASK-008-20.
- Any `SecurityFilterChain` — TASK-008-21.
- The ArchUnit rules — TASK-008-06.
- Any controller — TASK-008-25.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**
All of it is unit-testable: build a `Jwt` and a `JwtAuthenticationToken` by hand and call the
resolver directly. **No `@SpringBootTest`, no `@WebMvcTest`, no MockMvc** — the argument
resolver's registration is verified by reading the configuration, and end-to-end binding is
`DEFERRED — Testcontainers` under TASK-008-28. Verify with `./gradlew compileJava
compileTestJava` plus this task's unit tests; **do not run `./gradlew test` or `./gradlew build`.**

## Acceptance Criteria

- [ ] `AuthenticatedTenantResolver` is the only production class that constructs a `TenantId`.
- [ ] It reads `client_id` from the `Jwt` on the current `Authentication` and from nowhere else —
      no request parameter, no path variable, no header, no `RequestContextHolder`.
- [ ] `TenantIdArgumentResolver` supports exactly the `TenantId` parameter type and is registered
      through a `WebMvcConfigurer`.
- [ ] Unit tests cover: a valid JWT with `client_id` yields the expected `TenantId`; no
      authentication throws; a non-JWT authentication throws; a missing claim throws; a blank
      claim throws; a malformed claim throws.
- [ ] No test asserts a fallback or default tenant, because none exists.
- [ ] `SecurityContextHolder`'s strategy is not changed anywhere in this task, and no resolved
      tenant is cached in a static, field or thread-local.
- [ ] Nothing logs the token, its signature or the raw `Authorization` header (A09).
- [ ] These classes live in `adapter/in/web/security`; nothing in `application/**` or `domain/**`
      is touched.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
