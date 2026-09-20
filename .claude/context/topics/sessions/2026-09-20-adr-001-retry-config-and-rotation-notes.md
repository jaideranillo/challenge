# Session: 2026-09-20 ADR-001 retry-config and rotation notes

**Date:** 2026-09-20
**Topics:** backend, security

## Work Completed

### Files Modified
- `docs/architecture/adr/ADR-001-webhook-notification-delivery-outbox.md` — two implementation-detail notes added, no decision changed:
  - §4: `RetryPolicy` schedule is `@ConfigurationProperties`-bound, injected into the framework-free domain object at construction; flagged as unresolved whether it's global or per-subscription overridable (unlike `max_concurrency`/`circuit_backoff`, which are per-subscription on `subscriptions`).
  - A02/A04 OWASP row: secret rotation trigger/window/header shape calibrated — client-initiated via subscription-mgmt API (Q9, still out of scope) or operator action on compromise, 24-48h window as starting point, two separate headers (`X-Cobre-Signature` / `X-Cobre-Signature-Previous`) preferred over one comma-joined value.

## Status at End
- Completed: both notes written to ADR-001.
- Pending: `Status` still `Proposed` (line 15) — user declined to have the agent self-approve per CLAUDE.md workflow rule ("no agent self-approves"); user must flip it manually.

## Notes for Next Session
- No code exists yet. Once `Status: Accepted`, Atlas cuts `docs/features/FEAT-NNN/` + task files per CLAUDE.md workflow before any implementation starts.
