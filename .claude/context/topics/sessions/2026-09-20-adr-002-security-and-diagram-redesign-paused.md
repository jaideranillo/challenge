# Session: 2026-09-20 ADR-002 security design + diagram redesign (paused)

**Date:** 2026-09-20
**Topics:** security, architecture, diagram

## Work Completed

### Files Modified
- `docs/architecture/adr/ADR-002-api-authentication-and-tenant-isolation.md` — new, status `Proposed`. JWT resource server (RS256, self-contained, no introspection), tenant isolation via 4 layers (TenantId domain type with single factory site, mandatory port parameter, bound SQL predicate, PostgreSQL RLS fail-closed under non-bypassing `challenge_api` role), distinct authorities `notifications:read` vs `notifications:replay`, rate limiting split edge/app (600/min read vs 10/min+200/day replay), dev RSA key strategy (`tools/dev-jwt/`, outside `src/main/resources`, 6 fail-to-boot startup validations). Depends on ADR-001 (still `Proposed`).
- `docs/architecture/adr/diagrams/adr-001-information-flow.json` — **new**, previously did not exist (diagram had been delivered with no committed source spec). This is now the source of truth for the Archify diagram.
- `docs/architecture/adr/diagrams/adr-001-information-flow.html` + visual-check sidecars — regenerated from the JSON spec via Archify `deliver`.

### Problems Solved
1. **No source spec for existing diagram** — the ADR-001 diagram HTML existed but its Archify JSON spec was never committed. Reconstructed it from a prior session summary + ADR-001 text, added ADR-002's security layer on top (edge gateway, JWT filter chain, TenantId resolver, RLS annotation on Postgres, separate IAM SigV4 ingest chain, 404-not-403 card).
2. **Viewport overflow after first delivery** — added security nodes pushed page height over 900/1000px budgets at 1440x900 and 1600x1000. Fixed by compacting vertical spacing and dropping one redundant card (info already on node sublabels/boundaries). Resolved, validated 9/9, visual-check passed clean.
3. **Visual dominance bug (user-caught)** — after the security-layer add, the `delivery_worker -> postgres` write-back edge (long vertical line) visually dominated over the actual product path `delivery_worker -> gate -> client_webhook` (short edge), making the diagram read as if the delivery worker's destination was the database, not the client webhook. Fixed for that one edge pair with `variant: emphasis` on the real path and `variant: dashed` on the write-back — but this was a **partial, first-pass fix, not the real fix** (see below).

## Status at End — REDESIGN IN PROGRESS, NOT DELIVERED

The user correctly pushed back further: collapsing all of `notification_events`, `deliveries`, `subscriptions`, `delivery_attempts` into one `postgres` blob node is itself the root problem, not just a styling one — it hides which actor writes/reads which table (ADR-001 §2.1, §3, §4 define this precisely) and makes every edge look equally weighted.

**Decision made with the user:** split `postgres` into 4 real table nodes (`notification_events`, `deliveries`, `subscriptions`, `delivery_attempts`), each wired to its actual readers/writers per ADR-001, and make `delivery_worker -> gate -> client_webhook` (with retry policy: `5s -> 30s -> 2m -> 10m -> 1h -> 6h` backoff, ±20% jitter, response-code classification table) the one visually dominant path — everything else secondary/default or dashed weight.

Exact reader/writer map to implement (already derived from ADR-001, do not re-derive):
| From | To table | Verb |
|---|---|---|
| `event_gateway` | `notification_events` | INSERT (event+content), 1 txn |
| `event_gateway` | `subscriptions` | READ (resolve active subscription, tenant-scoped) |
| `event_gateway` | `deliveries` | INSERT N rows PENDING, same txn |
| `relay` | `deliveries` | UPDATE PENDING/RETRYING->QUEUED (SKIP LOCKED) |
| `delivery_worker` | `deliveries` | UPDATE QUEUED->PROCESSING->DELIVERED/RETRYING/DEAD |
| `delivery_worker` | `subscriptions` | READ (URL+secret); UPDATE (deactivate 404/410, `throttled_until` on 429) |
| `delivery_worker` | `delivery_attempts` | INSERT per HTTP attempt |
| `dlq_consumer` | `deliveries` | UPDATE -> FAILED (sole writer) |
| `self_service_api` | `notification_events` | READ (joined) |
| `self_service_api` | `deliveries` | READ (GET); INSERT (POST /replay, insert-not-mutate) |
| `self_service_api` | `delivery_attempts` | READ (GET single = full attempt history) |

**A background agent (`ac6de755becd7e5d0` in this session) was mid-way through implementing this when the user asked to pause it — it had already added the 4 table nodes to `adr-001-information-flow.json` but the spec is currently in a broken/invalid state**: `node bin/archify.mjs validate architecture docs/architecture/adr/diagrams/adr-001-information-flow.json --quality showcase --json` fails with `clean-flow/edge-through-node` errors (`event_gateway -> subscriptions` crosses `relay`; `delivery_worker -> deliveries` crosses `dlq`; more likely follow, agent was stopped before finishing the pass). **Do not deliver this JSON as-is** — it will produce a broken HTML. The task was stopped, not completed; its last stated in-progress note was "fixing self_service_api edges that ran along the boundary bottom border."

The delivered `adr-001-information-flow.html` on disk right now is still the **previous good version** (single `postgres` blob, emphasis/dashed fix applied, visual-check passing) — it was never overwritten with the broken spec, so nothing user-facing is currently broken. Only the `.json` source file is mid-edit.

## Notes for Next Session

1. Resume the 4-table redesign from the current (broken) `adr-001-information-flow.json` state, or start that portion over — the component list (`notification_events`, `deliveries`, `subscriptions`, `delivery_attempts` nodes) is already added; the connections need geometry fixes (edges crossing unrelated nodes) before it will validate.
2. Once `validate` passes 9/9 with 0 errors/warnings, run `deliver` to overwrite the `.html`, then `visual-check` to confirm no overflow at 1440x900 / 1600x1000 / 2048x1320, light+dark.
3. ADR-001 and ADR-002 are both still `Proposed` — no feature/task breakdown until the user flips both to `Accepted`.
4. Challenge's own delivery format is free-form (PDF confirmed): document or presentation, any modeling notation, GitHub repo with last commit before delivery date, and **AI usage must be documented in detail (prompts/screenshots)** per the case brief — this is a hard requirement from the challenge PDF, not optional.
