---
id: TASK-006-08
feature: FEAT-006
title: Relay configuration properties and scheduling enablement
status: Ready for Review
agent: devops-engineer
depends_on: []
date: 2026-09-21
---

# TASK-006-08: Relay configuration properties and scheduling enablement

## Feature

FEAT-006

## Assigned Agent

`devops-engineer` — configuration and wiring only. The scheduled method itself is TASK-006-09.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/scheduling/config/RelayProperties.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/in/scheduling/config/RelaySchedulingConfig.java` (new)
  - `src/main/resources/application.yaml`
- Concern: the relay's knobs and the scheduler that will drive it.

### `RelayProperties`

`@ConfigurationProperties(prefix = "challenge.relay")`, a record, with bean validation:

| Key | Type | Default | Source |
|---|---|---|---|
| `challenge.relay.enabled` | boolean | `true` | this task, see below |
| `challenge.relay.poll-interval` | `Duration` | `5s` | ADR-002 §2.1 ("a scheduled job, `fixedDelay`, 5s") |
| `challenge.relay.batch-limit` | int, `@Positive` | `500` | ADR-002 §2.1 (`LIMIT 500`) |

**These three values are taken verbatim from an `Accepted` ADR. Do not tune them, do not propose
alternatives, and do not add a fourth knob.** The 5-minute forward push and the batch chunk size
of 10 are deliberately **not** properties: the push lives in the due-query statement
(`CLAIM_DUE_PUSH_INTERVAL`) and the chunk size is ADR-006 §1.1's queue contract, not an
operational dial.

### `RelaySchedulingConfig`

- `@EnableScheduling`, gated on `@ConditionalOnProperty(prefix = "challenge.relay", name =
  "enabled", havingValue = "true", matchIfMissing = true)`.
- `@EnableConfigurationProperties(RelayProperties.class)`.

**The `enabled` flag exists for one specific reason and you should state it in a comment:** the
acceptance tests (TASK-006-10, TASK-006-11) drive `DispatchPendingDeliveriesUseCase` directly with
an explicit `asOf`, and a live scheduler racing those tests would claim their fixture rows out
from under them. Tests set `challenge.relay.enabled=false`. It is not a feature flag for
production and must not grow into one.

### Virtual threads

With `spring.threads.virtual.enabled=true` (already set in `application.yaml`), Spring Boot backs
`@Scheduled` with a virtual-thread `SimpleAsyncTaskScheduler`. **Verify that is what the
application actually gets and record how you verified it in your handoff** — do not assume it, and
do not hand-roll a `ThreadPoolTaskScheduler`. A relay cycle blocks on JDBC and on SQS, which is
exactly the workload virtual threads exist for; a fixed platform-thread pool sized by hand is the
pattern ADR-002 §2 rules out.

### `application.yaml`

Add the three keys with their defaults, each with a comment naming its ADR source. Do not touch
`spring.threads.virtual`, `spring.flyway`, or any existing key.

## Out of Scope

- `DeliveryRelayScheduler` and the `@Scheduled` method — TASK-006-09.
- The use case, the claimer, the due-query, and the SQS adapter.
- **Any new dependency.** Spring's scheduling support is already on the classpath via
  `spring-boot-starter`; adding a Gradle dependency here would be a new supply-chain surface
  (OWASP A03) for nothing.
- `compose.yaml`, `TestcontainersConfiguration`, Dockerfiles, and the LGTM stack. Nothing in this
  feature changes the dev-services topology.
- Queue settings (`VisibilityTimeout` 30s, `maxReceiveCount` 3, long poll 20s, receive batch 10).
  All are ADR-006 §1.1's and already configured; leave them alone.

## Acceptance Criteria

- [ ] `RelayProperties` is a validated `@ConfigurationProperties` record with exactly the three
      keys and defaults above.
- [ ] `RelaySchedulingConfig` enables scheduling, conditionally on `challenge.relay.enabled`,
      defaulting to on.
- [ ] `application.yaml` carries the three keys with ADR-sourced comments.
- [ ] A test asserts the defaults bind (5s, 500, enabled) — a small `@SpringBootTest` slice or a
      binding test, consistent with the merged `SqsQueueConfigurationTest`'s style.
- [ ] Setting `challenge.relay.enabled=false` leaves no scheduled relay task registered, asserted
      by a test.
- [ ] The virtual-thread backing of `@Scheduled` is verified and the verification is described in
      the handoff.
- [ ] No new Gradle dependency.
- [ ] `./gradlew test` passes.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable)

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's
`status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
