---
id: ADR-004
title: Retry Strategy and Outbound Webhook Signing
status: Accepted
date: 2026-09-20
authors: software-architect (Atlas)
supersedes:
superseded_by:
---

# ADR-004: Retry Strategy and Outbound Webhook Signing

## Status

Accepted <!-- change only by the user: Proposed | Accepted | Rejected | Superseded by ADR-NNN -->

## Context

See `docs/architecture-overview.md` for full context; this ADR covers what happens on a failed attempt, what the client actually receives, and how that payload is signed.

## Decision

### 1. Retry strategy

- **Policy:** exponential backoff with jitter, expressed in the domain as a pure `RetryPolicy` (no Spring, no clock dependency beyond an injected `Clock`).
- **Schedule:** `5s -> 30s -> 2m -> 10m -> 1h -> 6h`, 6 steps, each with ±20% jitter. Jitter is mandatory, not cosmetic: without it, a batch of deliveries that failed together (e.g. a shared upstream blip) retries in the same instant and re-DDoSes the same client endpoint that just recovered. These numbers are a proposal, not derived from measured client behavior; see Q5. See also ADR-006 §1 (Resilience policies) for how this composes with the circuit breaker and bulkhead.
  **Note (implementation detail, not a new decision):** the schedule is a Spring `@ConfigurationProperties`-bound value, read once at startup and passed into the framework-free `RetryPolicy` at construction — the domain object stays unaware of Spring, only the wiring adapter knows where the numbers came from. The ADR does not currently say whether this schedule is **global** (one `RetryPolicy` for every subscription) or **overridable per subscription** the way `max_concurrency`/`circuit_backoff` already are (ADR-003 §3, stored on the `subscriptions` row). Left as a global config value for v1 is the simpler reading and consistent with YAGNI (ADR-005 §1); a per-subscription override would need its own column and is not currently modeled.
- **Redirects (3xx):** treated as `DEAD`, not followed. Two reasons: an unvalidated redirect is a standard vector for smuggling the request to an internal address after the original URL passed validation (SSRF, A01), and a receiver that redirects almost always means the client moved their endpoint without updating the subscription — a config problem, not a transient one.
- **Response classification:**

| Response | Action | Counts toward circuit breaker (ADR-006 §1) |
| --- | --- | --- |
| 2xx | `DELIVERED`, resets in-memory failure count | — |
| 3xx | `DEAD` immediately, not followed (see above) | yes |
| 400, 422 | `DEAD` immediately, no retry | no |
| 401, 403 | `DEAD` immediately, no retry | no |
| 404, 410 | `DEAD` immediately, **and deactivate the subscription** (`active = false`, ADR-003 §3) | no |
| 408 | `RETRYING` | yes |
| 429 | `RETRYING`, honoring `Retry-After` when present and sane, **and sets `throttled_until` on the subscription** (ADR-003 §3) | no |
| 5xx | `RETRYING` | yes |
| Timeout / connection reset / DNS failure / TLS failure | `RETRYING` | yes |

  Only 2xx is success; a 200 carrying an error payload in the body is still `DELIVERED` — the contract is the status code, not the body (the body is opaque to this service beyond the truncated `response_excerpt` kept for audit, ADR-003 §3).

  **404 and 410 both deactivate the subscription**, not just 410. A 404 at the client's registered URL is treated the same as 410 Gone: the endpoint no longer exists at that address, and continuing to schedule deliveries against it wastes worker capacity for something no retry count will ever fix. This is a deliberate choice, not the HTTP-spec-conservative reading (410 is a stronger, permanent signal; 404 could in principle be transient). Its consequence is resolved in **Q12**: this ADR's v1 deliberately ships **no** reactivation path — not a client-facing one and not an operator-facing one. Recovering from a deactivation is a subscription-management action (reactivate, or create a replacement subscription), and subscription management is out of scope here (Q9), so it is deferred to that future API as a client-initiated action rather than pulled into this design as an admin endpoint.

  **429 does not count toward the circuit breaker.** A 429 is the client explicitly asking to slow down — a rate-limit signal, not evidence the endpoint is down. It is deliberately escalated to the *subscription* (`throttled_until`, ADR-003 §3) rather than handled per-delivery, so every other in-flight delivery for that client also stops instead of each one independently rediscovering the same 429. Folding 429 into the breaker's failure count would risk tripping the breaker (and its exponential cooldown, ADR-006 §1) off ordinary, healthy rate-limiting.

  **Outcomes that count toward the circuit breaker are availability signals** — 5xx, 408, timeout/connection/DNS/TLS failure, and 3xx (a redirect from what should be a static webhook target is itself a signal something changed). **Outcomes that don't** are either permanent/business (400, 401, 403, 404, 410, 422 — the endpoint is telling us the request is wrong, not that it's down) or already handled by a more specific mechanism (429 -> throttle, not breaker). This is the concrete definition of "failure" behind ADR-006 §1's "in-memory per-subscription failure count," which the earlier draft of ADR-006 §1 left unstated.

- **Non-HTTP non-retryable cases** (-> `DEAD` immediately, not covered by the response table above since no response was received): subscription deactivated between scheduling and attempt, and a URL that fails the egress allow-list check at attempt time.
- **Exhaustion:** reaching `max_attempts` moves the row to `DEAD`.
- **Per-attempt timeouts** are mandatory so one hanging client cannot hold a worker indefinitely. They are also not free parameters: their sum plus the surrounding DB writes must fit inside the SQS `VisibilityTimeout` (**30s**, ADR-006 §1.1), because a message whose visibility expires mid-attempt is redelivered and burns one of only three receives. The budget, worst case, per ADR-006 §1.1:

| Step | Budget | Note |
| --- | --- | --- |
| Conditional claim `UPDATE` (ADR-002 §2.2 step 1) | ~100ms | single-row update by PK |
| Bulkhead permit acquire (ADR-006 §1.3) | **2s** | local semaphore; no longer trimmed — see ADR-006 §1.1 |
| Connect (DNS + TCP + TLS) | **2s** | arbitrary internet endpoint, needs real headroom |
| Read (response) | **5s** | the client-facing attempt SLO |
| `delivery_attempts` insert + `deliveries` status update (ADR-002 §2.2 step 6) | ~200ms | one transaction |
| **Worst-case total** | **~9.3s** | ~20.7s margin under the 30s `VisibilityTimeout` — the margin is 2.2x the worst case itself |

  Connect and read are stated additively, which is conservative for a JDK `HttpClient` where the request timeout spans the whole exchange; the real worst case is lower.
- **Attempt history:** each attempt writes a row to a `delivery_attempts` child table (attempt number, timestamp, HTTP status or error class, latency, truncated response snippet). The `deliveries` row keeps the current state and counters. This is what makes a complaint ("you never called me at 14:02") answerable. Exact schema and partitioning are the DBA's call in a later task.

**SQS DLQ vs. `DEAD` — two different dead-letter concepts, not one.**

`DEAD` is a business state on the `deliveries` row: the *webhook* was attempted and definitively failed (retries exhausted or non-retryable response). It is client-visible, queryable, and replayable via `POST /replay`. It is produced by normal, successful processing of a pointer message — the consumer ran without error and recorded a business failure.

The SQS redrive policy's DLQ is a *transport* safety net for a different failure: the consumer itself cannot process the pointer message — unexpected exception in `AttemptDeliveryUseCase` before it reaches the HTTPS call, or a crash loop on the same message. SQS's `maxReceiveCount` (**3**, the whiteboard's value — see ADR-006 §1.1) moves such a message off the main queue into `deliveries-dlq` so it stops being redelivered and blocking other messages.

**`maxReceiveCount = 3` is only correct because no expected, routine path ever returns a message to the queue.** An earlier draft of this ADR proposed 50, on the grounds that bulkhead-timeout deferrals returned the message via `ChangeMessageVisibility` and so consumed receives that had nothing to do with poison. That argument no longer applies, because that mechanism is gone: ADR-006 §1.1 replaces every `ChangeMessageVisibility` deferral with *delete the message and reschedule the row*, so a deferral costs zero receives instead of one. With deferrals, duplicate claims (ADR-002 §2.2 step 2) and post-write crashes (ADR-002 §2.2 step 7) all ending in `DeleteMessage`, the receive counter measures exactly one thing — the consumer dying on this message — and 3 is a tight, correct threshold for that. A budget of 50 would now be actively wrong: it would let a genuinely poisonous pointer crash the consumer fifty times before anyone is paged.

**A message landing in the SQS DLQ does move the `deliveries` row's state — to `FAILED` (ADR-003 §1), not to `DEAD`.** (This corrects an earlier draft of this ADR, which had the DLQ leave the row untouched; `FAILED` was added as its own terminal state specifically so a poison-message row is visible and distinguishable from an ordinary in-flight one, instead of silently looking like normal backlog.) The dedicated DLQ-consumer actor (ADR-003 §1.1) reads the pointer, extracts `delivery_id`, and writes `FAILED`. `FAILED` shares `DEAD`'s public `failed` status (ADR-003 §1) — the client still sees the delivery as failed — but is never accepted by `POST /replay` (ADR-005 §1): it signals an application bug, not something a client-side retry can fix. This is also the trigger for the platform-admin alert (distinct from the client-facing dead-letter rate metric) — an application-bug signal that pages the on-call engineer, not something that appears on the client-facing dashboard. Draining/inspecting the DLQ itself remains a manual operator action (redrive after a fix ships, or discard) and is out of scope for the public API either way.

**`delivery_id` extraction cannot fail — the pointer-message envelope is designed so that it can't.** An earlier draft left this as an acknowledged residual gap ("when the message is too malformed to extract a `delivery_id` at all, no row can be updated"). That gap is closed structurally rather than with a fallback heuristic, by pinning the envelope contract here:

- **The message carries four top-level scalar fields and nothing else:** `delivery_id` (uuid), `subscription_id` (uuid), `attempt_hint` (int, advisory only), `traceparent` (string, ADR-002 §3). There is no nested document, no embedded webhook body, no variable-shape sub-object. The event `content`, the target URL and the secret reference are all loaded from PostgreSQL by the consumer, which has to read the row anyway (ADR-002 §2.2 step 1). This is ADR-001 §1's "the message is a pointer and nothing more" made concrete: a flat envelope of fixed arity has no independently-malformable part, so there is no shape in which the body parses "partially" and loses the id.
- **`delivery_id` is additionally set as an SQS message attribute**, transported and length-validated by SQS separately from the body. The DLQ consumer reads the attribute first and only falls back to parsing the body. Correlation therefore survives a body that fails JSON parsing outright.
- **Both publishers write the same envelope through the same adapter.** The only two call sites are the gateway's `SendMessage` (ADR-002 §1.1 step 5) and the relay's `SendMessageBatch` (ADR-002 §2.1), and both go through the single `NotificationQueuePort` implementation (ADR-005 §1's port list). The envelope is constructed in exactly one place in the codebase.

Consequently a DLQ message with no extractable `delivery_id` is **not a lost delivery** — it is a message this system did not produce (a misrouted publisher, a manual injection, or a queue misconfiguration pointing two systems at the same ARN). It is logged in full and raised as an operational/security alert on that basis, and it is deliberately *not* correlated to any `deliveries` row, because there is none to correlate it to. Every message this design actually emits is always correlatable.

**Recovering a `FAILED` row, once the underlying bug is fixed, never mutates it.** An internal recovery action (ADR-003 §1.1) — distinct from `POST /replay`, and not exposed on the public API — inserts a new `deliveries` row (`origin = 'RECOVERED'`, `recovered_from = <original FAILED row>`, `PENDING`, `attempt_count = 0`), the same insert-not-mutate shape ADR-005 §1 already uses for `DEAD`. The original `FAILED` row stays untouched as permanent incident audit, and no new state-diagram edge is needed — the new row enters through the same `[*] --> PENDING` arc as any other insert.

**Ordering:** this design does not guarantee per-client ordering of webhook deliveries. Concurrent workers plus independent retry schedules mean a retried event can land after a later event. Ordering was not requested in the case; see ADR-001's Q6. What the client *is* given is the material to reconstruct order itself: `created_at` and `event_id` inside the signed body (ADR-001 §1.1, and §1.1 below).

### 1.1 Outbound webhook envelope

The body the client receives is fixed here rather than left to the signing-scheme follow-up, because two of its fields are load-bearing for decisions already made in this ADR (`created_at` for ordering, ADR-001 §1.1 and ADR-001's Q6; `attempt` for the at-least-once duplicate contract, ADR-003 §2).

```json
{
  "notification_event_id": "uuid-del-delivery",
  "event_id": "EVT001",
  "event_type": "credit_card_payment",
  "client_id": "CLIENT001",
  "created_at": "2024-03-15T09:30:22.145231Z",
  "attempt": 2,
  "content": "..."
}
```

`notification_event_id` is the `deliveries.delivery_id` (ADR-003 §3's naming note), `created_at` is `notification_events.created_at`, and `content` is the platform event body verbatim.

**`created_at` rides in the body, not in a header, and that is a security property rather than a formatting preference.** The HMAC is computed over the body plus the `X-Cobre-Timestamp` header value (§2), so a field placed in any other header is outside the signature: an intermediary could rewrite it and every signature would still verify. A client that used a header-borne `created_at` to order or discard notifications would therefore be ordering on unauthenticated data — and since ordering is the *only* thing this field exists for (ADR-001 §1.1), an attacker able to alter it can make a client discard a current notification as stale or accept a replayed stale one as current. Putting it in the signed body closes that at zero cost: the client deserializes the body regardless, so there is no parse it avoids by reading a header instead.

**Headers carry only what the client needs *before* parsing the body**, and nothing that ordering or business logic depends on:

| Header | Purpose |
| --- | --- |
| `X-Cobre-Signature` | The HMAC, so the client can verify before trusting the body. |
| `X-Cobre-Timestamp` | The **send time** of this HTTP request, covered by the signature, used for replay-window rejection (below). |
| `X-Cobre-Delivery-Id` | The `delivery_id`, so a client can deduplicate without opening the payload (ADR-003 §2, unchanged — the delivery id and attempt number keep riding in headers; `attempt` is additionally in the body so it is signed). |

**`X-Cobre-Timestamp` and `created_at` are two different timestamps and must not be conflated.** `X-Cobre-Timestamp` is when *this attempt* was sent — it moves on every retry of the same delivery, and its only job is replay protection: the client rejects a request whose timestamp is outside a **~5 minute window** of its own clock, so a signed request captured off the wire cannot be replayed at it later. `created_at` is when the *platform event occurred* — it is identical across every attempt of that delivery and across the whole replay chain (ADR-005 §1), and its only job is the client-side ordering/dedup reconstruction that replaces `sequence_number` (ADR-001 §1.1). Signing over "body + timestamp" was already the stated scheme in §2; what is new here is naming which timestamp that is (the header one) and stating the freshness check explicitly, since a signed timestamp that nobody validates the age of provides no replay protection at all. This is also the narrow, in-scope half of what Q3's "check nonce" could have meant; a full per-request nonce with server-side state remains out of scope.

### 2. Signing mechanism and secret rotation

Enforce TLS certificate validation (never disable it for "difficult" clients); sign payloads (HMAC over body + the `X-Cobre-Timestamp` header value) so clients can verify origin. **The body that is signed is pinned in §1.1, not deferred** — including the decision that `created_at` rides inside it rather than in an unsigned header, and the distinction between `X-Cobre-Timestamp` (send time, replay-window protection, rejected outside ~5 minutes) and `created_at` (event occurrence time, client-side ordering). What remains a follow-up is the surrounding scheme (digest algorithm, canonicalization, header encoding, rotation window length), not the envelope. The **replay window is stated here as a requirement rather than an implication**: signing over a timestamp gives no replay protection unless the receiver validates the timestamp's age, so the client-facing contract is that `X-Cobre-Timestamp` must be within ~5 minutes of the receiver's clock and a stale request rejected. HMAC location: computed in `AttemptDeliveryUseCase` (domain-adjacent, framework-free — a pure function of body + timestamp + per-subscription secret), immediately before the call reaches `WebhookClientPort`, never in the adapter. The secret lives on the `subscriptions` row (or a secrets manager reference, not the plaintext, per the DBA task), is loaded once per attempt, and is never logged or included in `delivery_attempts`. The resulting signature is sent as a request header (e.g. `X-Cobre-Signature`) alongside the timestamp; it is not persisted on the `deliveries`/`delivery_attempts` rows, since it is a function of already-persisted data and can be recomputed if needed. **Secret rotation is a named gap**: `subscriptions.secret_ref` is a single scalar today, and a single value cannot represent "two valid secrets, one expiring" — rotating a client's secret with one column forces a hard cutover and a dropped-signature outage while the client updates its verification code. Direction: `subscriptions` holds two secret slots (current + previous) plus an expiry marking the rotation window, not one scalar — a data-model consequence for the DBA task, in the same shape as `replayed_from` in ADR-003 §3 (the column set expands to carry the second reference rather than overloading the existing one). At attempt time the use case signs with the current secret and, while a rotation window is open, also emits a signature under the previous secret so the client's verification succeeds under either until the window expires; once expired the previous slot is cleared and only the current secret signs. The rotation trigger, window length, and whether both signatures ride in one header or two are part of the same signing-scheme follow-up decision, not designed here.

**Note (session 2026-09-20, controlled scope for this challenge, not a new decision):** the mechanism above is already sufficient to implement — what's open is calibration, not shape. Trigger is client-initiated via the subscription-management API (Q9 in `docs/architecture-overview.md`, out of scope for this ADR) or an internal operator action on suspected compromise; no periodic auto-rotation is proposed. Window length is a tuning value like Q5's retry numbers and ADR-006's Q7 — no client SLA data to derive it from yet, a conservative starting point (24-48h) is reasonable and revisited once real client integration timelines are known. Header shape: two headers (`X-Cobre-Signature`, `X-Cobre-Signature-Previous`) is preferred over one comma-joined value — simpler client-side parsing, and the previous header's mere absence (once the window closes) needs no delimiter logic to detect. None of this changes `subscriptions`' two-slot shape already decided above.

### 3. Schema notes

The columns themselves are defined in ADR-003 §3; only the rationale for them lives here.

- `secret_ref` is a reference (secrets-manager key), never the plaintext HMAC secret, per A04 in the OWASP table. `previous_secret_ref` (nullable, same reference semantics) and `previous_secret_expires_at` (nullable) exist only to carry an in-progress secret rotation: the previous secret stays valid until the window closes, after which `previous_secret_ref` is cleared to `null` and `secret_ref` alone signs. Two slots on this row rather than a secret-history table, since rotation is rare and only the current/previous pair is ever needed at once; the rotation rationale and signing behavior during the window are in §2, not repeated here.
- `throttled_until` is a separate, simpler gate driven by `429`/`Retry-After` (§1): a worker that receives `Retry-After` sets `throttled_until = now() + Retry-After` on the subscription, and the claim query also excludes rows whose subscription is still throttled. This honors a server-requested pause at the subscription level, since a `429` usually reflects the client's overall rate limit, not one delivery's.

## OWASP / Security Impact

| OWASP Top 10:2025 | Exposure in this design | Mitigation direction (one line) |
| --- | --- | --- |
| **A02 Security Misconfiguration** / **A04 Cryptographic Failures** | Outbound TLS verification and webhook payload signing. | TLS certificate validation is enforced and payloads are HMAC-signed so clients can verify origin. Full mechanism, secret rotation and header shape in §2. |

## Assumptions

Carried from the master Q-list in `docs/architecture-overview.md`; these two are owned by this ADR.

- **Q5 — Retry numbers (schedule resolved; the numbers themselves unvalidated).** The schedule is `5s -> 30s -> 2m -> 10m -> 1h -> 6h`, 6 steps, ±20% jitter (§1, ADR-006 §1), with per-attempt timeouts of 2s bulkhead acquire / 2s connect / 5s read bounded by the 30s `VisibilityTimeout` (§1's budget table, ADR-006 §1.1). *(An earlier version of this entry described a different, superseded policy — "6 attempts, 30s base, 2x multiplier, 1h cap" — which had not been updated when §1 changed; the schedule above is the real one.)* What remains open is not the shape but the calibration: none of these intervals is derived from measured client behaviour or an agreed SLO. Replace with real numbers when they exist. This is a tuning item, not a design gap — every one of them is a configuration value, and changing any of them changes no contract or state machine.
- **Q12 — Auto-deactivation and reactivation (resolved: deactivate on 404/410; no platform-side reactivation in v1, by design).** §1's response classification deactivates the subscription (`active = false`) on both `404` and `410`, confirmed by the user. The gap this creates is real and acknowledged: a client whose endpoint returned a transient `404` (deploy blip, momentary misroute) stays deactivated, and nothing in the pipeline turns it back on.

  **The decision is that the platform deliberately does not reactivate it.** There is no admin-side reactivation endpoint in this ADR's v1, and no automatic un-deactivation on the delivery path — not as an omission, but as the chosen behavior. Reactivating a subscription, or creating a replacement one, is a **client-initiated subscription-management action**: the client reactivates their own subscription once their endpoint is back, or failing that registers a new subscription. Both of those are operations on the subscription resource, which is Q9's territory (`docs/architecture-overview.md`) and explicitly out of scope here, so both are deferred to the future subscription-management API/ADR rather than being pulled into this design as an operator action.

  The reasoning is ownership, not effort. The platform deactivated the subscription because the client's own endpoint told it, twice over (`404`/`410`), that nothing is listening at the registered URL. Only the client knows when that is fixed and whether the URL is still the right one; an operator flipping `active = true` on their behalf is guessing, and an automatic flip would just resume hammering a dead endpoint. Keeping deactivation terminal from the platform's side also keeps `active` honest — it means "the client has asserted this endpoint is live", and only the client can make that assertion.

  Consequence for v1: a deactivated subscription stays deactivated until the subscription-management API exists. Until then, a direct operator write on the row is an out-of-band break-glass action, not a supported product path, and this ADR does not design an interface for it.

## Downstream

All seven ADRs (ADR-001 through ADR-007) are now `Accepted`. Part of the `docs/features/FEAT-001-webhook-notification-delivery/` breakdown, ready for task generation.
