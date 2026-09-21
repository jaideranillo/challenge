---
name: error-spring-security-multi-chain-gap
description: Defining a SecurityFilterChain bean disables Spring Boot's default chain entirely, leaving unmatched paths unprotected unless a catch-all chain is added
metadata:
  type: errors
---

# Error: partial SecurityFilterChain leaves other paths unauthenticated

**Symptom:** after adding `@Bean SecurityFilterChain` matched to one path prefix (`securityMatcher("/local/webhook-stub/**")`, `permitAll()`), an unrelated path returned 404 instead of the expected 401 — meaning zero security enforcement on it, not just a missing route.

**Root cause:** Spring Boot's `SecurityAutoConfiguration` backs off completely as soon as any `SecurityFilterChain` bean is defined in the context. It does not "add" a bean alongside the default — it disables the default. A chain scoped to one `securityMatcher` therefore leaves every unmatched request path filterless.

**Fix:** always pair a scoped chain with a second, unscoped `@Order` chain (no `securityMatcher`, so it matches "any request") that replicates whatever default behavior you're not deliberately opting out of (`anyRequest().authenticated()` + `httpBasic()` here). Caught via a test asserting an unrelated path still requires auth — write that test whenever adding a scoped `SecurityFilterChain`.

See [[project-feat-001-local-dev-environment]] (TASK-001-08) for the concrete implementation (`LocalWebhookStubSecurityConfig`, two `@Order`ed beans).
