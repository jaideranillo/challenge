---
id: ADR-005
title: Self-Service API, Replay and Target-URL Ownership Verification
status: Accepted
date: 2026-09-20
authors: software-architect (Atlas)
supersedes:
superseded_by:
---

# ADR-005: Self-Service API, Replay and Target-URL Ownership Verification

## Status

Accepted <!-- change only by the user: Proposed | Accepted | Rejected | Superseded by ADR-NNN -->

## Context

See `docs/architecture-overview.md` for full context; this ADR covers the three client-facing endpoints, replay-as-insert, and the ownership handshake a subscription's target URL must pass before it is deliverable.

## Decision

### 1. Self-service API endpoints

All three endpoints read the same `deliveries` table (joined to `notification_events` for the event body), through `port/out` interfaces. No separate read model, no projection, no cache in v1 (YAGNI).

| Endpoint | Use case | Behavior |
| --- | --- | --- |
| `GET /notification_events?created_from=&created_to=&delivery_status=&cursor=&limit=` | `QueryNotificationEventsUseCase` | Always scoped to the authenticated caller's `client_id`, taken from the security context and never from a request parameter. Filters by event creation date range and public `delivery_status`. Keyset (cursor) pagination on `(created_at, id)` — offset pagination degrades on a write-hot table. **Bounded page size** (proposed: default 50, max 200 — `limit` above the max is clamped, not rejected) and a **default date window** (proposed: last 30 days) applied when `created_from`/`created_to` are omitted, so an unbounded query can never be issued against a write-hot table by accident. Both numbers are proposals, not derived from measured usage — same caveat as ADR-004's Q5 and ADR-006's Q7. |
| `GET /notification_events/{notification_event_id}` | `GetNotificationEventUseCase` | Returns 404, not 403, when the row exists but belongs to another client, so the endpoint does not leak existence of other tenants' ids. Response includes the full attempt history (all `delivery_attempts` rows for this delivery, ADR-003 §3), not just the current `deliveries` row — this is what makes a client's "you never called me at 14:02" complaint answerable from this one endpoint instead of requiring a separate call. |
| `POST /notification_events/{notification_event_id}/replay` | `ReplayDeliveryUseCase` | Accepted only when the target delivery is in `DEAD` (not `FAILED` — ADR-003 §1, ADR-004 §1). Does **not** mutate the original row. Inserts a **new** `deliveries` row: same `event_id`/`subscription_id`/`client_id`, `status = PENDING`, `attempt_count = 0`, `origin = 'REPLAY'`, `replayed_from = <original row's delivery_id>` (ADR-003 §3). The original `DEAD` row is untouched and stays queryable as-is — permanent audit of the original attempt chain. Returns `409 Conflict` if the target is not `DEAD`, or if a partial-unique constraint (ADR-003 §3) rejects the insert because a non-terminal or already-`DELIVERED` row already exists for this `(event_id, subscription_id)` pair (i.e. a replay is already in flight, or already succeeded). Requires an `Idempotency-Key` header for early HTTP-level rejection of a double click, on top of that DB-level guard. **Returns an acknowledgment that the replay was accepted, not a delivery outcome** — the response carries the new row's id and `status = PENDING`; the actual attempt happens later, asynchronously, through the same relay/worker pipeline as any other delivery (ADR-002 §2.1, ADR-002 §2.2), so there is no outcome to return synchronously. |

Replay deliberately re-enters the pipeline as a fresh `PENDING` row rather than publishing directly to the queue: it reuses one code path (the gateway's own insert shape), and a queue outage cannot lose a replay. Inserting instead of mutating also means the `notification_event_id` a client held before replaying still resolves to the original `DEAD` record with its full history intact; `GET /notification_events/{notification_event_id}/replay`'s response carries the *new* row's id, since that is now the live delivery to poll.

**Port shapes** (contract-level, to be finalized in the feature breakdown):

- `port/in`: `RegisterNotificationEventUseCase`, `DispatchPendingDeliveriesUseCase`, `AttemptDeliveryUseCase`, `QueryNotificationEventsUseCase`, `GetNotificationEventUseCase`, `ReplayDeliveryUseCase` — one method each, taking an immutable command record and returning an immutable result record. `Optional`/empty collections, never `null`.
- `port/out`: `DeliveryRepositoryPort`, `DeliveryAttemptRepositoryPort`, `SubscriptionRepositoryPort`, `NotificationQueuePort`, `WebhookClientPort`, `ClockPort` (if the domain needs time beyond an injected `Clock`).
- `domain/model`: `NotificationEvent`, `Delivery`, `DeliveryStatus`, `DeliveryAttempt`, `Subscription`, `RetryPolicy` — plain Java records/enums, zero framework imports.

### 2. Target-URL ownership verification (GET challenge)

Subscription CRUD itself remains out of scope (Q9, `docs/architecture-overview.md`), but the **verification contract its create/update endpoints must implement is designed here**, because it is a security control (A06) and controls are stated explicitly at design time rather than deferred. Whichever API owns subscriptions, it owes this behavior.

The subscriber exposes **one URL serving two methods at the same path** — the Meta/Facebook Graph API webhook shape:

| Method on `target_url` | Use case | Behavior |
| --- | --- | --- |
| `GET <target_url>?challenge=<random-token>&verify_token=<subscription-scoped-secret>` | `VerifySubscriptionTargetUseCase` | Ownership handshake, issued by the platform. The subscriber's server must respond `200` with the `challenge` value echoed verbatim as the plain-text response body, within a bounded timeout. Success is: `200` + exact body match + within timeout. Anything else (non-`200`, mismatched body, timeout, TLS failure, URL rejected by the A01-SSRF validation of ADR-002) is a failure. The challenge token is single-use, generated by the platform per verification attempt, and never reused. The exact query-parameter names, the token length, and whether `verify_token` is per-subscription or a client-level shared secret are a follow-up detail, not settled here. |
| `POST <target_url>` | `AttemptDeliveryUseCase` (ADR-002 §2.2, unchanged) | The actual webhook delivery. Only ever issued once verification has succeeded — nothing in ADR-002 §2.1/§2.2 changes except that an unverified subscription is not deliverable and therefore never enters a claim batch. |

**Lifecycle.** A subscription is created in `verification_state = PENDING_VERIFICATION` (ADR-003 §3) and is **not deliverable** in that state. The platform issues the `GET` challenge; on success the subscription moves to `VERIFIED` and becomes deliverable (subject to `active`, as before). On failure or timeout it stays `PENDING_VERIFICATION`, and the failure is reported to the client over the existing self-service API's response/error shape (§1) — the same 4xx-with-reason convention the other endpoints use. Re-attempting verification is a client-initiated action on the subscription resource, consistent with ADR-004's Q12 ownership reasoning: the platform does not retry indefinitely on its own.

**Cadence: once at creation, and again on any `target_url` change.** Changing the URL puts the subscription back to `PENDING_VERIFICATION` and re-runs the handshake, because a new URL is a new target and carries none of the old one's proof. There is **no periodic re-verification** — it would add a scheduled job and a new failure mode (a healthy subscription silently going undeliverable because a one-off handshake blipped) for no gain against the threat A06 names, which is *registering* someone else's endpoint.

**Proposed numbers, not derived from measured usage** — same caveat as this section's page-size defaults and as ADR-004's Q5 and ADR-006's Q7: verification timeout ~5s connect + ~5s read, and the challenge token valid only for the duration of that single attempt. Both are configuration, not contract.

**Relationship to the SSRF controls.** The `GET` challenge runs *through the same URL validation* as a delivery attempt (ADR-002's A01-SSRF row: HTTPS only, public DNS only, private/loopback/link-local/metadata ranges denied, redirects denied, resolve-then-pin). Verification is layered on top of those controls, not in place of them: the SSRF validation decides whether the URL may be called at all, and verification decides whether the party behind it agreed to be called.

### 3. Schema notes

The columns themselves are defined in ADR-003 §3; only the rationale for them lives here.

- `verification_state` (`PENDING_VERIFICATION | VERIFIED`) and `verified_at` (nullable) carry the target-URL ownership handshake of §2. A separate column rather than an overload of `active`, because the two mean different things and are set by different parties: `active` is the client's assertion that the endpoint is live (ADR-004's Q12), `verification_state` is the platform's proof that the endpoint's operator agreed to receive traffic. A subscription is deliverable only when `active = true` **and** `verification_state = 'VERIFIED'`; rows are created `PENDING_VERIFICATION` and return to it whenever `target_url` changes (§2). This is the only schema change domain-ownership verification requires — the verification check joins onto the same `subscriptions` row the relay's claim query (ADR-002 §2.1) already reads, so it costs no extra round trip.
- `origin` (`INGEST | REPLAY | RECOVERED`) and `replayed_from` (nullable, self-referential FK to `deliveries.delivery_id`) record how a row came to exist. `origin = 'INGEST'` for everything the gateway writes; `origin = 'REPLAY'` with `replayed_from` pointing at the original `DEAD` row for anything `POST /replay` writes (§1); `origin = 'RECOVERED'` with `replayed_from` pointing at the original `FAILED` row for the internal (non-public) recovery action (ADR-003 §1.1, ADR-004 §1) — same column reused rather than a separate `recovered_from`, since exactly one of `REPLAY`/`RECOVERED` ever applies per row and `origin` already disambiguates which source row it points at. This is what lets the audit trail chain across both replays and recoveries without ever mutating a terminal row.

## Consequences

**Becomes easier**
- All three API endpoints are straightforward reads/writes against one authoritative table; no cross-store reconciliation.
- Delivery history and replay are first-class data, so client complaints are answerable with a query.

## OWASP / Security Impact

| OWASP Top 10:2025 | Exposure in this design | Mitigation direction (one line) |
| --- | --- | --- |
| **A05 Injection** | Filter parameters (`delivery_status`, date range, cursor) and `client_id` reach SQL; `content` reaches an outbound HTTP body. | `NamedParameterJdbcTemplate` bound parameters only, no string-concatenated SQL; `delivery_status` bound to an enum at the adapter boundary via Bean Validation, never passed through as free text. |
| **A06 Insecure Design** | The service makes outbound HTTP requests to a client-supplied subscription URL on behalf of that client. With no proof that the subscriber actually owns the target domain, a malicious client can register a third party's server as its "webhook URL" and use this service as an amplification/DDoS vector against a victim who never consented to the traffic. | **Domain-ownership verification, designed in §2, not deferred.** The subscription's target URL must answer a `GET` challenge at the *same path* it will later receive `POST` deliveries on: the platform calls `GET <target_url>?challenge=<random-token>&verify_token=<subscription-scoped-secret>` and the subscriber must echo the `challenge` value back as the plain-text response body within a bounded timeout. A subscription is created in `verification_state = PENDING_VERIFICATION` (ADR-003 §3) and becomes deliverable only once that handshake succeeds; on failure or timeout it stays `PENDING_VERIFICATION`, **no `POST` delivery is ever attempted against that URL**, and the client is told over the existing self-service API surface (§1). Cadence: **once at creation, and again whenever the client changes `target_url` on an existing subscription** (a URL change is equivalent to registering a new target and returns the subscription to `PENDING_VERIFICATION`); no periodic re-verification. This is a **complement to, not a replacement for, the SSRF controls in ADR-002's A01-SSRF row** (HTTPS-only, public-DNS-only, RFC1918/loopback/link-local/metadata denial, redirect denial, resolve-then-pin against DNS rebinding) — those remain the primary defense and still run on the `GET` challenge itself. Verification's specific value is narrower and is exactly the A06 concern: it stops a client from silently pointing the platform at *someone else's* server, because the target's operator must actively answer the challenge to opt in. It does not fully close amplification — a client who genuinely controls a domain can still aim it at a third party's infrastructure behind it. **The one piece still deferred to Q9 (`docs/architecture-overview.md`)** is the per-client subscription cap: that is a policy number, not a mechanism, and belongs with the subscription-management API. |
| **A01 Broken Access Control (IDOR)** | The three endpoints above take a client-controlled id. | Fully designed in ADR-007; not restated here. |
| **A07 Authentication Failures** | A public self-service API. | Fully designed in ADR-007; not restated here. |

## Downstream

All seven ADRs (ADR-001 through ADR-007) are now `Accepted`. Part of the `docs/features/FEAT-001-webhook-notification-delivery/` breakdown, ready for task generation.
