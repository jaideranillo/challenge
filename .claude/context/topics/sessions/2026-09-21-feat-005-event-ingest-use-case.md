# Session: 2026-09-21 FEAT-005 event ingest use case

**Date:** 2026-09-21 00:05
**Topics:** backend, database, infra, errors

## Work Completed

### Files Modified/Created
- `docs/features/FEAT-005-event-ingest-use-case/` — 17 tasks (TASK-005-01 through 17), all `Ready for Review`. Implements ADR-002 §1.1, ADR-003 §2, ADR-003 Amendment A5, ADR-002 Amendment C3.
- `application/port/out/persistence/NotificationEventRepositoryPort` (new), `application/port/out/tracing/TraceContextPort` (new), `DeliveryPipelineRepositoryPort` gains `insertIfAbsent`/`findLiveByEventAndSubscription`.
- `adapter/out/persistence/NotificationEventJdbcRepository`, `DeliveryPipelineJdbcRepository` extended, `adapter/out/tracing/MicrometerTraceContextAdapter`, `adapter/out/messaging/SqsNotificationQueueAdapter` + `dto/NotificationEnvelope`.
- `application/usecase/RegisterNotificationEventUseCaseImpl`, `IngestPublishDispatcher`.
- `adapter/in/web/ingest/EventIngestController` + request/response DTOs — first HTTP-in adapter in the codebase.
- `src/test/java/.../TestcontainersConfiguration.java` — added `LocalStackContainer` + `challenge.sqs.*` `DynamicPropertyRegistrar` (no `@ServiceConnection` for SQS exists without Spring Cloud AWS).
- 414 tests total, verified with `./gradlew test --rerun` (not cache), 0 failures/errors.

### Problems Solved
1. **`TestcontainersConfiguration`'s SQS property registrar was missing `challenge.sqs.queues.*` keys**, only registering `endpoint`/`region`/`credentials`. `SqsProperties.queues` is `@NotNull`, so every `@SpringBootTest` without the `local` profile failed Spring context load (167/384 tests repo-wide) the moment `SqsClientConfig` became a real bean. Fixed by adding `challenge.sqs.queues.deliveries` / `.deliveries-dlq` to the registrar (queue names come from `docker/localstack/init-sqs.sh`, hardcoded `"deliveries"`/`"deliveries-dlq"` — matches the `local` profile's own `application-local.yaml` values). General lesson: a `DynamicPropertyRegistrar` for a new adapter's config must register every `@NotNull`/required property the adapter's own `@ConfigurationProperties` class demands, not just the ones the container itself exposes (queue names aren't a container property, they're an application concern, so it's easy to miss them).
2. **False duplicate-agent alarm.** Two backend-engineer agent dispatches for tasks 10-12 appeared to exist simultaneously (`a060b66b...` and `a657ed6d...`), triggering the standing "never let two agents touch the same directory concurrently" concern from FEAT-004. Investigation showed no real collision: `a060b66b`'s "completed, Ready for Review" self-report never actually landed on disk (task files still said `Not Started`, files didn't exist) — likely the coordinating session's context was compacted mid-flight and the completion notification was lost before being acted on, then a second dispatch for the same tasks was issued into what looked like a stale slot. `a657ed6d` was the only agent doing real work. General lesson: **always verify an agent's "completed"/"Ready for Review" claim against actual file contents and task-file `status:` fields before trusting it or dispatching a duplicate** — this is the same lesson as FEAT-004's stash-race incident, but the failure mode here was a lost/late notification rather than a stash collision. Also: `TASK-005-04`'s task file was left at `status: In Progress` even though the implementing agent reported it "Ready for Review" and the work was correct — caught only by grepping every task file's status line before reporting completion to the user, not by any agent's self-report.
3. **Task sequencing break is expected, not a defect, for port-then-adapter task pairs.** `TASK-005-04` (port interface) broke the full-module compile until `TASK-005-08` (its Postgres adapter) landed — normal for a hexagonal port/adapter split across two tasks assigned to different agents (`backend-engineer` then `dba`), not something to route back to the architect.

### Technical Decisions
- Auth between producer and ingest gateway explicitly cut from FEAT-005's scope by user directive mid-session (task 16/security-engineer's original auth task deleted by the architect, not deferred — renumbering cascaded through the task list). Recorded in `docs/concerns.md` as an open, unmitigated exposure (A07/A01) that must close before the delivery worker lands or before any untrusted-reachable deployment — do not let this get lost.
- Rest of the port/contract decisions are recorded in `docs/features/FEAT-005-event-ingest-use-case/feature.md` and ADR-002 Amendment C3 / ADR-003 Amendment A5 — not restated here.
- Flagged for user attention (not yet resolved): `RegisterNotificationEventUseCaseImpl` got `@Service` added mid-implementation so Spring could wire `IngestPublishDispatcher` into it, even though its task's acceptance criteria said `@Transactional` should be the only framework annotation in that file. Needs a decision: accept `@Service` there, or move wiring to an explicit `@Bean` method instead.

## Status at End
- Completed: FEAT-005 all 17 tasks `Ready for Review`; `./gradlew test --rerun` green, 414 tests, 0 failures/errors, verified fresh (not from cache).
- Nothing committed to git. User reviews/commits manually.

## Notes for Next Session
- Ingest endpoint (`POST /internal/events`) ships with no authentication by explicit user scope cut — flag this again before any deployment beyond local/demo, and before the delivery worker (which turns a persisted delivery row into a signed HTTPS POST to a client's registered URL) is built.
- `@Service` annotation question on `RegisterNotificationEventUseCaseImpl` is still open — resolve before merge if it matters to the reviewer.
- Concurrency/self-report lesson from problem 2 above extends the FEAT-004 lesson: verify against disk state, and specifically check task-file `status:` fields directly (grep) before reporting a feature complete — don't rely on an agent's final message text alone, even when it explicitly says "confirmed."
