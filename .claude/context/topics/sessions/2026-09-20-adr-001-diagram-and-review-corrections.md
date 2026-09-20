# Session: 2026-09-20 ADR-001 diagram + architect review corrections

**Date:** 2026-09-20
**Topics:** architecture, diagram

## Work Completed

### Files Modified
- `docs/architecture/adr/ADR-001-webhook-notification-delivery-outbox.md` — 4 corrections applied (still `Status: Proposed`), see Technical Decisions.
- `docs/architecture/adr/diagrams/adr-001-information-flow.html` — new Archify architecture diagram (showcase quality, 9/9 checks pass, visual-check clean at 1440x900 through 2048x1320).

### Diagram content (final state)
Nodes: Platform Producers, Event Gateway, deliveries+subscriptions (Postgres), Relay/Dispatcher, SQS Queue, Self-Service API, DLQ, DLQ Consumer, Delivery Worker, Bulkhead+Circuit Breaker (gate), Client Webhook.
Edges include both directions of the API<->deliveries relationship (read vs. POST /replay as an explicit INSERT-not-mutate), the gateway's best-effort direct SQS publish (§1.1 step 5), and the DLQ consumer's write path to `FAILED`.
Cards: delivery state machine (DEAD and FAILED both explicit as separate terminal states, not collapsed into a slash-list), "who writes to deliveries" (§2.1 table condensed).

Tool note: Archify's `via` routing on a `top`/`bottom`/`left`/`right` `toSide` always snaps the entry coordinate to the target component's center on that axis — a `via` last-point that doesn't match the center produces a non-orthogonal final segment and fails `clean-flow/endpoint-side-direction`. Route long return edges through the empty inter-row band (between row1 bottom and row2 top) or above/below all rows entirely, never through a column another edge already occupies at the same y.

### Technical Decisions (ADR-001)
1. **VisibilityTimeout 10s -> 30s, bulkhead back to 2s** (was trimmed to 500ms to fit under 10s). Nothing in the design (maxReceiveCount=3, delete-and-reschedule deferral) depends on a tight VisibilityTimeout — only on it exceeding worst-case in-flight time (~9.3s), so the trim bought margin nothing consumed.
2. **`sequence_number` column dropped**, not replaced by a global Postgres sequence. A per-client monotonic counter needs a hot row updated on every ingest — the same class of problem the circuit breaker design deliberately avoided (§10.2). Replaced with `notification_events.created_at` + `event_id` tiebreak, already stored/indexed for the API's date filter.
3. **New §4.1 "Outbound webhook envelope"**: `created_at` moved into the signed body (HMAC covers body only; a header-borne timestamp is unsigned and MITM-rewritable). Headers narrowed to `X-Cobre-Signature`, `X-Cobre-Timestamp` (send-time, ~5min replay window — genuinely new mechanism, not previously specified), `X-Cobre-Delivery-Id`.
4. **Circuit breaker cross-pod reset gap** (§10.2): a pod that never ran the HALF_OPEN probe keeps its stale local failure count after the subscription goes CLOSED, silently lowering its effective trip threshold on subsequent failures. Fix: every worker resets its local Resilience4j instance for a subscription when it reads `circuit_state='CLOSED'` on the subscription row it already loads for URL/secret — no extra query.

## Status at End
- ADR-001 still `Proposed` — user reviews the diff and flips `Status` to `Accepted` manually per project workflow.
- Diagram and ADR are now consistent with each other.

## Notes for Next Session
- Once Accepted: Atlas generates `docs/features/FEAT-001-webhook-notification-delivery/` feature + per-agent tasks.
