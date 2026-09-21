---
id: TASK-007-06
feature: FEAT-007
title: JdkWebhookClientAdapter — WebhookClientPort over the JDK HttpClient
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-007-02, TASK-007-05]
date: 2026-09-21
---

# TASK-007-06: `JdkWebhookClientAdapter`

## Feature

FEAT-007

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/webhook/JdkWebhookClientAdapter.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/out/webhook/config/WebhookHttpClientConfig.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/out/webhook/JdkWebhookClientAdapterTest.java` (new)
- Concern: the first and only implementation of the merged `WebhookClientPort`. One POST, strict
  timeouts, no redirects, no interpretation.

### Behavior

```
send(WebhookRequest) -> WebhookResponse
  build POST to request.targetUrl() with request.body() and every entry of request.headers()
  connect timeout  = challenge.worker.connect-timeout (2s)
  request timeout  = challenge.worker.read-timeout    (5s)
  on response  -> new WebhookResponse(status, NONE, elapsedMs,
                    WebhookResponseMapper.truncate(body, responseExcerptLimit),
                    WebhookResponseMapper.parseRetryAfter(header, now, retryAfterMax))
  on throwable -> new WebhookResponse(0, WebhookResponseMapper.toTransportFailure(t), elapsedMs,
                    Optional.empty(), Optional.empty())
```

- **`HttpClient.Redirect.NEVER`.** ADR-004 §1 makes 3xx terminal and explains why (an unvalidated
  redirect is the SSRF smuggling vector). The client must not follow one, and the adapter must not
  re-issue the request against a `Location` header.
- **TLS validation is never disabled.** No custom `SSLContext`, no trust-all manager, not even
  behind a property. ADR-004 §2 is explicit.
- **Reject a non-HTTPS `targetUrl`** by returning `TLS_FAILURE` rather than calling it. This is a
  placeholder: TASK-007-20 replaces it with a call to `OutboundUrlValidator`, which enforces the
  same rule plus DNS-resolved private-range rejection. Implement it here so this task stands
  alone; expect it to be deleted.
- **The adapter never throws.** Every failure becomes a `WebhookResponse` carrying a
  `TransportFailure`, because the use case's classification path expects a value, not an exception
  (`ResponseClassifier.classify(int, TransportFailure)`).
- **The adapter never re-serializes and never adds a header.** The body and the full header map
  arrive already built and already signed (TASK-007-08, TASK-007-09); changing a single byte would
  invalidate the signature.
- **No logging of the URL, the body, the response body, or any header.** ADR-002 §3.1's PII rule
  plus A09. Latency and status code are fine, but prefer to leave logging to the use case.
- `WebhookHttpClientConfig` builds one shared `HttpClient` bean (`Redirect.NEVER`, connect timeout
  from `WorkerProperties`). One instance for the process; the JDK client is thread-safe and its
  blocking `send` unmounts a virtual thread correctly. **No `synchronized` anywhere**, no executor
  of the adapter's own.

## Out of Scope

- The pure mapping functions — TASK-007-05 owns them; call them, do not reimplement.
- Signing, the envelope and header construction (TASK-007-08, -09, TASK-007-14).
- SSRF validation proper — DNS resolution, private/link-local/metadata range denial, the
  local-profile allowlist. **Not deferred, just not yours**: TASK-007-19 builds it and
  TASK-007-20 wires it into this file.
- Retry logic of any kind. Retries are a `deliveries`-row concern, never an in-adapter loop.

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker, **and no real socket**. Keep the adapter testable by injecting the `HttpClient` through
the constructor and passing a hand-written stub/mock of it (Mockito is already on the classpath via
the Boot test starters).

Required unit scenarios:
- a 200 response produces `statusCode = 200`, `TransportFailure.NONE` and a truncated excerpt
- a 500 response is passed through unchanged — the adapter classifies nothing
- a thrown `HttpTimeoutException` produces `TransportFailure.TIMEOUT` and does not propagate
- the request built carries every header from `WebhookRequest` verbatim, and the body byte-for-byte
- the client is configured with `Redirect.NEVER` (assert on the builder/config, not by following one)
- a `http://` target returns `TLS_FAILURE` without any call being issued

**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileJava
compileTestJava` and report the tests as written and pending the Tech Lead's later explicit run.

## Acceptance Criteria

- [ ] Implements `WebhookClientPort` with no signature change.
- [ ] `Redirect.NEVER`; no code path reads or acts on a `Location` header.
- [ ] No custom `SSLContext`, no trust-all, no property that could disable TLS validation.
- [ ] Connect and read timeouts come from `WorkerProperties`, defaulting to 2s and 5s.
- [ ] The method never throws; every failure becomes a `TransportFailure`.
- [ ] Body and headers are sent exactly as received; nothing is added, removed or re-encoded.
- [ ] No `synchronized`, no `ThreadLocal`, no per-call `HttpClient`, no executor.
- [ ] No URL, body, response body, header or secret appears in any log statement.
- [ ] Every unit scenario listed above is covered with an injected stub `HttpClient`.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] The handover notes that full SSRF validation arrives in TASK-007-19/-20 and that this
      task's `http://` check is the placeholder they replace.

## Definition of Done

Code written, tests written but **not run**. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
