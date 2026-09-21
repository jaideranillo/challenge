---
id: TASK-001-07
feature: FEAT-001
title: Testcontainers regression test asserting deliveries/deliveries-dlq queue attributes
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-001-01]
date: 2026-09-20
---

# TASK-001-07: Testcontainers regression test asserting deliveries/deliveries-dlq queue attributes

## Feature

FEAT-001 — Local development environment

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

Add an automated test that proves the SQS queue configuration produced by `docker/localstack/init-sqs.sh` matches ADR-006 §1.1, using Testcontainers' LocalStack module (not the compose stack — CLAUDE.md: tests use Testcontainers).

- File(s):
  - New test class under `src/test/java` (e.g. `adapter/out/messaging/SqsQueueConfigurationTest.java` or similar — place per existing package hygiene rules).
  - `TestcontainersConfiguration` (modified only if a shared LocalStack container bean is needed).
- Concern: proving queue *attributes* exist as configured. Nothing else.

### What must be asserted

The test must query queue attributes via the AWS SDK SQS client (already a transitive dependency via Spring Cloud AWS / AWS SDK, or add the SQS client if not present) against a LocalStack Testcontainer that runs the same `docker/localstack/init-sqs.sh` script (mount it the same way compose does, or invoke it against the container after startup).

Required assertions, from ADR-006 §1.1 — the point of this task is that these are read back as actual queue attributes, not inferred from the init script exiting 0:

- `deliveries` and `deliveries-dlq` both exist (`ListQueues` or direct `GetQueueUrl`).
- `deliveries` attribute `VisibilityTimeout` == `30`.
- `deliveries` attribute `ReceiveMessageWaitTimeSeconds` == `20`.
- `deliveries` attribute `RedrivePolicy` parses to `maxReceiveCount == 3` and `deadLetterTargetArn` equal to `deliveries-dlq`'s actual ARN (read back, not hardcoded to the LocalStack default account/region string).
- Re-running the init script against the already-provisioned container does not change any of the above (idempotency), and does not throw.

## Out of Scope

- `compose.yaml` — untouched, tests don't use it (CLAUDE.md).
- Any SQS producer/consumer application code — no relay/worker exists yet (ADR-002 future work).
- Receive batch size — consumer-side parameter, not a queue attribute, out of scope per TASK-001-01.
- Changing `init-sqs.sh` itself unless the test finds a genuine drift from ADR-006 §1.1 (report a discrepancy rather than silently editing the script's intent).

## Acceptance Criteria

- [ ] Test runs under `./gradlew test`, no Docker Compose dependency, no manual steps.
- [ ] Test fails if `VisibilityTimeout`, `ReceiveMessageWaitTimeSeconds`, `maxReceiveCount`, or the DLQ target ARN drift from ADR-006 §1.1 values.
- [ ] Test asserts against real queue attributes returned by the LocalStack container's SQS API — not against the init script's exit code or log output.
- [ ] Idempotency covered: script run twice against the same container, attributes unchanged, no error.
- [ ] Follows SOLID / YAGNI / Effective Java rules and package hygiene rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure (dummy test credentials only, no real AWS account/keys).

## Definition of Done

Test written, passes locally (`./gradlew test --tests "..."`). **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

## Completion note (backend-engineer)

**Test**: `src/test/java/com/cobre/challenge/adapter/out/messaging/SqsQueueConfigurationTest.java`.

Uses Testcontainers' `LocalStackContainer` (`localstack/localstack:4.4.0`, matching the pin from TASK-001-01, `SERVICES=sqs`), with `docker/localstack/init-sqs.sh` copied via `withCopyFileToContainer` into `/etc/localstack/init/ready.d/init-sqs.sh` — the same ready-hook path `compose.yaml` mounts it into, so the container runs the identical script LocalStack runs in local dev, not a copy or a reimplementation. `waitingFor(Wait.forLogMessage(".*Ready\\.\n", 1))` (built into `LocalStackContainer`) blocks container startup until the ready hooks, including this script, have finished.

No shared bean was added to `TestcontainersConfiguration` — this container is not needed by any `@SpringBootTest` (no producer/consumer exists yet), so it is declared as a plain `@Testcontainers`/`@Container` static field local to the test class, per YAGNI.

Both assertions read attributes back through the AWS SDK v2 `SqsClient` (`GetQueueUrl` + `GetQueueAttributes`) against the running container's real SQS API — nothing is asserted from the init script's exit code or logs:

- `deliveriesQueueMatchesAdr006Section1_1()`: resolves `deliveries-dlq`'s real ARN via `GetQueueAttributes(QUEUE_ARN)`, then asserts `deliveries`' `VisibilityTimeout == "30"`, `ReceiveMessageWaitTimeSeconds == "20"`, and parses the `RedrivePolicy` JSON string to assert `maxReceiveCount == 3` and `deadLetterTargetArn` equals that same real ARN (never a hardcoded LocalStack account/region string).
- `reRunningInitScriptIsIdempotent()`: reads `deliveries`' attributes, re-execs the init script inside the already-provisioned container via `execInContainer("bash", ".../init-sqs.sh")`, asserts exit code 0, then reads the attributes again and asserts the map is unchanged.

`RedrivePolicy` is parsed with two small regexes rather than a JSON library: Spring Boot 4.1.1 moved to Jackson 3 (`tools.jackson.databind`), and pulling in a databind dependency (or coupling to Boot's internal Jackson variant) just to read two flat string/int fields out of a known single-line JSON blob was more machinery than the assertion needs.

**Dependencies added** (`build.gradle`, testImplementation only): `org.testcontainers:testcontainers-localstack` (version aligned to the `testcontainers-postgresql`/`testcontainers-grafana` modules already in use, resolved transitively to `2.0.5` via Spring Boot's dependency management) and `software.amazon.awssdk:sqs:2.27.8` (pinned explicitly — Spring Boot 4.1.1 does not import the AWS SDK BOM, so this is the only AWS SDK artifact in the project and its version was picked to match the `software.amazon.awssdk:bom` version already present transitively in the dependency graph).

**No drift found**: `init-sqs.sh` was not modified. Its values match ADR-006 §1.1 exactly as read back through the SQS API.

**Test run**: `./gradlew test --tests "com.cobre.challenge.adapter.out.messaging.SqsQueueConfigurationTest"` — 2 tests, 0 failures. Full suite (`./gradlew test`) also green, no regressions. No Docker Compose involved; only Testcontainers, per CLAUDE.md.
