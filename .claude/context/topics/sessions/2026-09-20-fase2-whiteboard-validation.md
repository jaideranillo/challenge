# Session: 2026-09-20 Fase_2 whiteboard images vs ADR-001 validation

**Date:** 2026-09-20 (continuation of the same-day sessions)
**Topics:** architecture, backend

## Work Completed

### Files Reviewed (no ADR changes applied — validation only, explicitly requested)
- `docs/challenge/proposal/Fase_2/High_Level_Design.png` and `Configs_High_Level.png` — the original whiteboard photos this ADR's "the whiteboard's X" references have been citing all along. Read and cross-checked against `docs/architecture/adr/ADR-001-webhook-notification-delivery-outbox.md`.

### Findings
1. **Structural match: 1:1, no missing components.** Every whiteboard box (Producer, Gateway, BD/data model, Consumer, Client, Relay, SQS, DLQ, API, Worker, Observability) and the 7-state list (Pending/Processing/Retrying/Delivered/Dead/Queued/Failed) map cleanly onto existing ADR sections (§1.1, §9, §6.1, §6.2, §4, §2, §5, §2.1, §8). The whiteboard's response-code config (2xx/3xx/4xx with 429/408/404-410 carved out as exceptions, 5xx, timeout) matches the §4 response table built earlier this session exactly — independent confirmation that table reconstructed the original intent correctly.
2. **`maxReceiveCount` conflict: whiteboard says 3, ADR §4/§10 proposes 50.** Not cosmetic — the ADR's reasoning for a *high* count (bulkhead-timeout deferrals must never exhaust it and get miscounted as poison) doesn't hold at 3. Unresolved, needs a decision: keep 50 (deviate from original sketch, with stated reasoning) or honor 3 and rework how bulkhead deferrals interact with SQS's receive counter.
3. **`VisibilityTimeout = 10s` found on the whiteboard, never stated numerically in the ADR anywhere.** Closes a gap that's been open since the very first ADR-001 session (carried over unresolved in every session summary since). 10s is tight against the worst-case in-flight time implied by §6.2/§4 (~9-10s: bulkhead acquire + connect + read timeouts + DB write) — not unsafe (state-guard claim absorbs early revisibility, §0/§6.2) but generates avoidable duplicate POSTs under load if adopted as-is.

## Status at End
- No ADR-001 edits made — user explicitly asked to validate only, not apply.
- Both numeric conflicts (`maxReceiveCount`, `VisibilityTimeout`) are open, unresolved, not yet decided.

## Notes for Next Session
- If revisiting: the two numeric deltas above are the concrete next decision point for §9/§10 — don't need to re-read the whiteboard images again, this note has the extracted values and the reasoning tension.
- Security/OWASP section (from the prior session) is still on hold too, separate from this.
