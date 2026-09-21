---
id: TASK-007-21
feature: FEAT-007
title: Security review of the delivery-worker slice
status: Ready for Review
agent: security-engineer
depends_on: [TASK-007-14, TASK-007-15, TASK-007-17, TASK-007-18, TASK-007-19, TASK-007-20]
date: 2026-09-21
---

# TASK-007-21: Security review of the worker slice

## Feature

FEAT-007

## Assigned Agent

`security-engineer`

## Scope

- File(s):
  - `docs/concerns.md` (modified — findings that are deviations or open exposures)
  - at most one or two small production fixes, each of which must be a one-line-scope change; a
    finding needing more than that is written up, not fixed here
- Concern: review the whole FEAT-007 slice as shipped. Read-first, write-almost-nothing.

### What to review, and against what

Read every file produced by TASK-007-01 through TASK-007-20, plus `feature.md`'s
**Security Impact** and **Ambiguities resolved by inference** sections, then check:

1. **A04 — signing and secrets.** The HMAC scheme is as `feature.md` fixes it (HMAC-SHA256,
   `timestamp + "." + body`, lowercase hex, two headers). The signed bytes are the sent bytes —
   nothing re-serializes between signing and the socket. No secret reaches a log line, an
   exception message, a generated `toString()`, a span attribute, `delivery_attempts`, or a
   committed file. The rotation window honors `previous_secret_expires_at` and stops emitting the
   second header once it closes. Grep for `challenge.webhook.secrets` in tracked files.
2. **A01 — application-level SSRF, which this feature DOES close.** Verify `OutboundUrlValidator`
   (TASK-007-19) and its wiring (TASK-007-20) against the rules: HTTPS only; every resolved
   address checked, not just the first; loopback, any-local, private, link-local (including
   `169.254.169.254`), multicast and IPv4-mapped forms all rejected; DNS failure fails closed;
   validation on **every** attempt with no cached verdict, which is the DNS-rebinding defense;
   the validator called before any socket is opened. Then check the trap: **grep every profile
   for `challenge.egress.allowed-hosts` and confirm it exists only in `application-local.yaml`**,
   not as an empty list or a commented-out example anywhere else. Confirm the
   default-profile-rejects-localhost-and-metadata test exists and would fail if the allowlist
   leaked. Then verify the classification, which is no longer a gap: a `POLICY_REJECTED` verdict
   maps to the **existing** `NON_RETRYABLE` -> `DEAD` (ADR-004 §1), is excluded from the breaker,
   records an attempt row with `error` set and `http_status` absent, and raises the
   `notification.webhook.egress.rejected` counter plus a warn log carrying host and resolved
   address; a `DNS_FAILURE` verdict maps to `RETRYABLE` and does count. Confirm **no**
   `AttemptOutcome` or `TransportFailure` value was added to make this work — if one was, that
   is a finding.

   **Then check the plaintext waiver decided on 2026-09-21** (TASK-007-19 addendum 19a,
   TASK-007-20 addendum 20a), which is the highest-risk thing in this slice. The waiver lets an
   allowlisted host be reached over plain HTTP, and it must require **both** conditions: the host
   in `challenge.egress.allowed-hosts` **and** the `local` profile active. Try to defeat it —
   an allowlisted host under the default profile, a non-allowlisted host under `local`, an
   allowlist entry planted in `application.yaml` — and report what you find. **A waiver that
   turns out to need only one condition is a blocking finding.** Confirm the profile is resolved
   once at construction rather than per call, and that no `allow-plaintext-to-allowed-hosts`
   property or startup-guard bean was introduced (an earlier draft specified them; they were
   superseded and must not be present).
   **Network-level** egress controls (security groups, controlled egress path) remain deferred
   infrastructure work — confirm that framing in `docs/concerns.md` and do not design them here.
3. **A07 — unauthenticated ingest, deprioritized but still open.** The entry in
   `docs/concerns.md` stands; the Tech Lead's call of 2026-09-21 is that it does **not** block
   FEAT-007, since the events reaching the endpoint are currently mock-generated. Confirm the
   entry reflects that, and confirm it is written as its **own** problem: an attacker triggering
   deliveries to *existing subscription targets* is amplification and abuse of the pipeline, not
   SSRF — the destination URL comes from `subscriptions`, never from the inbound event. Keep the
   two tracked separately; do not restate them as one combined exposure.
4. **A05 — injection.** No SQL is written by this feature, but confirm no worker code
   concatenates a value into any statement, and that `last_error` and `response_excerpt` are
   bound parameters through the merged adapters.
5. **A09 — logging.** `content`, `response_excerpt`, target URLs, headers and signatures never
   reach a log line, an MDC key or a span attribute. The one deliberate exception is the DLQ
   consumer's uncorrelatable-message branch (ADR-004 §1) — confirm it is scoped to exactly that
   branch. Confirm no meter carries a `client_id` or `subscription_id` tag (ADR-002 Q8).
6. **A10 — exceptional conditions.** For each of the five failure points in `feature.md`'s A10
   row, confirm the behavior is the one specified: the outcome transaction fails closed; the
   message is deleted on every business path and only on those; the loops survive a `Throwable`;
   an unresolvable secret fails closed with no unsigned request; nothing calls
   `ChangeMessageVisibility` (grep the whole `src/main` tree).
7. **A08 — the double-POST guard.** The `status = 'QUEUED'` conditional claim is the only thing
   preventing a duplicate send. Confirm no path sends before checking the affected-row count, and
   no code weakens the guard.
8. **A03 — supply chain.** Review the two Resilience4j artifacts and their transitive tree: exact
   pinned versions, no Spring Boot starter, no unexpected transitive addition. Note that the
   `software.amazon.awssdk:sqs` review already logged in `docs/concerns.md` is still open.

### Output

A written review, plus `docs/concerns.md` entries for every finding that is an open exposure or a
deviation from ADR text. Each finding: what, where (file and line), which OWASP category, and the
smallest fix. Rank them, and say plainly which ones should block deployment to anywhere reachable
by untrusted traffic.

## Out of Scope

- Designing or implementing the SSRF/egress control, producer authentication, RLS, `TenantId`, or
  the self-service API's security (ADR-007's own breakdown).
- Rewriting anyone else's task. A finding that needs real work becomes a new task, proposed to the
  Architect — not an edit you make here.
- Any ADR edit. Findings go in `docs/concerns.md`.

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker. Add a unit test only if a finding you fix needs one to be meaningful.

**Do not run `./gradlew test` or `./gradlew build`.** Verify anything you change with
`./gradlew compileJava compileTestJava`. The Tech Lead runs the suite once every FEAT-007 task is
complete; say so in your report rather than implying the slice is proven.

## Acceptance Criteria

- [ ] All eight review areas above are covered explicitly, each with a verdict.
- [ ] A repository-wide grep confirms `ChangeMessageVisibility` appears nowhere in `src/main`.
- [ ] A repository-wide grep confirms no committed secret value and no `client_id` /
      `subscription_id` meter tag.
- [ ] A repository-wide grep confirms `challenge.egress.allowed-hosts` appears **only** in
      `application-local.yaml`, in no form elsewhere, and that
      `allow-plaintext-to-allowed-hosts` exists nowhere at all.
- [ ] The plaintext waiver is proven to require **both** the allowlist entry and the `local`
      profile, with an attempt made to defeat each condition separately.
- [ ] No TLS, keystore or `server.ssl` configuration was added for the local stub.
- [ ] Application-level SSRF validation is verified as implemented (not deferred), including the
      per-attempt resolution, the default-profile rejection test, the `NON_RETRYABLE` -> `DEAD`
      classification, the breaker exclusion, and the rejection counter plus warn log.
- [ ] No `AttemptOutcome` or `TransportFailure` value, and no column, was added anywhere in the
      slice.
- [ ] Network-level egress controls are confirmed as the only deferred half.
- [ ] `docs/concerns.md` keeps unauthenticated ingest and SSRF as two separate entries, with the
      ingest entry marked non-blocking for FEAT-007 and described as amplification/abuse of
      existing subscription targets, not SSRF.
- [ ] Every finding names file, line, OWASP category and smallest fix, and is ranked.
- [ ] At most two small production fixes are made; anything larger is written up instead.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.

## Definition of Done

Review written and `docs/concerns.md` updated. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
