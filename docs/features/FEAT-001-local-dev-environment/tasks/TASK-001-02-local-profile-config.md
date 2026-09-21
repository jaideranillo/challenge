---
id: TASK-001-02
feature: FEAT-001
title: Add the `local` Spring profile — SQS endpoint override, dummy credentials, compressed timings
status: Ready for Review
agent: devops-engineer
depends_on: [TASK-001-01]
date: 2026-09-20
---

# TASK-001-02: Add the `local` Spring profile

## Feature

FEAT-001 — Local development environment

## Assigned Agent

`devops-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

Add a `local` Spring profile that points the app at the LocalStack SQS queues created in TASK-001-01 and compresses the retry/poll timings so a full retry cycle fits inside a live demo.

- File(s):
  - `src/main/resources/application-local.yaml` (new) — the entire `local` profile
  - `src/main/resources/application.yaml` (modified, minimal) — only if `spring.threads.virtual.enabled: true` is not already set; do not put any `local` value here
  - `build.gradle` (**verify only, no edit**) — see below
- Concern: local-profile configuration. Nothing else.

### `build.gradle` — verify, do not modify

`developmentOnly 'org.springframework.boot:spring-boot-docker-compose'` is **already present at `build.gradle:28`**, confirmed against the working tree on 2026-09-20. Confirm it is still there and record that in the completion note. **Do not re-add it, do not reorder the dependency block, do not add any other dependency in this task.** An AWS SDK / Spring Cloud AWS dependency is not added here — no code consumes SQS yet, and picking that library is a decision for the queue-adapter feature, not for this one.

### `application-local.yaml` contents

**1. SQS endpoint override and dummy credentials**

- Endpoint pointing at the LocalStack edge port published in TASK-001-01
- Region: any fixed value (`us-east-1` is conventional for LocalStack)
- Access key / secret key: LocalStack's no-op placeholders (`test` / `test`). These authenticate to nothing and grant nothing; they exist because the AWS SDK refuses to sign a request without a credential present.
- Queue names `deliveries` and `deliveries-dlq`, matching TASK-001-01 exactly. If that task chose different names, use those.

Because no SQS client library is on the classpath yet, express these as `challenge.*`-namespaced application properties with self-evident key names, not as vendor-specific keys of a library that is not present. Whoever adds the queue adapter maps them, or renames them once with a real binding in front of them — a key that binds to nothing is better than a key that binds to the wrong library's namespace.

**2. Compressed timings — `local` profile only**

| Key | `local` value | Overridden production value | Source |
|---|---|---|---|
| `challenge.retry.backoff` | `2s, 5s, 10s` | `5s, 30s, 2m, 10m, 1h, 6h` (±20% jitter) | ADR-004 §1 |
| `challenge.relay.poll-interval` | `2s` | `5s` | ADR-002 §2.1 |

Both keys are **forward declarations**: no code binds them yet (the relay and the `RetryPolicy` do not exist). Their purpose is that the later implementers bind a name already agreed rather than inventing one. ADR-004 §1 already specifies the backoff schedule as a `@ConfigurationProperties`-bound value read once at startup, so a per-profile override is the mechanism that ADR anticipated.

Do **not** override the ±20% jitter — only the interval list changes.

**3. A comment block at the top of the file** stating, in one short paragraph: this profile is for local development and live demo only; its timings deliberately diverge from ADR-004 §1 and ADR-002 §2.1; the production numbers in those ADRs remain authoritative. Without this, the compressed numbers will eventually be read as the real ones.

### What must stay out of `application.yaml`

Every value above is `local`-only. `application.yaml` must not gain an SQS endpoint, a credential, or a compressed timing — a developer running without `-Dspring.profiles.active=local` must get no local-only behavior at all.

## Out of Scope

- `compose.yaml` and the init script — TASK-001-01 owns them.
- Adding any dependency to `build.gradle`, the AWS SDK and Spring Cloud AWS included.
- Any Java code, including the stub webhook receiver (TASK-001-03) and anything that reads these properties.
- Any Spring Security configuration or `SecurityConfig` class.
- A production or staging profile.
- `TestcontainersConfiguration` and anything under `src/test/java`.

## Acceptance Criteria

- [ ] `build.gradle` verified unchanged and `spring-boot-docker-compose` confirmed present at line 28; recorded in the completion note
- [ ] `application-local.yaml` exists and holds the SQS endpoint, region, dummy credentials, and both queue names, matching TASK-001-01's values exactly
- [ ] `challenge.retry.backoff` is `2s, 5s, 10s` and `challenge.relay.poll-interval` is `2s`, in `application-local.yaml` only
- [ ] `application.yaml` contains none of the above
- [ ] The divergence comment block naming ADR-004 §1 and ADR-002 §2.1 is present at the top of `application-local.yaml`
- [ ] `./gradlew build` passes, and the app starts under `--spring.profiles.active=local` with the compose stack up
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable). Specifically A04: the only credentials in the file are LocalStack's `test`/`test` no-op placeholders; no real AWS key, account id, or endpoint is committed

## Definition of Done

Configuration written and the app verified to start under the `local` profile. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

### Completion note

- `build.gradle` verified unchanged: `developmentOnly 'org.springframework.boot:spring-boot-docker-compose'` confirmed present at line 28. Nothing added, nothing reordered.
- `application-local.yaml` created with SQS endpoint `http://localhost:4566` (LocalStack edge port from `compose.yaml`), region `us-east-1`, dummy credentials `test`/`test`, and queue names `deliveries` / `deliveries-dlq` matching TASK-001-01's `init-sqs.sh` exactly.
- `challenge.retry.backoff: 2s, 5s, 10s` and `challenge.relay.poll-interval: 2s` set in `application-local.yaml` only, as forward-declared `challenge.*` properties (no binding class exists yet).
- `application.yaml` gained only `spring.threads.virtual.enabled: true` (was missing, required by the project's runtime model, not local-specific) — no SQS/credential/timing values added there.
- `./gradlew build` passes (Testcontainers-backed tests ran and the app context started/shut down cleanly).
