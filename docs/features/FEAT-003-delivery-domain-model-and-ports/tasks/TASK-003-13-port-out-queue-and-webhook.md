---
id: TASK-003-13
feature: FEAT-003
title: Outbound ports — queue and webhook client
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-003-05, TASK-003-06]
date: 2026-09-20
---

# TASK-003-13: Outbound ports — queue and webhook client

## Feature

FEAT-003

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

The two external-system `port/out` interfaces from ADR-005 §1, plus the transport-neutral records they exchange. **Interfaces only. No SQS client, no `HttpClient`, no adapter.**

> **Addendum (2026-09-20):** originally the pointer-envelope and request/response records were nested inside their port file. Superseded by user request — extracted to `dto` subpackages. See `docs/concerns.md`.

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/out/queue/NotificationQueuePort.java`
  - `src/main/java/com/cobre/challenge/application/port/out/queue/dto/DeliveryPointer.java`
  - `src/main/java/com/cobre/challenge/application/port/out/webhook/WebhookClientPort.java`
  - `src/main/java/com/cobre/challenge/application/port/out/webhook/dto/WebhookRequest.java`
  - `src/main/java/com/cobre/challenge/application/port/out/webhook/dto/WebhookResponse.java`
- Concern: the outbound contracts for the queue and the client endpoint.

**`NotificationQueuePort`** — the single publisher adapter of ADR-004 §1 ("both publishers write the same envelope through the same adapter").
- `void publish(DeliveryPointer pointer)` and `void publishBatch(List<DeliveryPointer> pointers)` — the gateway's `SendMessage` and the relay's `SendMessageBatch` are the only two call sites, so both are on this one port and nowhere else.
- `DeliveryPointer` is a flat record of **exactly four scalar fields** (ADR-004 §1): `deliveryId` (UUID), `subscriptionId` (UUID), `attemptHint` (int, advisory), `traceparent` (`Optional<String>`). No nested document, no event content, no target URL, no secret. That flatness is a stated design property — "a flat envelope of fixed arity has no independently-malformable part" — so the record must not grow a fifth structured component.

**`WebhookClientPort`** — the outbound POST.
- One method taking a request record (`targetUrl` as `String`, body as `String`, and the headers the worker must send: signature, timestamp, delivery id — ADR-004 §1.1) and returning a response record.
- The response record is **transport-neutral and feeds `ResponseClassifier` directly**: `statusCode` (int, `0` when no response), `failure` (`TransportFailure`), `responseTimeMs` (int), `responseExcerpt` (`Optional<String>`, already truncated), `retryAfter` (`Optional<Duration>`, parsed by the adapter for the 429 path of ADR-004 §1). No `HttpResponse`, no `ResponseEntity`, no exception type crosses this boundary — the adapter converts its client library's failures into `TransportFailure` values.

## Out of Scope

- **No implementation.** No `adapter/out/messaging`, no `adapter/out/http`, no AWS SDK import, no `java.net.http` import in these files.
- No HMAC computation. ADR-004 §2 places signing in `AttemptDeliveryUseCase`, before the call reaches this port; the port receives an already-computed signature header value.
- No SSRF/egress allow-list validation — an adapter and security-engineer concern (ADR-002's A01-SSRF row), not a port signature.
- No redirect following, and no option to enable it: ADR-004 §1 makes 3xx terminal. The request record must carry no `followRedirects` flag.
- No retry or timeout configuration on the port. Timeouts are adapter configuration (ADR-004 §1's budget table); retry is the pipeline's, via `RetryPolicy`.
- No `ClockPort`, no `responseTimeMs` measured inside the domain — the adapter measures and reports it.

## Acceptance Criteria

- [ ] `DeliveryPointer` has exactly the four fields listed, all scalar, with no nested record.
- [ ] The webhook response record's fields are exactly what `ResponseClassifier.classify(int, TransportFailure)` and the `delivery_attempts` insert need — nothing carrying a client-library type.
- [ ] No `followRedirects` option exists anywhere in the request record.
- [ ] `responseExcerpt` is documented as already truncated and as never containing signature headers or secrets (ADR-003 §3, A09).
- [ ] No import from `software.amazon`, `java.net.http`, `org.springframework`, or any messaging/HTTP library.
- [ ] Tests written and passing — compile-level for interface-only files; add a record-validation test if a compact constructor validates (e.g. rejecting a `null` `deliveryId`).
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. Note the two controls this contract preserves: A01-SSRF (no redirect option) and A09 (no secret in the excerpt). If either cannot hold as written, flag to `security-engineer`.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
