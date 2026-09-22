# Session: 2026-09-21 FEAT-008 implementation + first real bootRun smoke test

**Date:** 2026-09-21
**Topics:** backend, security, database, infra

## Work Completed

### Files Modified
FEAT-008 waves 0-6 fully implemented (TASK-008-01 through 27, plus 16A): `TenantId` domain type,
JWT resource server (`JwtDecoderConfig`, `SecurityConfig`, 4 filter chains), `V5`/`V6` migrations
(3 Postgres roles + RLS), `TenantSessionBinder`, tenant-scoped query ports/adapters/use cases,
`NotificationEventController` (list/get/replay), `ReplayIdempotencyGuard`. Full file list and
design rationale live in `docs/features/FEAT-008-self-service-api-and-security/feature.md` and its
task files — not restated here. TASK-008-11/28/29/30 (Testcontainers) remain `Not Started` on
Tech Lead direction, spec-only.

### Problems Solved
Design-level fixes (roles model, `TARGET_NOT_FOUND` equivalence, replay's `DELIVERED` gap,
`UUID`/`String` eventId mismatch) are recorded in `docs/concerns.md` and ADR-007's amendments —
see those, not restated here.

**First-ever full `bootRun` against real Postgres (this session) surfaced 9 real wiring bugs**,
none catchable by the unit-test-only phase this project has run under. Full detail per bug is in
`docs/concerns.md`; the reusable *patterns* (useful beyond this one feature) are split out to
`.claude/context/topics/errors/2026-09-21-spring-boot-full-context-boot-gotchas.md`.

### Technical Decisions
- Three-role Postgres model (`challenge_owner`/`challenge_pipeline`/`challenge_api`) with
  `FORCE ROW LEVEL SECURITY` on — see ADR-007 amendment and `docs/concerns.md`.
- URL path convention change requested by Tech Lead: no underscores/hyphens in path segments,
  single word per segment (`/notification_events` → `/notificationevents`,
  `/local/webhook-stub` → `/local/webhookstub`). **Not yet applied** — an agent run to do this was
  interrupted before touching any code (see Pending below).

## Status at End
- Completed: FEAT-008 waves 0-6 (tasks 01-27 + 16A), all `Ready for Review`. App boots clean
  end-to-end under `local` profile with real Postgres/LocalStack via Docker Compose; all 3
  self-service endpoints verified manually with real JWTs (valid, expired, wrong-audience,
  missing-client_id, wrong-scope cases all correct).
- In progress: nothing mid-edit. Working tree has 59 changed/new files, unstaged — user is
  reviewing and will commit manually per project convention.
- Pending:
  1. URL path rename (`/notification_events` → `/notificationevents`,
     `/local/webhook-stub` → `/local/webhookstub`) — requires an ADR-005/007 amendment (path is
     fixed in ADR-005 §1) plus a mechanical grep-and-rename across `SecurityConfig`,
     `NotificationEventController`, `LocalWebhookStubSecurityConfig`, and every test that hardcodes
     the old path string. Not started.
  2. TASK-008-11/28/29/30 (Testcontainers) — deferred, needs explicit Tech Lead go-ahead.
  3. No event-generation path exists yet: `/internal/events` ingest requires SigV4, which is an
     explicit FEAT-008 follow-up (out of scope). Testing the 3 self-service endpoints against real
     data requires seeding `notification_events`/`deliveries` by hand via SQL.

## Notes for Next Session
- `docs/concerns.md` is for genuine open design disagreements only (per CLAUDE.md's design-authority
  rule) — do not use it as a running bug-fix changelog. A batch of resolved-bug entries was written
  there mid-session and then removed once this was pointed out; keep entries to things an architect
  still needs to weigh in on.
- Before resuming the path rename, re-verify nothing was partially changed — the interrupted agent
  run was confirmed to have made zero code changes (compile was clean, `grep` showed old paths
  still in place), only `git add` had run (unstaged afterward).
