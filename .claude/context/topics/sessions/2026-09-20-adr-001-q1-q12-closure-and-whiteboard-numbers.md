# Session: 2026-09-20 ADR-001 whiteboard numbers, design-gap closure, Q1-Q12 all resolved

**Date:** 2026-09-20 (continuation of the same-day sessions)
**Topics:** architecture, backend

## Work Completed

### Files Modified
- `docs/architecture/adr/ADR-001-webhook-notification-delivery-outbox.md` — three rounds of edits via `software-architect` (Atlas), all in `Status: Proposed` (unchanged).

### Problems Solved
1. **`maxReceiveCount` whiteboard/ADR conflict (3 vs 50) resolved by removing `ChangeMessageVisibility` from the design entirely.** The old "50, so bulkhead-timeout deferrals never get miscounted as poison" reasoning didn't survive at 3 — `ApproximateReceiveCount` increments on every redelivery, including visibility-changed ones. Fix: bulkhead/breaker/DLQ deferrals now go through the relay's due-query re-publish (fresh message, receive count resets) instead of `ChangeMessageVisibility`. Every worker path now ends in `DeleteMessage`; a second receive can only mean the consumer died mid-attempt, so 3 becomes a correct, tight poison threshold. See ADR §4, §6.2, §10.1.
2. **`VisibilityTimeout` (whiteboard: 10s, previously unstated in ADR) validated against worst-case in-flight time.** Old budget summed to ~9.3-10s (no margin). Fix: bulkhead acquire timeout trimmed 2s -> 500ms, giving ~7.8s worst case (~22% margin). Risk stated explicitly in the ADR rather than hidden: a GC pause/slow DNS can still exceed 10s, but it's safe (state-guarded claim prevents duplicate send), costs one receive of three. See ADR §4's budget table, §10.1.
3. **Circuit breaker had no recovery path (`OPEN -> HALF_OPEN -> CLOSED` never defined)** — closed in new §10.2. Relay writes `OPEN -> HALF_OPEN` on its existing 5s poll cycle once `circuit_opened_at + circuit_backoff` elapses (no new job). `HALF_OPEN` admits exactly one probe via the existing `max_concurrency` in-flight check (cap 0/1/max_concurrency by circuit state). Worker writes `HALF_OPEN -> CLOSED`/`OPEN` in the probe's own outcome transaction. `consecutive_opens` resets on close. Still transitions-only persisted, never a per-attempt counter (same constraint as before).
4. **DLQ poison-message correlation gap (malformed message with no extractable `delivery_id`) closed structurally**, not with a fallback heuristic. Pointer envelope now pins four top-level scalars (`delivery_id`, `subscription_id`, `attempt_hint`, `traceparent`) with `delivery_id` also carried as an SQS message attribute (transported separately from the body), so a malformed body can't lose correlation. An uncorrelatable DLQ message is now defined as "not ours" (misrouted publisher) rather than a lost delivery. See ADR §4.
5. **Q1-Q12 all resolved** (were 6 genuinely open: Q1, Q2, Q4, Q8, Q10, Q12-half; Q5/Q7 already design-resolved, numeric calibration only):
   - Q1: Amazon SQS, confirmed.
   - Q2: "tobias" whiteboard label — user confirmed it's a writing slip, not a design element; closed, no schema impact.
   - Q4: public `delivery_status` vocabulary (`pending`/`completed`/`failed`) — resolved as a necessary consequence of the state machine already supplied (§2.1's mapping table), not a separate assumption.
   - Q8: rejected user's own proposal to tag metrics by `client_id`+`subscription_id` ("group after") — subscription-level tagging multiplies the same unbounded-cardinality problem rather than solving it. Resolution: metrics stay tagged by `event_type`/status class only; per-client drill-down via Loki/Tempo (already carries `delivery_id`/`client_id`/`event_id`); log-derived metrics (LogQL recording rules) as the fallback if a persistent per-client dashboard is needed later.
   - Q10: producer-to-gateway auth = AWS IAM (SigV4-signed / IAM role-based), since the platform is already assumed on AWS (Q1). Distinct from the self-service API's per-client auth.
   - Q12: no platform-side reactivation for an auto-deactivated subscription (404/410), by design — not admin, not automatic. Reactivation/replacement is a client-initiated action deferred to the future subscription-management API (Q9's territory).
   - Status table and cross-references (incl. §9's `lease_owner` bullet referencing Q2) corrected to stop contradicting the resolved prose entries.

### Technical Decisions
All architectural — see ADR-001 §4, §6.2, §10.1, §10.2, Assumptions/Open Questions section. Pointers only, not restated here.

## Status at End
- ADR-001 `Status`: still `Proposed`. Every Q1-Q12 now resolved; only Q5/Q7 numeric calibration remains, explicitly framed as non-blocking tuning.
- OWASP/Security Impact section: **untouched, deliberately deferred to a separate pass** (user's explicit ordering — security goes last).
- §10 Runtime (virtual threads/WebFlux) draft: confirmed nothing to merge — no leftover note existed to remove.
- Agent reliability note: two of the three Atlas subagent runs this session failed before completing (one hit a session/rate limit before making any edit, one stalled 600s mid-edit after landing Q1/Q2/Q4 but before Q8/Q10/Q12) — verified via `grep` on the ADR file between each relaunch to avoid duplicate/conflicting edits, then relaunched narrowed to only the remaining unresolved items.

## Notes for Next Session
- Next step per user: move to the OWASP/Security Impact section. Three Atlas rulings already given in an earlier session (2026-09-20 failed-recovery-api-security-review) are the starting point: amplification/DDoS -> new A06 row (not A10/A01); domain-ownership-verification and subscription-cap deferred as forward-requirements tied to Q9; HMAC rotation belongs in the existing A02/A04 row (not A07), and `subscriptions.secret_ref` as a single scalar doesn't support "two valid secrets during transition" without a schema change.
- Once security section is applied and user reviews/sets `Status: Accepted`, next step per `docs/README.md` workflow is Atlas generating `docs/features/FEAT-001-webhook-notification-delivery/` + per-agent tasks (backend-engineer, dba, security-engineer, devops-engineer).
