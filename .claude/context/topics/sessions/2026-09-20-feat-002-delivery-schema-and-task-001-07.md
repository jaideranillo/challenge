# Session: 2026-09-20 TASK-001-07 + FEAT-002 delivery schema (partitioning added then reverted)

**Date:** 2026-09-20 22:00
**Topics:** backend, database, infra, decisions

## Work Completed

### Files Modified/Created
- `docs/features/FEAT-001-local-dev-environment/tasks/TASK-001-07-sqs-queue-config-regression-test.md` — created and implemented. `Ready for Review`.
- `src/test/java/com/cobre/challenge/adapter/out/messaging/SqsQueueConfigurationTest.java` — new. Testcontainers `LocalStackContainer` running the real `init-sqs.sh`; asserts real SQS attributes (`VisibilityTimeout`, `ReceiveMessageWaitTimeSeconds`, `RedrivePolicy.maxReceiveCount`, DLQ ARN) via `GetQueueAttributes`, not script exit code. Plus idempotency re-run test.
- `docs/features/FEAT-002-webhook-notification-delivery/` — new feature (ADR-003 §3 data model). `feature.md` + `TASK-002-01` through `07`.
- `docs/architecture/adr/ADR-001` through `ADR-006` — fixed stale `FEAT-001-webhook-notification-delivery` references (naming collision, see below) to `FEAT-002-webhook-notification-delivery`.
- `docs/architecture/adr/ADR-003-delivery-data-model-state-machine-and-idempotency.md` §3 — amended: partitioning is deferred (not implemented), recorded as a future performance improvement; the §2 idempotency invariant is a native partial unique index directly on `deliveries`, no side table/trigger.
- `build.gradle` — added `spring-boot-starter-flyway` + `flyway-core` + `flyway-database-postgresql` (test/main), `testcontainers-localstack`, `software.amazon.awssdk:sqs:2.27.8`.
- `src/main/resources/application.yaml` — `spring.flyway.enabled/locations/baseline-on-migrate`.
- `src/main/resources/db/migration/V1__enums_notification_events_subscriptions.sql`, `V2__deliveries.sql`, `V3__delivery_attempts.sql` — final schema, unpartitioned, all FKs, native partial unique index `idx_deliveries_live_pair`, `idx_deliveries_due` per ADR-002 §2.1.
- `src/test/java/com/cobre/challenge/schema/DeliveryIdempotencyIndexTest.java`, `DeliveryDueQueryIndexTest.java` — Testcontainers-Postgres, assert partial unique index rejection/acceptance across all statuses and `EXPLAIN` uses `idx_deliveries_due` by name with no seq scan.
- `TestcontainersConfiguration` — widened `class` → `public class` (needed for cross-package `@Import` from `schema` test package).

### Problems Solved
1. **TASK-001-07 existed only as a discussed idea from the prior session, never created.** Created the task file and dispatched it this session.
2. **`FEAT-001` naming collision.** ADR-003's "Downstream" section pointed at `docs/features/FEAT-001-webhook-notification-delivery/`, but `FEAT-001` was already claimed by the (already-committed) local-dev-environment feature. Renumbered to `FEAT-002` and fixed the stale reference in all six ADRs.
3. **Postgres partition-key/unique-index conflict.** `deliveries` partitioned monthly on `created_at` collides with the ADR-003 §2 partial unique index on `(event_id, subscription_id)` — Postgres requires a partitioned table's unique index to include the partition key. First resolution: side table `deliveries_live_index` + maintenance trigger. **User reversed this decision** (unnecessary complexity at this data volume) — partitioning fully removed, native partial unique index restored directly on `deliveries`, all FKs restored (`delivery_id` single-column PK again). ADR-003 §3 records partitioning as deferred/future work.
4. **Two `software-architect` subagents briefly ran concurrently on the same files** during the partitioning-reversal follow-up (spawned a fresh agent instead of resuming the in-flight one via `SendMessage`). Caught via `ListAgents`, stopped the duplicate before it touched anything.
5. **Spring Boot 4.1.1 Jackson package moved**: `tools.jackson.databind`, not `com.fasterxml.jackson.databind`. Affected test code parsing JSON (`RedrivePolicy`, `EXPLAIN` plan output).
6. **`spring-boot-starter-flyway` is required** on Spring Boot 4, not just `flyway-core`/`flyway-database-postgresql` — without the starter, autoconfiguration never runs Flyway (silent no-op, app boots fine, migrations never applied).
7. **Testcontainers-Postgres tests never touch the local dev Postgres** (`compose.yaml`'s `challenge-postgres-1`) — that's a separate, persistent DB that only gets migrated by actually running `./gradlew bootRun`. Verified by hand: `\dt` on the local Postgres showed zero tables until `bootRun` was run once.

### Technical Decisions
- ADR-003 §3 partitioning deferred — see ADR text, not restated here.
- No `recovered_from` column — `replayed_from` is reused for both `REPLAY` and `RECOVERED` origins (ADR-005 §1 overrides ADR-003 §1.1's mention).
- `TASK-002-06` (partition retention) kept as a file marked `Deferred`, not deleted — historical record of why partitioning was considered and dropped.
- `TASK-002-01` (Flyway bootstrap) assigned to `devops-engineer`, not `dba` — Gradle/Spring config only, zero SQL.

## Status at End
- Completed: FEAT-001 TASK-001-07 and FEAT-002 TASK-002-01/02/03/04/05/07 all `Ready for Review`. TASK-002-06 `Deferred`.
- Local dev Postgres (`compose.yaml`) migrated by hand via one `bootRun` run, then the app was stopped again — confirmed 5 tables present (`notification_events`, `subscriptions`, `deliveries`, `delivery_attempts`, `flyway_schema_history`).
- Nothing committed to git across either feature. User reviews/commits manually.

## Notes for Next Session
- If continuing FEAT-002: no domain/use-case/persistence-adapter Java code exists yet — schema only. Next natural step is `application/port` + `adapter/out/persistence` (Spring Data JDBC) per CLAUDE.md's hexagonal layout.
- `TASK-002-06`'s deferred partitioning content is a ready-made starting point if/when partitioning is revisited (would need a superseding ADR per ADR-003 §3's new text, not just a task re-open).
