# Session: 2026-09-20 FEAT-001 local dev environment

**Date:** 2026-09-20 21:15
**Topics:** backend, infra, security, errors

## Work Completed

### Files Modified/Created
- `compose.yaml` — added `localstack` service (image `localstack/localstack:4.4.0`, `SERVICES=sqs`), mounts `docker/localstack/init-sqs.sh` as a ready-hook.
- `docker/localstack/init-sqs.sh` — new. Creates `deliveries` + `deliveries-dlq` per ADR-006 §1.1 (VisibilityTimeout 30s, maxReceiveCount 3 redrive, WaitTimeSeconds 20s). Idempotent.
- `src/main/resources/application-local.yaml` — new. SQS endpoint override, dummy AWS creds (`test`/`test`), compressed local timings (`challenge.retry.backoff: 2s,5s,10s`, `challenge.relay.poll-interval: 2s` — forward-declared config keys, no consumer code yet).
- `src/main/resources/application.yaml` — added `spring.threads.virtual.enabled: true`.
- `src/main/java/com/cobre/challenge/adapter/in/web/local/webhookstub/` — `LocalWebhookStubController`, `LocalWebhookStubRecorder`, `dto/RecordedRequest`, `behavior/ForcedBehavior`, `LocalWebhookStubSecurityConfig`. Stub webhook receiver, `@Profile("local")` only, records received requests, forced-status/forced-hang controls.
- `.claude/agents/backend-engineer.md` — added "Package & Class Hygiene" mandatory section: one type per file, thin controllers (no private helpers, delegate to collaborators), sub-divided adapter packages by concern, record/DTO placement by role (`model/` vs `dto/`), static factory methods (`from`/`to`/`of`, Effective Java Item 1) over public constructors for conversions.
- `CLAUDE.md` — added "Design authority" (ADRs are settled, implement against them, disagreements go to `docs/concerns.md`) and "Testing" (domain: plain JUnit no Spring context; adapters: Testcontainers against real Postgres/LocalStack, no H2, no mocked SQS) sections.
- `tools/insomnia/challenge-collection.json` — new. Insomnia v4 export, folder-organized by component, `Local` environment (`base_url=http://localhost:8080`).
- `docs/features/FEAT-001-local-dev-environment/` — feature.md + TASK-001-01 through 08 (compose/SQS, local profile, stub receiver, structure fixes x3, ResponseEntity consistency, local SecurityConfig). All 8 tasks `Ready for Review`, nothing committed to git.

### Problems Solved
1. **LocalStack images after 2026.3.0 require a paid `LOCALSTACK_AUTH_TOKEN`** (license gate, exits code 55 without one). Fixed by pinning to `localstack/localstack:4.4.0`, the last pre-license release.
2. **Defining any `@Bean SecurityFilterChain` makes Spring Boot's auto-configured default security chain back off entirely** — a single `@Order(1)` chain matched only to `/local/webhook-stub/**` left every other path with zero security enforcement (404 instead of 401 on unrelated paths, proven by a failing test). Fix: a second `@Order(2)` catch-all chain (`anyRequest().authenticated()` + HTTP Basic) replicating the default, in the same `@Profile("local")` config class. Both beans are required, not redundant.
3. **IntelliJ "Active profiles" field takes the bare profile name only** — pasting the full `--spring.profiles.active=local` flag into it produces a literal (invalid) profile named `--spring.profiles.active=local` and `SpringApplication` fails fast (`ProfilesValidator`). Field wants just `local`.
4. **Stub receiver logs nothing to the console on purpose** (OWASP A09 constraint from TASK-001-03, not a bug) — the only way to see recorded requests is `GET /local/webhook-stub/requests`.
5. **OTLP `ConnectException` noise on every metrics-push interval** when `grafana-lgtm` isn't running (e.g. port 3000 already taken by another local process) — harmless, app functions normally without it.
6. **Real `aws` CLI vs `awslocal`**: plain `aws` uses real AWS credentials/profile (hit `ExpiredToken` against real endpoints) unless given `--endpoint-url http://localhost:4566` and dummy creds explicitly; `awslocal` (via `docker exec` into the LocalStack container, or `pip install awscli-local` locally) wraps that automatically.

### Technical Decisions
- Main SQS queue name `deliveries` is not sourced from any ADR — `software-architect`'s own naming choice during TASK-001-01 generation, explicitly flagged for review (`deliveries-dlq` is ADR-verbatim, the main queue name isn't).
- `ForcedBehavior` placed in a `behavior/` subpackage, not `model/` — `model` is reserved vocabulary for `domain/model` (framework-free domain types) per CLAUDE.md's hexagonal layout; this type is adapter-internal control-plane state, not a domain concept.
- No SQS producer/consumer Java code exists yet (relay/worker is future work per ADR-002) — the queue infra (compose + init script) was deliberately provisioned ahead of any code that uses it, per explicit user request ("local dev environment before any application code").

## Status at End
- Completed: FEAT-001 fully implemented (8 tasks), local dev stack verified end-to-end live (LocalStack queue config correct, stub receiver reachable unauthenticated under `local` profile, Insomnia collection working).
- Pending: TASK-001-07 (automated Testcontainers-LocalStack regression test for `init-sqs.sh`'s queue config) was discussed and judged worthwhile but never created/dispatched — open decision for next session.
- Nothing committed to git. Commit message (`Add local dev env: LocalStack SQS, stub, config`) and PR body were drafted and handed to the user; user commits/PRs manually.

## Notes for Next Session
- If continuing FEAT-001: TASK-001-07 is the next open item (Testcontainers + AWS SDK SQS test asserting `init-sqs.sh`'s queue attributes, so a future edit to the script can't silently drift from ADR-006 §1.1).
- The stub's `@Profile("local")` gate plus `LocalWebhookStubSecurityConfig`'s permit are the only security control in place; ADR-007's full `SecurityConfig` is still not implemented — any new endpoint added under any other profile currently falls under Spring Security's default (all paths authenticated).
