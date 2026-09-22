---
id: TASK-009-05
feature: FEAT-009
title: Move UnparsablePointerMessageException's cause sanitization from the logging site to construction time
status: Not Started
agent: backend-engineer
depends_on: [TASK-009-02]
date: 2026-09-21
---

# TASK-009-05: Sanitize `UnparsablePointerMessageException` at Construction

## Feature

FEAT-009

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's
a separate task, not scope creep on this one.

## Why this task exists

Architect ruling on TASK-009-02's review, 2026-09-21.

TASK-009-02 correctly identified a real ADR-008 §3.4 bypass: `UnparsablePointerMessageException`'s
own message is metadata-only, but the `cause` it chains is a Jackson parse/mapping exception whose
message quotes the raw SQS message body. Logging the exception whole prints that cause's message as
part of the stack trace.

TASK-009-02 fixed it at the **logging site** (`DeliveryQueueListener.logUnparsablePointer`), because
`PointerMessage.java` was outside that task's declared file scope. That was the right call for that
task. It is **not** the right permanent home, for two reasons:

1. **It protects exactly one call site.** The exception stays payload-carrying as an object. Any
   future `catch (UnparsablePointerMessageException e) { log.error("...", e); }` anywhere re-opens
   the leak, with no compile-time signal and no test that would catch it. That is precisely the
   "convention with no automated control behind it" weakness ADR-008's Consequences already names
   as the weakest link in §3.3's enforcement — and here it is removable structurally, at one place.
2. **The current workaround loses the exception class.** `logUnparsablePointer` builds a
   `new RuntimeException(e.getMessage())` carrying `e`'s stack trace in order to drop the cause
   chain. The log record therefore reports `java.lang.RuntimeException` as the thrown type, not
   `PointerMessage$UnparsablePointerMessageException`. ADR-008 §3.4 requires the **exception class**
   to be logged; this drops it.

ADR-008 §3.4's own second rule is the governing one: *"Where a wrapping exception is constructed by
application code, its message is built from metadata only and never by interpolating the raw
value."* `PointerMessage` **is** application code constructing a wrapping exception. Faithfully
chaining a cause whose message is the raw value defeats that rule as surely as interpolating it
would. Fixing it at construction makes the exception safe to log anywhere, which is the property the
rule is buying.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/messaging/dto/PointerMessage.java` (modified —
    `UnparsablePointerMessageException` stops chaining a payload-echoing cause and folds the cause's
    **class name** and **message length** into its own metadata-only message)
  - `src/main/java/com/cobre/challenge/adapter/in/messaging/DeliveryQueueListener.java` (modified —
    `logUnparsablePointer` collapses back to a plain `log.error("...", e)`, restoring the real
    exception class in the log record)
- Concern: where the §3.4 sanitization for one exception type lives.

## What to change

### 1. `UnparsablePointerMessageException` becomes safe to log whole

The constructor keeps receiving the offending `Throwable` — the caller has it and it is where the
metadata comes from — but the exception **must not retain it as its `cause`**. Instead:

- The exception's own message stays metadata-only and gains the cause's **class name** and its
  **message length** (an integer), e.g.
  `Unparsable pointer message delivery_id=<uuid> cause_class=<fqcn> cause_message_length=<n>`.
  `delivery_id` is an identifier and stays **unredacted and in full** (ADR-008 §3.3's explicit
  identifier exemption).
- **No part of the cause's message text, and no prefix or substring of it, appears anywhere on the
  exception.** Not in the message, not in a suppressed exception, not in a field.
- The cause is not chained. Choose whichever of these reads better; both are acceptable:
  - call `super(message)` (no cause) and expose nothing further, or
  - call `super(message, null)` explicitly.
  A sanitized surrogate `Throwable` carrying the cause's stack frames is **also** acceptable if you
  want the parser's frames preserved — but only if its own message is metadata-only. Do not
  preserve the frames at the cost of the message.
- The existing `deliveryId()` accessor and the existing throw sites in `PointerMessage.toCommand`
  are otherwise unchanged. The exception stays a `RuntimeException`; no signature outside this file
  changes.

### 2. `DeliveryQueueListener` stops working around it

With the exception safe, `logUnparsablePointer`'s synthetic `RuntimeException` is no longer needed
and is actively harmful (it reports the wrong class). Replace the whole method body with the plain
form — the exception object carries the metadata now:

```
log.error("Undeliverable pointer message; deleting without an attempt. delivery_id={}",
        e.deliveryId().map(Object::toString).orElse("unknown"), e);
```

Nothing else in `DeliveryQueueListener` changes. Do not touch the MDC scope, the span lifecycle, the
`catch (Throwable t)` block, or `tagSpanFromResult`.

## Out of Scope

- **Every other exception-handling site in the codebase.** TASK-009-02's §3.4 assessment covered
  them and the architect confirmed the result at its review: the deliveries and DLQ queues carry
  **pointers, not payloads** (ADR-002/ADR-004 §1), `JdkWebhookClientAdapter` converts every HTTP
  client exception to a `TransportFailure` before it can escape, `DeliveryDlqConsumer` swallows its
  Jackson exceptions inside `extractDeliveryIdFromBody`/`extractTraceparentFromBody` without
  logging them, and no application-constructed exception interpolates a raw value. Do not re-audit
  and do not "harden" a site that is already clean.
- Any span, meter, MDC key, timer or `toString()` change — TASK-009-02 delivered those.
- Any `application*.yaml` property, appender or `MeterFilter` — TASK-009-03.
- `NotificationEnvelope`, `AttemptDeliveryCommand`, or the pointer envelope's shape.

## Acceptance Criteria

- [ ] `UnparsablePointerMessageException` chains **no** payload-echoing cause; `getCause()` returns
      either `null` or a throwable whose own message is metadata-only.
- [ ] Its message carries `delivery_id` (in full, unredacted), the cause's **class name**, and the
      cause's **message length** as an integer — and no part of the cause's message text.
- [ ] `DeliveryQueueListener.logUnparsablePointer` is gone or reduced to a plain
      `log.error(..., e)`; the log record now reports the real exception class.
- [ ] No synthetic `RuntimeException` is constructed anywhere to strip a cause chain.
- [ ] The exception is safe to log whole from any site — that is, `log.error("...", e)` on it cannot
      print request content, at any level including `DEBUG` and `TRACE`.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure (A09 — this closes a §3.4 bypass rather than opening one;
      A10 — the exception still fails the message closed, deleting it without an attempt, exactly as
      before, and this task changes no control flow).

## Verification for this task

`./gradlew compileJava compileTestJava`. **Do not run `./gradlew test` or `./gradlew build`** —
FEAT-009's single full build is TASK-009-04's.

**No unit tests.** FEAT-009 runs under an explicit Tech Lead override suspending unit tests and
Testcontainers work for the whole feature. Do not write them and do not ask.

## Definition of Done

Code written, compiles clean. **Do not run `git add` or `git commit`.** Set this task's `status` to
`Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
