---
id: TASK-007-05
feature: FEAT-007
title: WebhookResponseMapper — pure HTTP result to WebhookResponse mapping
status: Ready for Review
agent: backend-engineer
depends_on: []
date: 2026-09-21
---

# TASK-007-05: `WebhookResponseMapper`

## Feature

FEAT-007

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/webhook/WebhookResponseMapper.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/out/webhook/WebhookResponseMapperTest.java` (new)
- Concern: every decision the HTTP adapter makes that is **not** I/O, extracted as pure static
  functions so it is unit-testable without a socket.

### Why this is its own class

TASK-007-06's adapter should contain one `HttpClient.send` call and nothing else worth testing.
Everything around it — classifying an exception into a `TransportFailure`, truncating the body,
parsing `Retry-After` — is pure and belongs here, where a plain JUnit test can cover it. This is
the same split the merged `ResponseClassifier` already uses on the domain side.

### The three functions

1. **`TransportFailure toTransportFailure(Throwable)`**
   - `HttpTimeoutException` / `HttpConnectTimeoutException` -> `TIMEOUT`
   - `javax.net.ssl.SSLException` (and subtypes) -> `TLS_FAILURE`
   - `UnknownHostException` -> `DNS_FAILURE`
   - `ConnectException`, `SocketException`, `IOException` with a reset -> `CONNECTION_RESET`
   - anything else -> `CONNECTION_RESET` (the conservative retryable default; do not return
     `NONE`, which would mean "read the status code" when there is no status code)
   - Unwrap one level of `CompletionException`/`ExecutionException` before classifying.

2. **`Optional<String> truncate(String body, int limit)`**
   - empty or null body -> `Optional.empty()`
   - longer than `limit` -> the first `limit` characters, no ellipsis marker that could be
     mistaken for client content
   - the result feeds `delivery_attempts.response_excerpt` (ADR-003 §3), so it must never carry
     a header, a signature, or anything the adapter added.

3. **`Optional<Duration> parseRetryAfter(String headerValue, Instant now, Duration max)`**

   **Both RFC 7231 forms are accepted, and an over-long value is clamped rather than rejected**
   (Tech Lead correction, 2026-09-21):

   | Input | Result |
   |---|---|
   | `120` (delta-seconds) | `Duration.ofSeconds(120)` |
   | `Wed, 21 Oct 2026 07:28:00 GMT` (HTTP-date) | that instant minus `now`, if positive |
   | a positive value greater than `max` | **`max`** — clamped, not discarded |
   | `0`, a negative value, a past HTTP-date | `Optional.empty()` — fall back to the backoff schedule |
   | unparseable text | `Optional.empty()` — a normal outcome, never an exception |

   - **An HTTP-date is not illegible just because it is not numeric.** Parse it with
     `DateTimeFormatter.RFC_1123_DATE_TIME`; try the numeric form first, then the date form, and
     only then give up. Getting this wrong means every date-form client is silently treated as
     having sent garbage.
   - Clamping to `max` is deliberate and `max` deliberately equals the breaker's own
     `max-cooldown` for the active profile (1h production, 60s local, TASK-007-02): a client
     cannot park a subscription for longer than the breaker's worst-case cooldown, but a client
     asking for a long pause still gets the longest pause we are willing to give rather than the
     short backoff it would get from an outright rejection.
   - `Optional.empty()` is never an error condition. The caller falls back to the retry schedule.
   - `now` is a parameter, never `Instant.now()` inside, so the HTTP-date branch is deterministic
     in a test.
   - Never throw. A malformed header is client input (A10).

All three are `static`, the class is `final` with a private constructor, and it holds no state.
One-line javadoc each; no ADR-citing paragraphs.

## Out of Scope

- The adapter itself and any `HttpClient` usage (TASK-007-06).
- `ResponseClassifier` — status-code-to-outcome classification is merged and must not be
  duplicated here. This class never returns an `AttemptOutcome`.
- Any logging. A mapper that logs a response body would violate ADR-002 §3.1's PII rule.

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker. This class is pure, so its test is a straightforward table of inputs.

Required unit scenarios:
- each exception type above maps to its `TransportFailure`, including one wrapped in a
  `CompletionException`
- an unrecognised `Throwable` maps to `CONNECTION_RESET`, never `NONE`
- truncation returns `Optional.empty()` for null and for empty, and exactly `limit` characters for
  an over-long body
- `Retry-After: 120` parses to two minutes
- an HTTP-date `Retry-After` (`Wed, 21 Oct 2026 07:28:00 GMT`) parses against the injected `now`
  to the correct positive duration — **not** to `Optional.empty()`
- an HTTP-date in the past returns `Optional.empty()`
- `Retry-After: 7200` with `max = 1h` returns exactly **1h** (clamped, not empty)
- an HTTP-date far in the future is clamped to `max` the same way
- `Retry-After: 0`, a negative value and unparseable garbage each return `Optional.empty()`
- no input causes a throw

**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileJava
compileTestJava` (or the IDE's compile) and report that the tests are written and pending the Tech
Lead's later explicit run.

## Acceptance Criteria

- [ ] Three static functions with the signatures above; the class is final, stateless, private ctor.
- [ ] `parseRetryAfter` accepts **both** the delta-seconds and the HTTP-date form.
- [ ] A positive value above `max` is **clamped to `max`**, never discarded.
- [ ] Zero, negative, past-date and unparseable inputs return `Optional.empty()` without throwing.
- [ ] No `Instant.now()`, no `System.currentTimeMillis()`, no randomness inside the class.
- [ ] No logging, no Spring annotation, no `HttpClient` type in any signature.
- [ ] `AttemptOutcome` is never produced here.
- [ ] Every unit scenario listed above is covered by a plain JUnit test.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer).

## Definition of Done

Code written, tests written but **not run**. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
