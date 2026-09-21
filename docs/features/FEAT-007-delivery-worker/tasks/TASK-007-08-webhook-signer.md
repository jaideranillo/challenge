---
id: TASK-007-08
feature: FEAT-007
title: WebhookSigner — HMAC-SHA256 over timestamp and body, with the rotation-window signature
status: Ready for Review
agent: security-engineer
depends_on: []
date: 2026-09-21
---

# TASK-007-08: `WebhookSigner`

## Feature

FEAT-007

## Assigned Agent

`security-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/domain/policy/WebhookSigner.java` (new)
  - `src/test/java/com/cobre/challenge/domain/policy/WebhookSignerTest.java` (new)
- Concern: one pure function from (body, timestamp, secrets) to the signature headers. No I/O, no
  Spring, no clock.

### Why it lives in `domain/policy`

ADR-004 §2: "HMAC location: computed in `AttemptDeliveryUseCase` (domain-adjacent, framework-free
— a pure function of body + timestamp + per-subscription secret), immediately before the call
reaches `WebhookClientPort`, **never in the adapter**." `domain/policy` is where this repo already
keeps stateless decision rules over the aggregates (`ResponseClassifier`, `RetryPolicy`) — see
`docs/concerns.md`'s note on that package choice.

### The scheme

ADR-004 §2 leaves "digest algorithm, canonicalization, header encoding" as a named follow-up. This
task fixes them as **labelled implementation choices** (FEAT-007 ambiguity 1), logged in
`docs/concerns.md`, not as ADR decisions:

| Choice | Value |
|---|---|
| Algorithm | `HmacSHA256` |
| Signing string | `<X-Cobre-Timestamp header value> + "." + <body>`, body as UTF-8 |
| Encoding | lowercase hexadecimal |
| Current-secret header | `X-Cobre-Signature` |
| Rotation-window header | `X-Cobre-Signature-Previous`, present only while a previous secret is live |

ADR-004 §2's own note states the two-header shape is preferred over one comma-joined value, and
why: the previous header's mere absence needs no delimiter logic to detect.

### Contract

```java
/** The signature headers for one attempt; previous is present only during a rotation window. */
Map<String, String> sign(String body, String timestampHeaderValue,
                         String currentSecret, Optional<String> previousSecret);
```

- Returns an immutable map containing `X-Cobre-Signature` always, and
  `X-Cobre-Signature-Previous` only when `previousSecret` is present.
- **The caller decides whether the rotation window is open** by comparing
  `previous_secret_expires_at` to now; this function never looks at a clock. An expired window
  means the caller passes `Optional.empty()`.
- Class is `final` with a private constructor and static methods, or a stateless instance — pick
  one and be consistent with `ResponseClassifier`.
- **No logging in this file, at any level.** No secret, no signature, no body in an exception
  message either: throw `IllegalArgumentException` with a message naming the *argument*, never its
  value.
- Do not implement verification. This service signs; it never verifies its own signature. A
  verification helper would be dead code (YAGNI) and a second place for the scheme to drift.

## Out of Scope

- Building the envelope or serializing it (TASK-007-09).
- Resolving `secret_ref` to material (TASK-007-07) — secrets arrive as parameters.
- Choosing or formatting the `X-Cobre-Timestamp` value, and the `X-Cobre-Delivery-Id` header
  (TASK-007-14 builds the full header map).
- Rotation triggers, window length, or any subscription write.

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker. This class is pure, so its test is exact-value based.

Required unit scenarios:
- a known (body, timestamp, secret) triple produces a known hex digest — compute the expected
  value independently (e.g. a checked-in constant derived from an HMAC-SHA256 reference), not by
  calling the class under test
- the output is lowercase hex and of the length SHA-256 implies
- changing one byte of the body changes the signature; changing the timestamp changes it too
  (proving the timestamp is genuinely covered, which is the whole replay-protection property)
- `previousSecret` present produces both headers, with different values
- `previousSecret` empty produces exactly one header, and the map contains no null value
- the returned map is immutable

**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileJava
compileTestJava` and report the tests as written and pending the Tech Lead's later explicit run.

## Acceptance Criteria

- [ ] `HmacSHA256`, signing string `timestamp + "." + body`, lowercase hex, exactly as tabled.
- [ ] Framework-free: no Spring, no Jackson, no adapter type, no clock, no randomness.
- [ ] Both header names spelled exactly `X-Cobre-Signature` and `X-Cobre-Signature-Previous`.
- [ ] The previous-signature header is absent, not empty or null, when no previous secret is given.
- [ ] No secret, signature or body value reaches a log statement or an exception message.
- [ ] No verification method exists.
- [ ] Every unit scenario listed above is covered, including an independently-derived expected digest.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] The three scheme choices are noted in the handover as FEAT-007 inferences for TASK-007-19.

## Definition of Done

Code written, tests written but **not run**. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
