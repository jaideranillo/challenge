---
id: TASK-005-01
feature: FEAT-005
title: "AWS SDK SQS as a runtime dependency, and LocalStack SQS in TestcontainersConfiguration"
status: Ready for Review
agent: devops-engineer
depends_on: []
date: 2026-09-20
---

# TASK-005-01: SQS dependency and Testcontainers wiring

## Feature

FEAT-005

## Assigned Agent

`devops-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `build.gradle`
  - `src/test/java/com/cobre/challenge/TestcontainersConfiguration.java`
- Concern: making SQS available to production code and to `@SpringBootTest`, which it currently is not.

`software.amazon.awssdk:sqs:2.27.8` is presently `testImplementation` only (`build.gradle`), so no production class can reference it. Promote it to `implementation`, pinned through `software.amazon.awssdk:bom` via `dependencyManagement` (or an explicit pinned version — do not use a dynamic version, `+` or a range). `SqsQueueConfigurationTest` must keep compiling unchanged.

Add a LocalStack container to `TestcontainersConfiguration` alongside the existing Postgres and LGTM beans, running `sqs` only, with `docker/localstack/init-sqs.sh` copied to `/etc/localstack/init/ready.d/init-sqs.sh` exactly as `SqsQueueConfigurationTest` already does — so a `@SpringBootTest` gets the `deliveries` and `deliveries-dlq` queues with ADR-006 §1.1's attributes, from the same script the compose stack uses. One script, one source of truth; do not duplicate the queue attributes in Java.

If `@ServiceConnection` does not cover LocalStack SQS in this Boot version, expose the container's endpoint, region and credentials as test properties under the `challenge.sqs.*` keys `application-local.yaml` already declares (`endpoint`, `region`, `credentials.access-key`, `credentials.secret-key`), via a `DynamicPropertyRegistrar` bean in the same class. Which of the two mechanisms works is yours to determine; the requirement is that a `@SpringBootTest` importing `TestcontainersConfiguration` reaches a real SQS with no per-test wiring.

FEAT-001 deferred exactly this ("Testcontainers wiring for SQS ... is a distinct unit of work and is not part of this feature"). This is that unit of work.

## Out of Scope

- `compose.yaml` and `docker/localstack/init-sqs.sh` — both already correct, do not touch either.
- Any `SqsClient` bean or `@ConfigurationProperties` class. TASK-005-02.
- Any adapter, use case, or controller.
- Any change to `application.yaml`. The default profile gains nothing here.
- Removing, replacing or reconfiguring the Postgres or LGTM containers.

## Acceptance Criteria

- [ ] `software.amazon.awssdk:sqs` is on the `implementation` configuration at a pinned version, and no other AWS SDK service module is added.
- [ ] `SqsQueueConfigurationTest` still compiles and passes unchanged.
- [ ] `TestcontainersConfiguration` starts LocalStack with `sqs` only and the existing init script mounted; queue attributes are not restated in Java.
- [ ] A `@SpringBootTest` importing `TestcontainersConfiguration` can reach the `deliveries` queue without declaring a container of its own — proven by an existing or trivially added smoke assertion, not asserted by inspection.
- [ ] `./gradlew build` passes.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A03** is the operative category: one new runtime dependency, pinned, no dynamic version, no additional SDK modules. **A04:** the only credentials written are LocalStack's `test`/`test` no-op pair, and they stay out of `application.yaml`.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
