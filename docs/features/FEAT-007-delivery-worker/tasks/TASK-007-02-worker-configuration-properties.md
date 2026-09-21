---
id: TASK-007-02
feature: FEAT-007
title: Worker configuration properties and their defaults
status: Ready for Review
agent: devops-engineer
depends_on: []
date: 2026-09-21
---

# TASK-007-02: Worker configuration properties

## Feature

FEAT-007

## Assigned Agent

`devops-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/messaging/config/WorkerProperties.java` (new)
  - `src/main/resources/application.yaml` (modified)
  - `src/main/resources/application-local.yaml` (modified)
- Concern: one validated `@ConfigurationProperties` record carrying every worker dial, with
  ADR-sourced defaults. No behavior, no beans other than the properties binding.

### Keys and defaults

`challenge.worker.*`, mirroring the shape of the merged `RelayProperties`:

| Key | Default | Source |
|---|---|---|
| `enabled` | `true` | on by default; `false` lets a test or a demo run without a consumer |
| `wait-time` | `20s` | ADR-002 §2.2, ADR-006 §1.1 long poll |
| `batch-size` | `10` | ADR-002 §2.2, ADR-006 §1.1 receive batch |
| `connect-timeout` | `2s` | ADR-004 §1 budget table |
| `read-timeout` | `5s` | ADR-004 §1 budget table |
| `bulkhead.acquire-timeout` | `2s` | ADR-006 §1.3 |
| `bulkhead.defer-min` | `10s` | ADR-006 §1.3 "10-20s jittered" |
| `bulkhead.defer-max` | `20s` | ADR-006 §1.3 |
| `circuit-breaker.failure-threshold` | `10` | ADR-006 §1.2 / Q7, a labelled proposal |
| `circuit-breaker.base-cooldown` | `30s` | inferred, FEAT-007 ambiguity 3, a labelled proposal |
| `circuit-breaker.max-cooldown` | `1h` | inferred, FEAT-007 ambiguity 3, the "capped" of ADR-006 §1.2 |
| `retry-after-max` | `1h` | inferred, FEAT-007 ambiguity 4 — the clamp ceiling for `Retry-After` |

### The circuit-breaker values are profile-specific

Tech Lead direction, 2026-09-21. The three breaker keys are the **only** worker keys that differ
by profile, and the difference is deliberate: a 30s-to-1h escalation cannot be demonstrated in a
live demo.

| | `failure-threshold` | `base-cooldown` | `max-cooldown` | Resulting escalation |
|---|---|---|---|---|
| Default (`application.yaml`, production) | `10` | `30s` | `1h` | 30s -> 1m -> 2m -> ... -> 1h |
| `local` (`application-local.yaml`, demo) | `3` | `10s` | `60s` | 10s -> 20s -> 40s -> 60s |

The escalation column is not a separate key: it is `base * 2^consecutive_opens` capped at
`max-cooldown`, already implemented in SQL by the merged `SubscriptionJdbcRepository` (ADR-006
§1.2, Amendment B3). Both rows above are that formula with different inputs — confirm by hand
that each sequence is reproduced by it before writing the values, and note the check in your
handover.

`retry-after-max` deliberately equals `max-cooldown` in each profile (1h production, 60s local):
a client's `Retry-After` should not be able to park a subscription for longer than the breaker's
own worst-case cooldown. Keep them equal in both files.

These local values belong alongside the compressed timings already in `application-local.yaml`
(`challenge.retry.backoff: 2s, 5s, 10s` and `challenge.relay.poll-interval: 2s`), under that
file's existing warning that local timings deliberately diverge from the ADR schedules.
| `response-excerpt-limit` | `1024` | truncation bound for `delivery_attempts.response_excerpt` (ADR-003 §3) |

Nest `bulkhead` and `circuit-breaker` as inner records, the way `SqsProperties` nests
`Credentials`/`Queues`. Validate with Bean Validation (`@Positive`, `@NotNull`) and `@Validated`,
as `RelayProperties` and `SqsProperties` already do.

In `application.yaml`, write every key with the default above and a one-line comment naming its
ADR section. In `application-local.yaml`, compress only what a live demo needs (a shorter
`circuit-breaker.base-cooldown`, e.g. `5s`), following the existing file's own warning that local
timings deliberately diverge from the ADR schedules.

**One-line comments only** (repo convention). The ADR rationale lives in this task file and in
`feature.md`, not in the code.

## Out of Scope

- Enabling the listener, creating any bean other than the properties record, or referencing these
  properties from any class. Consumers are TASK-007-06, -10, -11, -12, -17, -18.
- `challenge.retry.*` (TASK-007-03) and `challenge.webhook.secrets.*` (TASK-007-07).
- The `Clock` and `RandomGenerator` beans the worker needs. They belong to TASK-007-03's
  addendum 3a, which declares them alongside the `RetryPolicy` bean. **Do not declare them here
  as well** — two definitions of `RandomGenerator` would mean two generators, or a startup
  failure on an ambiguous bean.
- Anything under `challenge.relay.*` or `challenge.sqs.*` — both merged and correct.

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker. Add a plain JUnit test constructing `WorkerProperties` directly and asserting the
validation constraints reject a non-positive `batch-size` and a null nested block — the same shape
as the merged `RelayPropertiesTest`, minus any Spring context it uses.

**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileJava
compileTestJava` (or the IDE's compile) and report that the tests are written and pending the Tech
Lead's later explicit run.

## Acceptance Criteria

- [ ] `WorkerProperties` is a `@Validated @ConfigurationProperties("challenge.worker")` record with
      exactly the keys above, `bulkhead` and `circuit-breaker` as nested records.
- [ ] Every default in `application.yaml` matches the table above exactly.
- [ ] `application-local.yaml` carries the demo breaker triple (`3` / `10s` / `60s`) and
      `retry-after-max: 60s`, and changes no other worker key.
- [ ] `retry-after-max` equals `circuit-breaker.max-cooldown` in both profiles.
- [ ] The handover states that both escalation sequences were checked by hand against
      `base * 2^consecutive_opens` capped at `max-cooldown`.
- [ ] No class outside this task reads the new properties.
- [ ] Comments are one line each; no ADR-citing paragraph appears in the code.
- [ ] Unit test (plain JUnit, no Spring context) covers the validation constraints.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer).

## Definition of Done

Code written, tests written but **not run**. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

### Handover

- `WorkerProperties` created as a `@Validated @ConfigurationProperties("challenge.worker")` record
  with nested `Bulkhead` and `CircuitBreaker` records, matching the key table exactly. No bean,
  no consumer, no other class references it.
- `application.yaml`: every key added under `challenge.worker` with the production defaults
  (`failure-threshold: 10`, `base-cooldown: 30s`, `max-cooldown: 1h`, `retry-after-max: 1h`), one
  ADR-citing comment per line.
- `application-local.yaml`: only the demo breaker triple added
  (`failure-threshold: 3`, `base-cooldown: 10s`, `max-cooldown: 60s`) plus `retry-after-max: 60s`,
  alongside the existing compressed `retry.backoff`/`relay.poll-interval` block, under the file's
  existing divergence warning (extended to name the breaker keys too). No other worker key
  touched in this file.
- Escalation check by hand against `base * 2^consecutive_opens` capped at `max-cooldown`:
  - Production (`base=30s`, `max=1h`): 30s, 1m, 2m, 4m, 8m, 16m, 32m, then 30s*2^7=64m capped to
    1h. Matches "30s -> 1m -> 2m -> ... -> 1h".
  - Local (`base=10s`, `max=60s`): 10s, 20s, 40s, then 10s*2^3=80s capped to 60s. Matches
    "10s -> 20s -> 40s -> 60s".
  - Both sequences reproduce the table in the task file exactly.
- Unit test `WorkerPropertiesTest` (plain JUnit, no Spring context) added: builds a
  `jakarta.validation.Validator` directly and asserts a non-positive `batchSize` and a null
  `circuitBreaker` each produce a constraint violation on the expected property path.
- Verification: `./gradlew compileJava compileTestJava` was attempted but the build currently
  fails on an unrelated, pre-existing, untracked file
  (`src/main/java/com/cobre/challenge/application/port/out/webhook/dto/WebhookEnvelope.java`,
  missing `tools.jackson.annotation` dependency) that is outside this task's scope and was not
  touched here. `WorkerProperties.java` and `WorkerPropertiesTest.java` were manually checked
  against the working `RelayProperties`/`SqsProperties`/`RelayPropertiesTest` patterns for
  syntactic and API correctness. Per the task's testing phase rule, `./gradlew test`/`build` were
  not run; the new test is written and pending the Tech Lead's later explicit run.
