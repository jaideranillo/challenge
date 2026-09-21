---
id: TASK-007-20
feature: FEAT-007
title: Wire egress validation into the attempt pre-flight, with the security signal and local allowlist
status: Ready for Review
agent: security-engineer
depends_on: [TASK-007-06, TASK-007-14, TASK-007-19]
date: 2026-09-21
---

# TASK-007-20: Wire `OutboundUrlValidator` into the outbound path

## Addendum 20a (2026-09-21, revised): the local allowlist comment, and nothing else

**Status: Ready for Review (devops-engineer).** Pairs with TASK-007-19's addendum 19a.

**No TLS setup for the local stub** — the Tech Lead ruled that overkill for local dev. The
allowlist entry now waives HTTPS as well as the private-range check, gated on the `local`
profile being active (19a). **This addendum shrank as a result**: an earlier draft added a second
boolean property and a fail-fast startup guard to police it. Neither is needed now, because the
second condition is the Spring profile itself rather than another YAML key, so there is no flag
to police. **Do not implement that guard**, and do not add
`allow-plaintext-to-allowed-hosts` — it does not exist.

What remains is one comment correction in `application-local.yaml`:

```yaml
challenge:
  egress:
    # Local demo only. Waives BOTH the HTTPS requirement and the private-range
    # check for these hosts; applies only while the `local` profile is active.
    allowed-hosts: [localhost]
```

`application.yaml` gets no `challenge.egress` key, in any form — not empty, not commented out.

### Acceptance criteria for 20a

- [ ] `challenge.egress.allowed-hosts` exists only in `application-local.yaml`.
- [ ] Its comment says the entry waives HTTP as well as the private-range check, and that it
      applies only under the `local` profile.
- [ ] `application.yaml` contains no form of that key.
- [ ] The one-line warning at the top of `application-local.yaml` mentions that this profile
      permits plaintext egress to allowlisted hosts.
- [ ] No `allow-plaintext-to-allowed-hosts` property and no startup-guard bean exist anywhere.
- [ ] No TLS, certificate, keystore or `server.ssl` configuration is added for the stub.

## Addendum 20b (2026-09-21): the security log carries the resolved address and the verdict reason

**Status: Ready for Review (security-engineer).** Depends on TASK-007-19's addendum 19b, which
puts the resolved address on the verdict.

Two small changes in `AttemptDeliveryUseCaseImpl`'s pre-flight branch:

1. The warn-level rejection log includes `EgressVerdict.resolvedAddress()` when present,
   alongside the `delivery_id`, `subscription_id` and host it already carries. Without it the
   alert cannot distinguish a metadata-endpoint probe from an ordinary misconfiguration.
2. The verdict's **reason string** is passed through as the attempt's `error`, instead of the
   bare outcome name. See addendum 12a on TASK-007-12, which makes the writer honor it.

### Acceptance criteria for 20b

- [ ] The warn log includes the resolved address on a range-based rejection and omits it
      cleanly (no `null`, no empty key) on a scheme rejection or DNS failure.
- [ ] The verdict reason reaches `AttemptOutcomeCommand.error`, verbatim.
- [ ] Still no content, response body, header or secret on this path.
- [ ] `DNS_FAILURE` still emits no security counter and no warn-level alert.

## Feature

FEAT-007

## Assigned Agent

`security-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/usecase/AttemptDeliveryUseCaseImpl.java` (modified)
  - `src/main/java/com/cobre/challenge/adapter/out/webhook/JdkWebhookClientAdapter.java` (modified)
  - `src/main/resources/application-local.yaml` (modified)
  - test updates in the two existing test classes for those files
- Concern: make the validator run on every attempt, classify its two verdicts correctly, raise
  the security signal, and unblock the local demo without loosening any other profile.

### Where the call goes, and why not in the adapter

The validator is called from `AttemptDeliveryUseCaseImpl`, in step 5, **immediately before**
`webhookClientPort.send(...)` — not inside the adapter. The reason is classification: the two
verdicts map to two different `AttemptOutcome` values, and `WebhookResponse` has no way to say
"permanently rejected" (it carries a `TransportFailure`, and every `TransportFailure` classifies
as `RETRYABLE`). Expressing it through the adapter would need a new `TransportFailure` value,
which is exactly the contract change this design avoids. The use case already owns classification,
so the branch belongs there.

"Validate at send time" is preserved: the call sits directly before the send, after the row and
subscription are loaded, on every attempt, with no cached verdict.

```
5. verdict = urlValidator.validate(subscription.targetUrl())

   ALLOWED          -> proceed: sign and POST, exactly as TASK-007-14 specifies

   DNS_FAILURE      -> no POST. outcome = AttemptOutcome.RETRYABLE
                       breaker: counts, exactly like any other DNS failure (ADR-004 §1)
                       writer command: httpStatus absent, error = verdict reason

   POLICY_REJECTED  -> no POST. outcome = AttemptOutcome.NON_RETRYABLE
                       breaker: NOT touched — recordFailure is not called, tripCircuit is not called
                       writer command: httpStatus absent, error = verdict reason
                       security signal (below)
```

`NON_RETRYABLE` already terminates in `DEAD` via `DeliveryOutcomeWriter`'s existing branch
(ADR-004 §1) and already returns `false` from `countsTowardCircuitBreaker()`. **Add no enum
value, no `TransportFailure`, no column, no port method.** If you find yourself wanting one,
stop and report it.

### Recording the attempt

Both verdict failures still write a `delivery_attempts` row, through the existing writer, in the
shape ADR-003 §3 already defines for a failure that happened before any HTTP exchange: `error`
populated with the verdict's reason, `http_status` **absent** (`OptionalInt.empty()`),
`responseTimeMs` the time spent validating, no `responseExcerpt`. The merged `DeliveryAttempt`
record and the merged insert already support this — no schema or model change.

### The security signal (A01, A09)

A `POLICY_REJECTED` verdict is a possible SSRF or DNS-rebinding attempt, not a routine dead
delivery, and must not be silent:

- **Counter** `notification.webhook.egress.rejected`, incremented once per rejection. **No
  `client_id` and no `subscription_id` tag** (ADR-002 Q8), same as every other meter here.
- **Log at warn**, structured, carrying `delivery_id`, `subscription_id`, the target host and the
  resolved address that triggered the rejection. This is a deliberate, narrowly scoped exception
  to the "never log the target URL" rule: the whole value of the signal is knowing *where* the
  target pointed. It does **not** extend to the event `content`, the response body, any header,
  or the secret — none of which may appear on this path either.
- A `DNS_FAILURE` verdict gets neither: it is ordinary transient noise and is already visible in
  the attempt-outcome counters.

### The adapter change

Remove the placeholder `http://` check TASK-007-06 added — the validator now owns scheme policy,
and two places deciding the same thing is how they drift apart. Add one line of javadoc stating
that callers must validate the target before calling `send`, and that
`AttemptDeliveryUseCaseImpl` is the only caller. Nothing else in the adapter changes: same
timeouts, same `Redirect.NEVER`, same headers, same body, no URL logging.

### The local-profile allowlist

`application-local.yaml` gains the stub receiver's host, and nothing else:

**Superseded in wording by addendum 20a above** (the comment now states the HTTPS waiver); the
key and its placement are unchanged.

```yaml
challenge:
  egress:
    # Local demo only. Waives BOTH the HTTPS requirement and the private-range
    # check for these hosts; applies only while the `local` profile is active.
    allowed-hosts: [localhost]
```

- **`application.yaml` must not gain this key in any form** — not empty, not commented out. A
  commented-out example is how this ends up uncommented in production.
- Add one line to `application-local.yaml`'s existing header warning noting that this key is a
  demo-only egress exception that permits plaintext.
- The HTTPS waiver is scoped to hosts on this list **and** to the `local` profile (TASK-007-19
  addendum 19a). No second property, no startup guard, no TLS setup for the stub.

## Out of Scope

- The validator's own logic and its tests (TASK-007-19).
- Network-level egress controls.
- Any change to `WebhookClientPort`, `WebhookResponse`, `TransportFailure`, `AttemptOutcome`,
  `DeliveryAttempt`, or any persistence port.
- The rest of the use case's flow — the claim, bulkhead, breaker gate and outcome write are
  TASK-007-14's and must not be restructured here.

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker. Extend the existing use case and adapter test classes with a stubbed validator.

Required unit scenarios:
- **`POLICY_REJECTED`**: `WebhookClientPort` is never called (`verifyNoInteractions`); the writer
  receives `NON_RETRYABLE` with `httpStatus` absent and the reason as `error`;
  `CircuitBreakerPort.recordFailure` and `SubscriptionRepositoryPort.tripCircuit` are **never**
  called; the rejection counter is incremented once.
- **`DNS_FAILURE`**: no POST; the writer receives `RETRYABLE`; `recordFailure` **is** called; the
  rejection counter is **not** incremented.
- **`ALLOWED`**: the flow is byte-for-byte what TASK-007-15 already asserts — the validator adds
  nothing to the happy path.
- The validator is consulted on **every** `attempt`, including two consecutive attempts for the
  same subscription (no cached verdict, which is the rebinding defense).
- The adapter no longer rejects `http://` itself, and never throws when handed any URL.

**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileJava
compileTestJava` and report the tests as written and pending the Tech Lead's later explicit run.

## Acceptance Criteria

- [ ] `urlValidator.validate(...)` is called in the use case immediately before the POST, on
      every attempt, with no cached verdict.
- [ ] `POLICY_REJECTED` maps to the existing `NON_RETRYABLE` -> `markDead`, opens no socket, and
      touches neither `recordFailure` nor `tripCircuit`.
- [ ] `DNS_FAILURE` maps to `RETRYABLE` and **does** count toward the breaker.
- [ ] Both write a `delivery_attempts` row with `error` set and `http_status` absent.
- [ ] No `AttemptOutcome`, `TransportFailure`, enum, column or port signature is added or changed.
- [ ] A rejection increments `notification.webhook.egress.rejected` (untagged) and logs at warn
      with `delivery_id`, `subscription_id`, host and resolved address — and nothing else.
- [ ] No content, response body, header or secret is logged on any of these paths.
- [ ] The duplicate `http://` check is removed from the adapter, which gains a one-line javadoc
      naming the validation precondition.
- [ ] `challenge.egress.allowed-hosts` is set **only** in `application-local.yaml`;
      `application.yaml` contains no form of that key.
- [ ] Every unit scenario listed above is covered, including the `verifyNoInteractions` assertions.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.

## Definition of Done

Code written, tests written but **not run**. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
