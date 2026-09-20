# Session: 2026-09-20 ADR-001 OWASP/Security Impact section — A06 added, HMAC rotation, domain-ownership verification designed

**Date:** 2026-09-20 (continuation of the same-day sessions)
**Topics:** backend, security

## Work Completed

### Files Modified
- `docs/architecture/adr/ADR-001-webhook-notification-delivery-outbox.md` — four rounds of edits via `software-architect` (Atlas), all in `Status: Proposed` (unchanged).

### Problems Solved
1. **User's three proposed OWASP items checked against existing table.** Two were already covered and needed no change: replay/`event_id`-not-caller's-own (A01 Broken Access Control — IDOR, existing row) and client-API-needs-auth (A07 Authentication Failures, existing row). Third item — outbound requests to client-supplied subscription URL used as an attack vector — was miscategorized by the user as A10 (Mishandling of Exceptional Conditions, which in this ADR already means something else: retry/dead classification fail-open/closed); recategorized and added as new **A06 Insecure Design** row: amplification/DDoS via an unverified webhook target.
2. **A06 mitigation designed concretely, not left as a deferral.** Modeled on Meta/Facebook Graph API webhook verification: subscriber's `target_url` must answer a `GET ?challenge=&verify_token=` handshake (echo challenge back, bounded timeout) at the same path that later receives `POST` deliveries, before the subscription becomes deliverable. New `### 5.1 Target-URL ownership verification (GET challenge)` subsection added after §5. Lifecycle: subscription created `PENDING_VERIFICATION`, moves to `VERIFIED` only on handshake success; re-verifies once at creation and again on any `target_url` change; no periodic re-verification (adds a failure mode for no gain against the A06 threat). Explicitly a complement to, not replacement for, the existing A01-SSRF controls (HTTPS-only, public-DNS-only, private-range denial, redirect denial, DNS-rebinding pin) — those still gate whether the URL may be called at all; verification only proves the operator behind it agreed to receive traffic. Per-client subscription-cap remains the one piece still deferred to Q9 (policy number, not a mechanism).
3. **HMAC secret rotation gap closed** in the A02/A04 OWASP row: `subscriptions.secret_ref` was a single scalar, which cannot represent "two valid secrets, one expiring" during a rotation window. Direction: service is the signer (client verifies, confirmed direction) — during a rotation window the service signs and emits under both current and previous secret so the client's verification succeeds under either, until the window closes.
4. **§9 schema updated to match both fixes**, closing a doc-internal contradiction before it could reach the DBA task:
   - `subscriptions.secret_ref` (unchanged) + new `previous_secret_ref` (nullable) + `previous_secret_expires_at` (nullable timestamp) — three columns, not a secret-history table, since rotation is rare and only the current/previous pair is ever needed at once.
   - `subscriptions.verification_state` (`PENDING_VERIFICATION | VERIFIED`) + `verified_at` (nullable) — kept separate from the existing `active` column deliberately: `active` is the client's own liveness assertion (Q12), `verification_state` is the platform's proof the target's operator agreed to receive traffic. Deliverable requires `active = true` AND `verification_state = 'VERIFIED'`. Joins onto the same row the relay's claim query (§6.1) already reads — no extra round trip.

### Technical Decisions
All architectural — see ADR-001 §5.1 (new), §9 (`subscriptions` table), OWASP table rows A06 and A02/A04. Pointers only, not restated here.

## Status at End
- ADR-001 `Status`: still `Proposed`. OWASP/Security Impact section now has all three user-proposed items resolved (two confirmed as already-correct, one recategorized+designed) plus the HMAC-rotation gap closed.
- Open judgment call flagged for user, not yet decided: `VerifySubscriptionTargetUseCase` (new in §5.1) is not listed in §5's "Port shapes" `port/in` enumeration, since that list is framed as this ADR's in-scope surface and subscription CRUD is Q9's territory — user has not yet said whether to add it there for symmetry.

## Notes for Next Session
- OWASP/Security Impact section is now substantially complete for the items raised so far; no other rows flagged as needing changes.
- Once user is satisfied with the security section and sets `Status: Accepted`, next step per `docs/README.md` workflow is Atlas generating `docs/features/FEAT-001-webhook-notification-delivery/` + per-agent tasks (backend-engineer, dba, security-engineer, devops-engineer). The DBA task will need to implement the `subscriptions` schema changes from this session (secret rotation columns + verification-state columns) alongside whatever Q9's subscription-management API needs.
