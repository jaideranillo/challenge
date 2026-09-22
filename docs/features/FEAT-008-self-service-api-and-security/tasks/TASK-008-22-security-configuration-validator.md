---
id: TASK-008-22
feature: FEAT-008
title: SecurityConfigurationValidator — fail startup on six misconfigurations
status: Ready for Review
agent: security-engineer
depends_on: [TASK-008-03, TASK-008-20]
date: 2026-09-21
---

# TASK-008-22: `SecurityConfigurationValidator`

## Feature

FEAT-008

## Assigned Agent

`security-engineer` — this task is only for this agent.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/web/security/SecurityConfigurationValidator.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/in/web/security/SecurityConfigurationValidatorTest.java` (new)
- Concern: refuse to boot on a configuration that would weaken the trust anchor.

## The six rules (ADR-007 §7) — all six, no fewer

The bean runs during context initialization and **fails the application start** if any holds:

1. The active profile is **not** `local` or `test`, and `jwt.public-key-location` **is** set. A
   static key in production is the exact failure ADR-007 §7 exists to prevent.
2. The active profile is not `local` or `test`, and `jwt.issuer-uri` is absent or blank.
3. `jwt.issuer-uri` is set, in a non-local profile, to a **loopback, private or link-local host**.
   A production service pointing its trust anchor at `localhost` is either a copy-paste error or
   an attack, and neither should boot.
4. The configured **audience is absent in any profile**. An audience-less resource server accepts
   any token from the issuer.
5. The resolved RSA key is **smaller than 2048 bits**.
6. The `local` profile is active **and** the build is a release artifact (detect via a
   build-stamped property). A production image starting in the local profile is itself the
   misconfiguration.

**Startup failure, not a log warning.** ADR-007 §7 is explicit about why: a warning in a
healthy-looking boot log is not a control. Fail closed, loudly, before serving one request
(A02, A10).

**There is deliberately no fallback**: no default key, and no "if JWKS is unavailable, use the
configured static key" path. A resource server that degrades to a weaker trust anchor when its
strong one is unreachable has turned a transient IdP outage into a permanent authentication
bypass. If JWKS cannot be reached, requests fail 401 and the service stays up to serve health
probes and the pipeline — which does not depend on client tokens at all. Implement nothing that
softens that.

For rule 6, if no build-stamp property exists in this repository, say so in the handover and
implement the rule against a named property that a release build would set; do not invent a build
plugin (that would be a devops task).

## Error messages

A startup failure message may name **which rule** failed and the property involved. It must not
print a key, a token, a secret, or the issuer's credentials. This is boot output, not a response,
so naming the misconfiguration is correct here — the opposite of TASK-008-23's rule for 401 bodies.

## Testing (phase rule — read before writing any test)

**Unit tests only in this phase: plain JUnit, no Spring context, no Testcontainers, no Docker.**

Write the validator so its rules are **callable as plain methods over plain inputs** (profiles,
property values, a key size, a host string), and unit-test all six that way. That design choice is
what makes this task fully testable now, so make it deliberately rather than putting the logic
inside an `ApplicationListener` that only a booting context can reach.

`DEFERRED — Testcontainers/context`: any test that actually boots an application context and
expects it to fail.

Verify with `./gradlew compileJava compileTestJava` plus this task's unit tests; **do not run
`./gradlew test` or `./gradlew build`.**

## Out of Scope

- The decoder itself — TASK-008-20.
- Configuration values — TASK-008-03.
- The API pool's `BYPASSRLS` startup assertion — TASK-008-09 owns that, deliberately, so this
  class stays a JWT-configuration concern and does not grow a database dependency.
- Any runtime request-path check.

## Acceptance Criteria

- [ ] All six rules are implemented, each as a separately named, independently testable check.
- [ ] A violation **fails context startup**; nothing is downgraded to a warning or a log line.
- [ ] No fallback key, no default issuer, no JWKS-unavailable degradation path exists anywhere.
- [ ] Rule 3 rejects loopback, RFC1918 private and link-local issuer hosts in non-local profiles.
- [ ] Rule 5 checks the resolved key's modulus size, not a configured claim about it.
- [ ] Failure messages name the rule and property, and contain no key, token or secret.
- [ ] Unit tests cover all six rules — each failing case and its matching passing case — with no
      Spring context.
- [ ] **DEFERRED — context test:** a booting application with a violating configuration fails to
      start.
- [ ] Nothing in `application/**` or `domain/**` is touched.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (A02, A04, A10).

## Definition of Done

Code written, unit tests passing. **Do not run `./gradlew test` or `./gradlew build`.** **Do not
run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
