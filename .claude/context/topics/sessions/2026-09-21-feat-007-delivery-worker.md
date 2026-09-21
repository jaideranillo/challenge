# Session: 2026-09-21 FEAT-007 delivery worker

**Date:** 2026-09-21 03:00
**Topics:** backend, security, infra

## Work Completed

### Files Modified/Created
- `docs/features/FEAT-007-delivery-worker/` — 21 tasks (TASK-007-01 through 21) plus 7 follow-up
  addenda (14a, 3a, 19a, 19b, 20a, 20b, 12a), all `Ready for Review` (verified via `grep -a`, not
  agent self-report). Implements ADR-002 §2.2, ADR-004 §1/§1.1, ADR-006 (full).
- Worker: `DeliveryQueueListener` (long poll 20s, batch 10, virtual-thread fan-out, unconditional
  DeleteMessage on every path, never branches on `AttemptDeliveryResult`), `DeliveryDlqConsumer`
  (correlates via `delivery_id` message attribute even when body is undeserializable, marks
  `FAILED`), `AttemptDeliveryUseCaseImpl` (fixed order: claim -> sign+POST -> record+update ->
  caller deletes), `DeliveryOutcomeWriter` (single-transaction outcome write), Resilience4j
  bulkhead/circuit-breaker adapters (per-subscription, in-memory only), `OutboundUrlValidator`
  (app-level SSRF gate), `WebhookSigner`/`WebhookResponseMapper`/envelope serializer.
- ~55 files touched total (new + modified), nothing staged/committed — user reviews manually per
  standing workflow.

### Problems Solved
1. **Missing "no attempt was made" signal.** `AttemptDeliveryUseCase.attempt()`'s return type
   `AttemptDeliveryResult(DeliveryStatus, AttemptOutcome)` had no way to express "claim lost" or
   "deferred by bulkhead/circuit" without fabricating a fake `AttemptOutcome`. Fixed by widening
   `outcome` to `Optional<AttemptOutcome>` (empty iff no HTTP attempt made) — not a new enum value,
   a port-contract widening. Chosen over a `boolean attempted` flag because that leaves the illegal
   state representable (flag false, outcome still populated). Two factories:
   `AttemptDeliveryResult.noAttempt(status)` / `.of(status, outcome)`.
2. **`RandomGenerator.getDefault()` is not documented thread-safe for concurrent use** — its javadoc
   expects per-thread `split()`. Sharing it as a singleton Spring bean across thousands of virtual
   threads (the worker's jitter/backoff source) would be a real bug. Fixed with
   `RandomGeneratorFactory.of("Random").create()` (legacy `java.util.Random`, CAS/synchronized-
   guarded, documented safe for concurrent `nextX()`), not `SecureRandom` — jitter is a thundering-
   herd mitigation, not a security control, so no crypto-strength generator needed.
3. **SSRF app-level vs network-level split, and the local-demo trap.** App-level SSRF validation
   (HTTPS-only, per-attempt DNS resolution, private/reserved-range rejection including
   `169.254.169.254`, no cached verdict) is implemented now, not deferred — only network egress
   controls (security groups) wait. The local stub webhook receiver runs plain HTTP, which the
   validator would reject even with a host allowlist entry, breaking the local demo. Resolved as a
   single AND-gated waiver: `host ∈ challenge.egress.allowed-hosts && Environment` reports the
   `local` profile active — both resolved once at construction (profile check is not re-read per
   call). An earlier draft used a second boolean flag (`allow-plaintext-to-allowed-hosts`) plus a
   startup guard bean; explicitly superseded and removed — a single shared predicate in the class
   that owns the decision was judged simpler and equally safe. TLS-in-front-of-the-stub was
   considered and rejected (private key in repo, or a profile-specific `SSLContext` weakening the
   exact TLS path ADR-004 §2 protects — worse trade than a scheme-check waiver).
4. **DNS-failure vs policy-rejection must not collapse into one `AttemptOutcome`.** Initial draft
   mapped every validator rejection to `RETRYABLE`. Corrected: DNS failure (timeout, NXDOMAIN) is
   transient -> `RETRYABLE` (counts toward breaker); a resolved-but-blocked target (private range,
   metadata IP, non-HTTPS outside the waiver) is a permanent config problem -> `NON_RETRYABLE` ->
   `DEAD` (does NOT count toward breaker — it says nothing about the client endpoint's health). Both
   record a `delivery_attempts` row with `error` set and `http_status` absent, per ADR-003 §3's
   existing "failures before HTTP" provision — no new enum value or column needed either way.
5. **`delivery_id` missing from SQS message attributes** (gap in already-merged FEAT-005/006 code) —
   the DLQ consumer's entire purpose (correlate + mark `FAILED` on an undeserializable body) was
   unimplementable without it. Fixed in `SqsNotificationQueueAdapter`, sequenced first in FEAT-007
   since TASK-007-17/18 depend on it. `traceparent` checked and confirmed to NOT share the defect
   (already sent as an attribute, plus a `deliveries.trace_context` DB fallback).
6. **`Retry-After` handling** — must clamp out-of-range values to the breaker's max cooldown (not
   reject them), and must accept both delta-seconds and RFC 7231 HTTP-date format, not just numeric
   seconds. A date-only value being treated as "unparseable" was the original (wrong) draft.

### Technical Decisions
- Circuit breaker thresholds/cooldowns are profile-specific, not one fixed set: production
  10 failures / 30s base / 1h cap / 30s→1m→2m→…→1h; local/demo 3 failures / 10s base / 60s cap /
  10s→20s→40s→60s. Full rationale in `feature.md`'s ambiguity table, not restated here.
- Implementing agents worked in dependency-ordered waves (parallel within a wave, one agent per
  task file); tasks sharing a target file (e.g. two agents both editing `application.yaml`) were
  deliberately serialized rather than run concurrently, to avoid a last-write-wins clobber.

## Status at End
- Completed: FEAT-007 all 21 tasks + 7 follow-up addenda `Ready for Review` (status verified on
  disk); `./gradlew compileJava compileTestJava` green after every wave. TASK-007-21's own security
  review found no blocking findings.
- Nothing committed to git. User reviews/commits manually.
- User has not yet triggered `./gradlew test` (Testcontainers/LocalStack) — explicitly deferred
  until all FEAT-007 tasks were done; still pending as of session end.

## Notes for Next Session
- `docs/concerns.md` still carries (unchanged from FEAT-006, both explicitly non-blocking for
  FEAT-007 per Tech Lead 2026-09-21): unauthenticated ingest endpoint (mock-generated events for
  now, tracked separately from SSRF as amplification/abuse of existing subscription targets, not
  SSRF itself), and the still-open `software.amazon.awssdk:sqs:2.27.8` CVE scan.
  `resilience4j-core:2.3.0`'s transitive Kotlin stdlib pull was noted informationally during the
  A03 review — no CVE, not logged as a concerns.md entry.
- Next action is the user's: trigger `./gradlew test` for the full Testcontainers/LocalStack suite
  across FEAT-007, then review/commit the working tree.
