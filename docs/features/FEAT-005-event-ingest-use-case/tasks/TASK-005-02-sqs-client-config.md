---
id: TASK-005-02
feature: FEAT-005
title: "SqsClient bean and challenge.sqs properties binding"
status: Ready for Review
agent: devops-engineer
depends_on: [TASK-005-01]
date: 2026-09-20
---

# TASK-005-02: `SqsClient` bean and `challenge.sqs` configuration

## Feature

FEAT-005

## Assigned Agent

`devops-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/messaging/config/SqsProperties.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/out/messaging/config/SqsClientConfig.java` (new)
- Concern: binding the already-declared `challenge.sqs.*` keys and building one `SqsClient`.

**Bind to the key names that already exist.** FEAT-001 forward-declared them in `application-local.yaml` precisely so this task would not rename them: `challenge.sqs.endpoint`, `challenge.sqs.region`, `challenge.sqs.credentials.access-key`, `challenge.sqs.credentials.secret-key`, `challenge.sqs.queues.deliveries`, `challenge.sqs.queues.deliveries-dlq`. Do not invent new keys and do not edit `application-local.yaml`.

`SqsProperties` is a `@ConfigurationProperties("challenge.sqs")` record (or immutable class) — `endpoint` and `credentials` are `Optional`/nullable, because outside `local` the SDK resolves the endpoint from the region and the credentials from the IAM role chain, which is the production path (ADR-002 §1.1 Q10: the platform already runs on AWS). `region` and `queues.deliveries` are required.

`SqsClientConfig` builds **one** `SqsClient` bean: the synchronous client, never `SqsAsyncClient` — this codebase is blocking on virtual threads and a reactive/async client would introduce a second concurrency model for one call site. Apply the endpoint override and static credentials **only when present**; when absent, build the client with the SDK defaults so the IAM role chain applies untouched.

Note the virtual-thread property in a class comment: the sync client's blocking HTTP I/O unmounts a virtual thread correctly, which is why it is the right choice here and why no caller may wrap it in `synchronized`.

## Out of Scope

- Any `NotificationQueuePort` implementation, queue-URL resolution, or `SendMessage` call. TASK-005-11.
- `application.yaml` and `application-local.yaml`. Neither is edited by this task.
- A `SqsAsyncClient`, a custom HTTP client, a retry policy override, or connection-pool tuning. The SDK defaults stand until something measured says otherwise (YAGNI).
- DLQ consumption. The `deliveries-dlq` key is bound because it already exists in the file; nothing in this feature reads it.

## Acceptance Criteria

- [ ] `SqsProperties` binds the six existing keys with no renames and no new keys.
- [ ] `region` and `queues.deliveries` are validated as required; a missing one fails fast at startup with a message naming the key.
- [ ] Exactly one `SqsClient` bean; no `SqsAsyncClient` anywhere.
- [ ] Endpoint override and static credentials are applied only when configured; with neither set, the client is built on SDK defaults.
- [ ] No credential value is logged, and none is added to `application.yaml`.
- [ ] Tests written and passing: a context test asserting the bean exists and the properties bind under the `local` profile.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A04:** real credentials never enter a checked-in file; the `local` values are LocalStack's no-op pair. **A02:** the production path is the IAM role chain by omission, not by a hardcoded fallback.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
