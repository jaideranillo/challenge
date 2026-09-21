---
id: TASK-007-04
feature: FEAT-007
title: Queue attributes, redrive policy and DLQ configuration keys
status: Ready for Review
agent: devops-engineer
depends_on: [TASK-007-02]
date: 2026-09-21
---

# TASK-007-04: Queue attributes, redrive policy and DLQ configuration

## Feature

FEAT-007

## Assigned Agent

`devops-engineer`

## Scope

- File(s):
  - `docker/localstack/init-sqs.sh` (modified, only if it falls short of the table below)
  - `src/main/resources/application.yaml` (modified)
  - `src/main/resources/application-local.yaml` (modified, only if a key is missing)
- Concern: the queue-side settings the worker depends on are correct, complete and identical in
  every wiring path. Configuration only.

### The settings that must hold (ADR-006 §1.1)

| Setting | Value | Applies to |
|---|---|---|
| `VisibilityTimeout` | `30` | `deliveries` |
| `ReceiveMessageWaitTimeSeconds` | `20` | `deliveries` **and** `deliveries-dlq` |
| `RedrivePolicy.maxReceiveCount` | `3` | `deliveries`, targeting `deliveries-dlq`'s ARN |

**Audit first, change second.** The merged `init-sqs.sh` already creates both queues, resolves the
DLQ ARN before the main queue exists, and sets all three of the main queue's attributes. Verify it
line by line against the table before editing anything. The one gap to look for is the **DLQ's own**
`ReceiveMessageWaitTimeSeconds`: the DLQ consumer (TASK-007-18) long-polls it exactly like the main
queue, and a DLQ created with the default `0` makes that a hot spin loop. If it is missing, add it
on the DLQ's `create-queue`; the script's re-run idempotency note must stay true afterwards.

`compose.yaml` mounts this script and `TestcontainersConfiguration` copies the same file into the
LocalStack container, so one edit covers both wiring paths. **Do not fork the script or add a
second copy for tests.**

### Configuration keys

`challenge.sqs.queues.deliveries-dlq` already exists and is already registered in
`TestcontainersConfiguration`. Confirm it is also present in `application-local.yaml` (it is) and
add nothing new to `SqsProperties` — the DLQ consumer resolves its queue URL from the existing key.

In `application.yaml`, add only the `challenge.worker.enabled` line if TASK-007-02 did not already
place it, and leave every other worker key where TASK-007-02 put it.

## Out of Scope

- `TestcontainersConfiguration` and any test file. Integration wiring is the deferred phase.
- Any Java file, including `SqsProperties`.
- Creating, renaming or deleting a queue.
- The DLQ consumer itself (TASK-007-18).

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker. **This task adds no test.** Proving the redrive policy actually moves a poison message
after three receives requires LocalStack and is explicitly deferred to the later integration phase
(see `feature.md`, "Deferred to a later phase"). Do not start a container to check the script.

**Do not run `./gradlew test` or `./gradlew build`.**

## Acceptance Criteria

- [ ] `deliveries` carries `VisibilityTimeout=30`, `ReceiveMessageWaitTimeSeconds=20` and a
      redrive policy with `maxReceiveCount=3` targeting `deliveries-dlq`'s ARN.
- [ ] `deliveries-dlq` carries `ReceiveMessageWaitTimeSeconds=20`.
- [ ] The script remains idempotent on re-run and still creates the DLQ before the main queue.
- [ ] One script serves both `compose.yaml` and `TestcontainersConfiguration`; no copy is forked.
- [ ] No new key is added to `SqsProperties`.
- [ ] A short handover note states, per setting, whether it was already correct or was changed.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer).

## Definition of Done

Change written. **Do not run `git add` or `git commit`.** Set this task's `status` to
`Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

Handover note, per setting:

- `deliveries` `VisibilityTimeout=30`: already correct, no change.
- `deliveries` `ReceiveMessageWaitTimeSeconds=20`: already correct, no change.
- `deliveries` `RedrivePolicy.maxReceiveCount=3` targeting `deliveries-dlq`'s ARN: already correct, no change.
- `deliveries-dlq` `ReceiveMessageWaitTimeSeconds=20`: was missing (DLQ `create-queue` had no
  `--attributes` at all, so it defaulted to `0`, i.e. a hot spin loop for TASK-007-18's long-poll
  consumer). Added `--attributes '{"ReceiveMessageWaitTimeSeconds":"20"}'` to the DLQ's
  `create-queue` call in `docker/localstack/init-sqs.sh`. Script remains idempotent (same
  attributes on every re-run) and still creates the DLQ before the main queue.
- `challenge.worker.enabled` in `application.yaml`: already present (TASK-007-02), no change.
- `challenge.sqs.queues.deliveries-dlq` in `application-local.yaml`: already present, no change.
- No key added to `SqsProperties`.
- One script still serves both `compose.yaml` and `TestcontainersConfiguration`; no fork.
