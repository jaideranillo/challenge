# Session: 2026-09-19 ADR-001 refinement — webhook notification delivery

**Date:** 2026-09-19
**Topics:** architecture, backend, database

## Work Completed

### Files Modified
- `docs/architecture/adr/ADR-001-webhook-notification-delivery-outbox.md` — created (Proposed) and iteratively refined. See ADR-001 for full content: outbox pattern, state machine, retry strategy, self-service API, observability, OWASP, data model.

### Technical Decisions
All architectural — see ADR-001, not restated here. Refinements made this session on top of the initial draft:
- No separate outbox table: `deliveries` IS the outbox, single write path, nothing enqueued pre-commit (ADR-001 §Decision).
- SQS chosen over Kafka: queue is transport/wake-up only, not log of record; source of truth is Postgres (ADR-001 §Decision, Q1).
- `DEAD` (business state, client-visible, replayable) vs. SQS DLQ (transport poison-message, admin-alert only, no `deliveries` state mutation) are distinct concepts — added as new subsection in ADR-001 §4.
- HMAC signing location pinned: computed in `AttemptDeliveryUseCase`, secret from `subscriptions.secret_ref`, header-only, never persisted (ADR-001 OWASP table).
- Relay's `SKIP LOCKED` periodic claim query (not SQS) is the guaranteed-delivery path; SQS is a latency optimization only (confirmed, already in ADR-001 §1/§6).
- Data model defined: `notification_events`, `subscriptions`, `deliveries`, `delivery_attempts` — added as ADR-001 §8 with ER diagram and column-level rationale.

## Status at End
- Completed: ADR-001 drafted through §8 (data model), Status still `Proposed`.
- In progress: retry-response classification table (ADR-001 §4) agreed in chat (2xx→DELIVERED, 3xx→DEAD no-redirect-follow, 408/429/5xx/network→RETRYING, other 4xx→DEAD) but the 3xx row was **not yet written into the ADR file** — still pending as of session end.

## Notes for Next Session
- Apply the pending 3xx→DEAD edit to ADR-001 §4 before treating retry classification as final.
- ADR-001 has 11 open questions (Q1–Q11) awaiting user confirmation before `Status` can move to `Accepted`; Q2 (sketch's "tobias" label) now has a best-effort resolution (`lease_owner`/`lease_expires_at`) proposed in §8 but still unconfirmed.
- Whether to add a dedicated SQS-DLQ observer worker (vs. CloudWatch-alarm-only) was analyzed but left as an open choice — not yet decided or written into the ADR.
- Once `Status: Accepted`, next step per `docs/README.md` workflow is Atlas generating `docs/features/FEAT-001-webhook-notification-delivery/` + per-agent tasks.
