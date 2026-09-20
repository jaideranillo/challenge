# Session: 2026-09-20 ADR-001 §8.1 Actuator/OpenTelemetry addendum

**Date:** 2026-09-20
**Topics:** observability, backend

## Work Completed

### Files Modified
- `docs/architecture/adr/ADR-001-webhook-notification-delivery-outbox.md` — added §8.1 "Actuator and OpenTelemetry wiring (implementation detail, no decision changed)" directly after §8. No implementation code written this session (explicit user request: doc only).

### Technical Decisions
All captured in ADR-001 §8.1 directly — see ADR-001, not restated here:
- `traceparent` propagated as an SQS message attribute (not only JSON body) so `notification.ingest -> notification.dispatch -> notification.attempt` is one continuous Tempo trace across the SQS hop.
- `delivery_id`/`event_id`/`client_id`/`trace_id` bound via SLF4J MDC, cleared per span, JSON-encoded by Logback.
- `content` (platform event body) and `response_excerpt` (client webhook response body) named the PII layer explicitly — never in MDC/logs/spans, DB columns + wire payload only.
- Micrometer percentile-histogram publishing for attempt latency (p50/p95/p99), not client-side averages.
- `client_id`/`subscription_id` hard-banned from meter tags — code-review enforced, restates Q8.
- Actuator health-group split: Postgres `db` indicator gates `/actuator/health/readiness` only, excluded from `/actuator/health/liveness` (outage should stop routing, not trigger a restart).

## Status at End
- Completed: §8.1 addendum written.
- In progress: none.
- Pending: ADR-001 `Status` still `Proposed` — user must set `Accepted`/`Rejected` before Atlas cuts feature/task breakdown (mandatory workflow, CLAUDE.md). No implementation exists yet.

## Notes for Next Session
- Once ADR-001 is `Accepted`, the observability wiring in §8.1 becomes one of the devops-engineer/backend-engineer task files in the feature breakdown — not before.
