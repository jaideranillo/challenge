---
id: TASK-007-07
feature: FEAT-007
title: WebhookSecretPort and the configuration-backed secret resolver
status: Ready for Review
agent: security-engineer
depends_on: []
date: 2026-09-21
---

# TASK-007-07: `WebhookSecretPort` and its resolver

## Feature

FEAT-007

## Assigned Agent

`security-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/out/secrets/WebhookSecretPort.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/out/secrets/ConfiguredWebhookSecretResolver.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/out/secrets/config/WebhookSecretProperties.java` (new)
- Concern: turn `subscriptions.secret_ref` into usable HMAC key material, behind a port, without
  ever putting plaintext in the database.

### The gap this fills, stated honestly

ADR-004 §3 pins `secret_ref` as "a reference (secrets-manager key), never the plaintext HMAC
secret", and ADR-004 §2 says the secret "is loaded once per attempt, and is never logged". No
secrets manager exists in this project and no ADR designs one. This task therefore ships the
smallest resolver that satisfies the stated invariant — configuration-backed — behind a port so
replacing it with a real secrets-manager client is a one-adapter change. **The inference is logged
in `docs/concerns.md`; do not treat it as an ADR decision and do not amend any ADR.**

### Contract

```java
// application/port/out/secrets/WebhookSecretPort
/** Resolves a subscriptions.secret_ref to secret material; empty when the reference is unknown. */
Optional<String> resolve(String secretRef);
```

- `Optional.empty()`, never `null`, never a thrown exception, for an unknown reference. An unknown
  reference is an operational condition the use case must handle as a non-retryable failure, not a
  crash (A10).
- The port returns material, never the reference. No method exposes the whole map, lists
  references, or reports how many secrets are configured.

### Adapter

- `WebhookSecretProperties`: `@ConfigurationProperties("challenge.webhook")` with one component,
  `Map<String, String> secrets`, keyed by reference.
- `ConfiguredWebhookSecretResolver` reads that map, defensively copied at construction, and does a
  lookup. No caching layer, no refresh, no fallback to a default secret.
- **Nothing in these three files logs, and nothing overrides `toString()`** on a type holding
  secret material. A `@ConfigurationProperties` record's generated `toString()` would print every
  secret if the record is ever logged or dumped by an actuator endpoint — either hold the map in a
  non-record class, or override `toString()` to a constant. State which you chose and why.
- **Do not commit a secret value.** `application.yaml` gets no `challenge.webhook.secrets` entry
  at all; document in the handover that the local profile and each environment supply their own
  (environment variable or an untracked file). If a demo value is genuinely needed for `bootRun`,
  say so in the handover and let the Tech Lead decide — do not add it yourself.
- Verify no actuator endpoint exposes these properties in the current configuration (`/env`,
  `/configprops`) and report what you found.

## Out of Scope

- The signing itself (TASK-007-08).
- Secret rotation triggers, window length, or any write to `secret_ref` / `previous_secret_ref`
  (ADR-004 §2's follow-up, Q9 territory).
- Any real secrets-manager client or new dependency.
- Reading the subscription row. The reference arrives as a `String` from the caller.

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker.

Required unit scenarios:
- a known reference resolves to its configured material
- an unknown reference returns `Optional.empty()` and does not throw
- a null or blank reference returns `Optional.empty()` and does not throw
- the map passed at construction is defensively copied — mutating the caller's map afterwards does
  not change what the resolver returns
- `toString()` on the properties type (or the resolver) contains no secret value

**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileJava
compileTestJava` and report the tests as written and pending the Tech Lead's later explicit run.

## Acceptance Criteria

- [ ] The port lives in `application/port/out/secrets` and carries no framework type.
- [ ] `resolve` returns `Optional`, never `null`, and never throws for an unknown reference.
- [ ] No API exposes the full secret map, a list of references, or a count.
- [ ] No secret value can reach a log line, an exception message, or a generated `toString()`.
- [ ] No secret value is committed to any file in the repository.
- [ ] A one-line note in the handover records whether `/env` or `/configprops` would expose these
      keys under the current actuator configuration.
- [ ] Every unit scenario listed above is covered by a plain JUnit test.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] A04 exposure is documented for TASK-007-19 rather than assumed closed.

## Definition of Done

Code written, tests written but **not run**. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

Handover notes:

- `WebhookSecretProperties` is a plain class, not a record, and overrides `toString()` to a
  constant (`"WebhookSecretProperties[secrets=<redacted>]"`) for the reason ADR-004 §2 requires:
  a record's generated `toString()` would print every secret if this bean were ever logged or
  dumped by an actuator endpoint. `ConfiguredWebhookSecretResolver` does the same for the same
  reason, since it also holds the map.
- Actuator exposure: no `management.endpoints.web.exposure.include` override exists anywhere in
  `application.yaml`/`application-local.yaml`, so the Spring Boot default applies (only `health`
  exposed over web). `/env` and `/configprops` are therefore not reachable in the current
  configuration. This is a default, not a guarantee: if a future task adds
  `management.endpoints.web.exposure.include: "*"` (or `env`/`configprops` explicitly) without also
  sanitizing `challenge.webhook.secrets`, the raw map becomes visible through that endpoint despite
  the `toString()` override, since actuator's env/configprops sanitization is a separate mechanism
  keyed on property-name patterns (`password`, `secret`, `key`, `token`, etc. by default) that
  happens to match `challenge.webhook.secrets` today but is config, not code, and can be
  reconfigured. **A04 is not treated as closed by this task** — full closure (verifying the
  sanitization pattern still matches, and no profile widens exposure) is left to TASK-007-19 per
  the acceptance criteria.
- No `challenge.webhook.secrets` entry was added to `application.yaml` or
  `application-local.yaml`. Each environment (including local `bootRun`) must supply its own
  values via an environment variable (`CHALLENGE_WEBHOOK_SECRETS_<REF>=...`, Spring relaxed
  binding) or an untracked properties/yaml file. No demo value was added; if `bootRun` needs one
  for a live demo, that's a Tech Lead call, not this task's.
- `docs/concerns.md` already carries the inference that no secrets manager exists and this
  resolver is the config-backed stand-in; nothing further was added there since the task file
  itself states the inference and forbids amending any ADR.
- Verified by manual `javac` compilation against the project's resolved dependency jars (not
  `./gradlew compileJava`): the repo's `compileJava` currently fails on an unrelated, pre-existing
  error in another in-flight file (`WebhookEnvelope.java`, `tools.jackson.annotation` import),
  confirmed present before this task's changes via `git stash`. All three main files and their
  three test files compile clean in isolation. Tests are written per the required scenarios and
  intentionally not run, per the Definition of Done.
