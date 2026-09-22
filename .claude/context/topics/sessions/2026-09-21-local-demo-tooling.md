# Session: 2026-09-21 Local demo tooling — event generator, ingest bypass, subscription seed, Makefile

**Date:** 2026-09-21
**Topics:** backend, infra, security, database

## Work Completed

### Files Modified
- `adapter/in/web/local/eventgenerator/EventGeneratorController.java` — new. `POST /local/event-generator/generate`, body `{"count": N}` (1-1000). Cycles the 10 event_type/content templates from `docs/challenge/notification_events.json`, random CLIENT001/002/003, routes each event through a real HTTP `POST /internal/events` call (via `RestClient`) instead of calling the use case in process — exercises the real gateway-ingest step (ADR-002 §1.1 step 1) for demo purposes.
- `adapter/in/web/local/eventgenerator/LocalEventGeneratorSecurityConfig.java` — new. `local`-profile-only, `@Order(5)`, permit-all on `/local/event-generator/**`.
- `adapter/in/web/local/eventgenerator/LocalIngestBypassSecurityConfig.java` — new. `local`-profile-only, `@Order(0)` (ahead of `SecurityConfig`'s `@Order(2)` ingest chain), permit-all on `/internal/events/**`. DEV-ONLY bypass — real producer auth is SigV4 (ADR-002 Q10), not yet implemented; this is not a stand-in for it.
- `adapter/in/web/local/eventgenerator/dto/{GenerateEventsRequest,GenerateEventsResponse}.java` — new DTOs.
- `adapter/in/web/security/config/SecurityConfig.java` — javadoc updated to document the local-only order-0/4/5 chains defined elsewhere.
- `tools/insomnia/challenge-collection.json` — added 3 folders: Event Generator, Gateway Ingest (`/internal/events`, now usable directly under local thanks to the bypass), Self-Service API (list/get/replay, needs `jwt_token` env var). Added `jwt_token` to the Local environment.
- `tools/dev-seed/seed-subscriptions.sql` + `.sh` — new. Seeds subscriptions (not exposed via any CRUD API, ADR-003 §3 scope cut) so generated events have a matching, active subscription and produce `deliveries` rows. CLIENT001 has 2 subscriptions (credit_*/debit_* split) to demo per-client fan-out to multiple targets; CLIENT002/003 have 1 each covering all 10 event types. Fixed UUIDs + `ON CONFLICT DO UPDATE` — safe to re-run.
- `Makefile` — new. `make up` (compose up + bootRun + wait-health + seed), `down`, `restart`, `logs`, `seed`, `status`, `test`, `build`, `clean`. `.run/app.log`/`.run/app.pid` gitignored.
- `.gitignore` — added `.run/`.

### Problems Solved
1. **`RestClient.Builder` not injectable** — `EventGeneratorController(RestClient.Builder ...)` failed at startup (`UnsatisfiedDependencyException`, no qualifying bean). Fix: construct `RestClient.builder()` directly in the constructor instead of relying on Boot's `RestClientAutoConfiguration` bean.
2. **`bootRun` failed with SQS `ConnectException`** when only the `postgres` container was started manually beforehand (`docker compose up -d postgres`) — the app still needs `localstack` (SQS) and it wasn't up. Fix: bring up the full compose stack (`docker compose up -d`, no service filter) before `bootRun`, or let `spring-boot-docker-compose` start everything itself with no partial pre-start.
3. **`/internal/events` unreachable in every profile** — `SecurityConfig`'s chain 2 (`@Order(2)`, matcher `/internal/**`) sets `anyRequest().authenticated()` with no auth mechanism wired (SigV4 follow-up not implemented), so every request 401/403s including from a real producer. Confirmed live. Worked around for local demo only via `LocalIngestBypassSecurityConfig` at `@Order(0)`, narrower matcher `/internal/events/**` (Spring Security picks the first chain whose matcher matches, evaluated by `@Order`).
4. **Deliveries fail closed on unresolved secret** — `AttemptDeliveryUseCaseImpl` calls `WebhookSecretPort.resolve(subscription.secretRef())`; if it returns empty (no `challenge.webhook.secrets.<ref>` configured), it logs `"Secret unresolved; failing closed"` and never sends the HTTP call. `application-local.yaml` ships no default — must export `CHALLENGE_WEBHOOK_SECRETS_<REF>` (e.g. `CHALLENGE_WEBHOOK_SECRETS_DEMO`) before `bootRun` for any seeded subscription using `secret_ref='demo'`.

## Status at End
- Completed: event generator + ingest bypass verified live end-to-end (curl: direct `/internal/events` now `202`, generator `200` with real HTTP calls through it). Subscription seed script written but not yet run against a live DB in this session (SQL/shape reviewed, not executed). Makefile written, only dry-run (`make -n up`) checked, not a real `make up`.
- Pending: full retry-simulation demo (webhook stub force-500/429/400/hang → observe `delivery_attempts`) discussed and documented in conversation but not executed live this session. Observability (logs→Loki gap) — user will plan separately, not started.

## Notes for Next Session
- Metrics and traces already flow to Grafana (`grafana-lgtm`, `localhost:3000`) automatically via `spring-boot-starter-opentelemetry` + Docker Compose service connection (confirmed live: `Publishing metrics for OtlpMeterRegistry ... /v1/metrics`). **Logs do not** — no `logback`/`management.otlp.logging.export` config exists anywhere in `application*.yaml`. This is the real observability gap, not metrics/traces as the user initially assumed.
- Fast way to check if an event reached SQS without any observability tooling: `docker exec -it $(docker ps -qf name=localstack) awslocal sqs get-queue-attributes --queue-url http://localhost:4566/000000000000/deliveries --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible --region us-east-1`. Queue names: `deliveries` / DLQ `deliveries-dlq` (from `docker/localstack/init-sqs.sh`).
- All of this session's work (generator, bypass, seed, Makefile) was explicitly scoped by the user as dev/demo tooling, outside the mandatory ADR→Feature→Task delivery workflow (CLAUDE.md) — user instructed to skip that flow for this class of task.
