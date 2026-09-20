# Session: 2026-09-20 ADR-001 delivery worker + HTTP response classification

**Date:** 2026-09-20 (continuation of the same-day resilience-review session)
**Topics:** architecture, backend

## Work Completed

### Files Modified
- `docs/architecture/adr/ADR-001-webhook-notification-delivery-outbox.md` — added new §6.2 (Delivery worker), renumbered §7/§8/§9 -> §8/§9/§10 with all cross-references fixed, replaced §4's prose retry/non-retry bullets with a full HTTP response-classification table, added Q12.

### Problems Solved
1. **Consumer claim guard was over-broad in the user's draft** — draft guarded `WHERE status IN ('QUEUED','RETRYING','PENDING')`; corrected to `= 'QUEUED'` only, matching §2.1 (only the relay writes `PENDING/RETRYING -> QUEUED`, and a pointer message only exists after that commits — no real message ever points at a `PENDING`/`RETRYING` row).
2. **"Discard the message" was ambiguous (also present in §0's original text)** — clarified as an explicit `DeleteMessage` on zero-rows-affected claim, not just a no-op skip. Left un-deleted, a duplicate claim would keep redelivering until `maxReceiveCount` (50) is exhausted and misclassify a benign duplicate as `FAILED` via the DLQ path.
3. **§7.3 signing cross-reference in the user's draft didn't exist** — resolved by giving the delivery worker its own subsection (§6.2, sibling of §6.1 the relay) rather than trying to force it under Observability; required renumbering the three sections after it.
4. **Circuit-breaker "failure count" definition was never stated anywhere in the ADR** — §10 said the breaker trips off "an in-memory per-subscription failure count" but never defined what counts as a failure. Now explicit in §4's response table: availability signals (5xx, 408, timeout/connection/DNS/TLS, 3xx) count; business/permanent outcomes (400/401/403/404/410/422) and 429 (handled via throttle instead) don't.
5. **404 vs 410 subscription deactivation** — user's draft auto-deactivated on both; flagged the risk (404 isn't a spec-guaranteed permanent signal, and subscription mgmt is out of scope per Q9 so there's no reactivation path). User confirmed: deactivate on both regardless. Captured the residual gap as **Q12** (no reactivation path for an auto-deactivated subscription).

### Technical Decisions
All architectural — see ADR-001 §4, §6.2, §10, Q12. Pointers only:
- Delivery worker per-message flow: claim -> bulkhead permit (2s) -> breaker check -> sign+POST -> insert attempt/update status -> `DeleteMessage`, in that order, with insert-before-delete being load-bearing (same asymmetry as §0: a lost SQS message is recoverable, a lost DB write is not).
- TLS handshake/certificate failure added to the retryable list (§4) — was missing from the prior enumeration.
- 429 explicitly excluded from circuit-breaker counting — it's a rate-limit signal (escalated to `throttled_until` at the subscription level) not an availability signal, and folding it into the breaker would trip the exponential cooldown off healthy rate-limiting.

## Status at End
- Completed: §6.2 added and renumbering done, §4 response-classification table added, Q12 added.
- ADR-001 `Status` still `Proposed`. Q1-Q12 outstanding before `Accepted`.

## Notes for Next Session
- Circuit breaker HALF_OPEN/CLOSED transitions are still the next open gap in §10 (carried over from the prior session, not addressed this session).
- Q5 retry-schedule numbers in Assumptions still need the stale 30s-base/2x/1h-cap text corrected to the actual `5s->30s->2m->10m->1h->6h` schedule (also carried over, not touched this session).
