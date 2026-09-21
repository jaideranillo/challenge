---
id: TASK-001-01
feature: FEAT-001
title: Add LocalStack (SQS only) to compose.yaml with a queue init script
status: Ready for Review
agent: devops-engineer
depends_on: []
date: 2026-09-20
---

# TASK-001-01: Add LocalStack (SQS only) to compose.yaml with a queue init script

## Feature

FEAT-001 — Local development environment

## Assigned Agent

`devops-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

Add a LocalStack service to the existing Docker Compose stack, running **SQS and nothing else**, and an init script that creates the delivery queue and its DLQ with the ADR-006 §1.1 settings.

- File(s):
  - `compose.yaml` (modified) — add one `localstack` service alongside the existing `postgres` and `grafana-lgtm`
  - `docker/localstack/init-sqs.sh` (new) — mounted into LocalStack's init-hooks directory so it runs once the SQS service is ready
- Concern: local SQS infrastructure provisioning. Nothing else.

### LocalStack service requirements

- Image: LocalStack community image, pinned to an explicit tag rather than `latest`. The existing `postgres`/`grafana-lgtm` services use `latest`; do not change those, but do not add a third unpinned image — a queue whose semantics change under the developer is a debugging trap (OWASP A03, supply-chain).
- `SERVICES=sqs` — SQS only, per the requirement. No S3, no SNS, no Lambda.
- Expose the LocalStack edge port (`4566`). Publish it on a fixed host port so the `local` profile in TASK-001-02 can name a stable endpoint; the existing services' dynamic-port style does not work here, because the endpoint override is a plain URL string in a config file, not something `spring-boot-docker-compose` resolves.
- Mount `docker/localstack/init-sqs.sh` into LocalStack's ready-hook directory (`/etc/localstack/init/ready.d/`) so it runs after SQS is up, not before.
- Add a healthcheck so `spring-boot-docker-compose` waits for LocalStack before the app boots. Without it the app can start, fail its first SQS call, and look like a code bug.

### Queue configuration the init script must produce

All values from **ADR-006 §1.1** (`docs/architecture/adr/ADR-006-resilience-policies.md`), which is `Accepted`:

| Setting | Value | Applies to |
|---|---|---|
| Queue name | `deliveries` | main queue |
| DLQ name | `deliveries-dlq` | dead-letter queue |
| `VisibilityTimeout` | **30** (seconds) | main queue |
| `maxReceiveCount` | **3** | redrive policy on the main queue, targeting `deliveries-dlq` |
| `ReceiveMessageWaitTimeSeconds` | **20** | main queue (long poll) |

Notes on the two names: `deliveries-dlq` is stated verbatim in ADR-004 §1 and ADR-006 §1.1. The main queue name `deliveries` is **not** stated in any ADR — it is chosen here for consistency with the DLQ name and with the `deliveries` table. If you change it, change it in TASK-001-02 too; they must agree.

The DLQ must be created **first**, its ARN read back, and that ARN used in the main queue's `RedrivePolicy`. Creating the main queue first and patching the policy afterwards leaves a window where messages can exhaust receives with nowhere to go.

Receive batch size (10, ADR-006 §1.1) is a **consumer-side** parameter, not a queue attribute — do not attempt to set it here.

The script must be idempotent: running it twice (container restart, `compose up` on an existing volume) must not fail or produce a second pair of queues.

## Out of Scope

- `application.yaml`, `application-local.yaml`, or any Spring configuration — that is TASK-001-02.
- `build.gradle` — `spring-boot-docker-compose` is already present at line 28 and nothing in this task needs a dependency change.
- The existing `postgres` and `grafana-lgtm` services. Do not repin, reformat, or reorder them.
- `TestcontainersConfiguration` and anything under `src/test/java`. Tests do not use the compose stack (CLAUDE.md).
- Any Java code.
- Any LocalStack service other than SQS.

## Acceptance Criteria

- [ ] `docker compose up` brings up LocalStack alongside the existing services, with SQS as its only enabled service
- [ ] After startup, both `deliveries` and `deliveries-dlq` exist and can be listed through the LocalStack SQS endpoint
- [ ] `deliveries` reports `VisibilityTimeout=30`, `ReceiveMessageWaitTimeSeconds=20`, and a `RedrivePolicy` with `maxReceiveCount=3` pointing at the `deliveries-dlq` ARN
- [ ] Re-running the init script (or restarting the container) neither errors nor duplicates queues
- [ ] The LocalStack image is pinned to an explicit version tag
- [ ] The healthcheck gates app startup — the app does not boot against a LocalStack that has not finished initializing
- [ ] Verified by hand and the verification commands recorded in the task's completion note. There is no automated test for this task; the compose stack is not exercised by `./gradlew test`
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable). Specifically: no real AWS credential, account id, or endpoint appears anywhere in these files

## Definition of Done

Configuration written and manually verified locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

## Completion note (devops-engineer)

**Image tag deviation from the initial pin, flagged explicitly:** LocalStack changed its licensing on 2026-03-23 (calver `2026.3.0` onward) to merge the community and pro images into one, which now refuses to start without a `LOCALSTACK_AUTH_TOKEN` ("License activation failed... no credentials were found"). Verified by hand: `localstack/localstack:2026.08.0` exits with code 55 and never starts. This is not something the task file could have anticipated (dated before the license change). Pinned instead to `localstack/localstack:4.4.0`, the last pre-license-change release, which starts clean with no token and no account required. This keeps the "no real AWS credential, account id, or endpoint" acceptance criterion intact by construction (no LocalStack account needed either) and preserves the "pin to an explicit tag" requirement. Flagging this for security-engineer/architect awareness: any future re-pin of this image must stay off `latest`/`stable`/any post-`2026.3.0` tag unless a `LOCALSTACK_AUTH_TOKEN` secret-handling decision is made first.

**Verified locally** (`docker compose up -d localstack`, then `docker exec challenge-localstack-1 awslocal sqs ...`):
- Both `deliveries` and `deliveries-dlq` exist and list via the LocalStack SQS endpoint.
- `deliveries`: `VisibilityTimeout=30`, `ReceiveMessageWaitTimeSeconds=20`, `RedrivePolicy={"deadLetterTargetArn":"arn:aws:sqs:us-east-1:000000000000:deliveries-dlq","maxReceiveCount":"3"}`.
- DLQ created first (its ARN was read back and embedded in the main queue's `RedrivePolicy` before the main queue was created).
- Idempotency: re-ran `init-sqs.sh` inside the running container against the already-created queues (exit 0, no duplicate queues, no second pair) and separately restarted the container (came back healthy, same two queues, no error in logs).
- Healthcheck (`curl -f http://localhost:4566/_localstack/health`) gates the container's `healthy` state; `spring-boot-docker-compose` waits on container health before considering a service ready.
- `postgres` and `grafana-lgtm` services untouched: `git diff --stat compose.yaml` shows only the 15 lines added for the new `localstack` service.

Verification commands used:
```
docker compose up -d localstack
docker inspect --format='{{.State.Health.Status}}' challenge-localstack-1
docker exec challenge-localstack-1 awslocal sqs list-queues --region us-east-1
docker exec challenge-localstack-1 awslocal sqs get-queue-attributes --queue-url <url> --attribute-names VisibilityTimeout ReceiveMessageWaitTimeSeconds RedrivePolicy --region us-east-1
docker exec challenge-localstack-1 bash -c "bash /etc/localstack/init/ready.d/init-sqs.sh"   # idempotency re-run
docker restart challenge-localstack-1                                                        # idempotency across restart
docker compose down
```
