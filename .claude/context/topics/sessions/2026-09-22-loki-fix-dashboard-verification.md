# Session: 2026-09-22 Grafana dashboard verification, two production bugs found and fixed

**Date:** 2026-09-22
**Topics:** backend, infra, observability

## Work Completed

### Files Modified
- `docs/ai/ai-usage.md` — expanded with the ADR→Feature→Task workflow explanation and intro (docs-only, no technical content).
- `README.md` (new) — run instructions (`make up`, IntelliJ, Insomnia), traffic generation + Grafana validation steps, logging→Loki explanation.
- `src/main/java/.../adapter/out/persistence/DeliveryPipelineJdbcRepository.java` — `claimDue` NULL-handling fix, see `errors/2026-09-22-claim-due-null-next-attempt-at-excluded`.
- `src/test/java/.../adapter/out/persistence/ClaimDuePredicateTest.java` — added regression test for the above.
- `build.gradle`, `src/main/resources/logback-spring.xml` (new), `src/main/java/.../adapter/out/observability/OpenTelemetryLoggingInstaller.java` (new) — Loki logging fix, see `errors/2026-09-22-otlp-logging-missing-logback-bridge`.
- `docker/grafana/dashboards/notification-delivery.json` — TASK-009-07 dashboard (new, delivered by `devops-engineer` subagent), panel 9 title cleaned up from `"Trace and log correlation (ADR-008 §2, §3.1)"` to `"Trace and log correlation"`.
- `docs/features/FEAT-009-observability/tasks/TASK-009-07-grafana-dashboard-provisioning.md` — closing note, status `Ready for Review`.
- `.idea/workspace.xml` — added `CHALLENGE_WEBHOOK_SECRETS_DEMO` env var to the `ChallengeApplication` run configuration directly (user's own IntelliJ run config).

### Problems Solved
1. **claimDue NULL exclusion** — full delivery-pipeline stall, see `errors/2026-09-22-claim-due-null-next-attempt-at-excluded`.
2. **Loki receiving zero logs** — missing Logback→OTel bridge dependency, see `errors/2026-09-22-otlp-logging-missing-logback-bridge`.
3. **`make up` LocalStack queue race on fresh recreate** — see `infra/2026-09-22-make-up-localstack-queue-race`, not yet fixed at the Makefile level.

### Technical Decisions
- Both bugs above were fixed directly in-session rather than routed through a `software-architect`-authored ADR/task file, on explicit user instruction ("ya repara eo directo"). This is a deviation from the repo's normal delivery workflow (`CLAUDE.md`), done deliberately per user request for two production-blocking bugs found mid-verification, not a standing change to the workflow.

## Status at End
- Completed: both bugs fixed and verified live (Prometheus has `notification_delivery_attempt_latency_milliseconds_count` data, Loki has log lines with `trace_id`/`span_id`, both confirmed via direct API queries).
- In progress: user validating the Grafana dashboard UI directly (Explore panel queries, time range) — pipeline/data-layer confirmed correct via API, any remaining "No data" is a Grafana UI/query issue, not a data issue.
- Pending: TASK-009-07 still needs user review to move from `Ready for Review` to `Done`. The `make up` LocalStack race (infra note above) is undocumented as a task — worth a small devops task if it recurs.

## Notes for Next Session
- Local dev stack was recreated twice this session (`make down && make up`); each recreate wipes Grafana/Prometheus/Loki storage (no persistent volumes in `compose.yaml` for `grafana-lgtm`) — expected, not a regression, but worth telling the user proactively next time to avoid the "it got worse" confusion that happened here.
- Two background traffic-generation loops accidentally ran concurrently mid-session (a stale one from before a failed `make up` attempt, plus a fresh one after retry), doubling the intended event count (240 instead of 120). No harm to the pipeline, but worth being more careful killing stale background tasks before relaunching equivalent ones.
