# Session: 2026-09-20 ADR-001 FAILED recovery, §5 API defaults, OWASP A06 classification (deferred)

**Date:** 2026-09-20 (continuation of the same-day sessions)
**Topics:** architecture, backend, security

## Work Completed

### Files Modified
- `docs/architecture/adr/ADR-001-webhook-notification-delivery-outbox.md`:
  - §2.1, §4, §8, §9: added internal (non-public) recovery mechanism for `FAILED` rows — insert-new (`origin='RECOVERED'`, `replayed_from=<original>`, reusing the same column as `REPLAY`), never mutates the terminal row. Mirrors §5's replay-as-insert pattern for `DEAD`. No state-diagram edge needed — recovery inserts re-enter through the existing `[*] --> PENDING` arc.
  - §5: added `GET /{id}` returning full `delivery_attempts` history; `/replay` response clarified as an async acknowledgment, not a delivery outcome; page-size (default 50, max 200) and date-window (default 30 days) defaults added, marked as proposals not derived — same caveat class as Q5/Q7.
  - Read-replica idea for GET endpoints was proposed then explicitly dropped by the user ("no creo que lo necesitemos a este punto") — not in the ADR.

### Problems Solved
1. **FAILED recovery would have contradicted the terminal-row-immutability principle** §5 established for `DEAD` (prior session) — an earlier draft proposed `UPDATE ... SET status='PENDING'` directly on the `FAILED` row. Caught before merging; resolved by reusing the insert-new pattern instead. See ADR §2.1/§4/§8 for the resolved design.
2. **A "Postgres unavailable at write time" DLQ scenario turned out not to reach the DLQ under the §6.2 flow** — traced through: claim succeeds, POST sent, DB write fails, message isn't deleted (correct), but redelivery's claim query now sees the row as `PROCESSING` not `QUEUED`, hits the zero-rows/"another worker has it" branch, and the message gets deleted — the row actually self-heals via §6.1's 60s `PROCESSING` staleness sweep, invisibly, never alarming. User confirmed this is acceptable (no data loss, just no alarm) rather than a bug to fix.

### Technical Decisions
Architectural — see ADR-001 §2.1/§4/§8/§9, §5. Pointers only:
- `FAILED` recovery is internal/ops-only, never exposed on the public `/replay` endpoint (FAILED still 409s there) — it signals an application bug, not a client-fixable condition.
- No separate read replica for v1; all reads and writes go to the same Postgres instance. User's explicit call, revisit later if read load actually competes with delivery write load.

### Open / Deferred
- **Security section (draft "13. Security" mapping to OWASP) not applied.** `software-architect` (Atlas) agent was consulted and gave rulings on 3 classification questions (amplification/DDoS -> new A06 row, not A10 or A01; domain-ownership-verification and subscription-cap deferred as forward-requirements tied to Q9, not designed in ADR-001; HMAC rotation belongs in the existing A02/A04 row, not A07 — and flagged that `subscriptions.secret_ref` as a single scalar column doesn't support "two valid secrets during transition" without a schema change). **User explicitly held off applying any of it** ("estamos muy confundidos aun, deja toda esa seccion de seguridad para evaluar") — nothing from this security batch is in the ADR yet. Full Atlas ruling is in this session's transcript, not re-saved elsewhere; re-derive from ADR §OWASP table + Q9/Q12 if picking this back up.
- §10 Runtime (virtual threads/WebFlux) draft was reviewed — found to duplicate existing §6 content almost entirely; two genuinely new bits (explicit WebFlux-backpressure rationale, `-Djdk.tracePinnedThreads=full` as a verification step) were identified but not yet merged — user called it low priority, no decision requested yet.

## Status at End
- ADR-001 `Status` still `Proposed`.
- Security/OWASP table update: **on hold**, needs a calmer pass before applying Atlas's rulings.
- §10 Runtime refinement: **not started**, low priority per user.

## Notes for Next Session
- When resuming the security section: the 3 Atlas rulings above are the starting point, don't re-litigate from scratch unless the user wants to.
- Carried over from earlier sessions, still not addressed: circuit breaker HALF_OPEN/CLOSED transitions (§10), Q5's stale retry-schedule numbers in Assumptions.
