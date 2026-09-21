---
id: TASK-005-05
feature: FEAT-005
title: "TraceContextPort: the use case's framework-free access to the current W3C traceparent"
status: Ready for Review
agent: backend-engineer
depends_on: []
date: 2026-09-20
---

# TASK-005-05: `TraceContextPort`

## Feature

FEAT-005

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/out/tracing/TraceContextPort.java` (new package, new file)
  - `src/test/java/com/cobre/challenge/application/port/out/tracing/TraceContextPortTest.java` (new, shape test in the style of `NotificationQueuePortTest`)
- Concern: one method, so the ingest use case can write `trace_context` without importing Micrometer.

```java
public interface TraceContextPort {

    Optional<String> currentTraceparent();
}
```

Why it exists (state it in javadoc, with citations): ADR-003 Amendment A3 makes `trace_context` a value the **ingest use case writes** and the worker reads; ADR-002 §3.1 defines that value as the current W3C `traceparent`. Reading it means touching Micrometer/OpenTelemetry, and a use case in this codebase carries no framework type but `@Transactional`. ADR-002 Amendment C3 adds this port for that reason.

Contract:

1. **`Optional.empty()` when no span is active is normal**, not an error. `deliveries.trace_context` is nullable (`V2`), and ADR-002 §3.1's consumer-side fallback already handles an absent value: the message attribute is tried first.
2. **Never throws.** An observability read must not be able to fail an ingest whose data is otherwise fine (A10). The adapter swallows and returns `empty()`; the port says so.
3. **One method.** Not a general "tracing facade" — no span creation, no MDC, no baggage, no `currentTraceId()`. Ingest writes one value; that is the whole surface (YAGNI, Effective Java Item 64).

## Out of Scope

- The Micrometer implementation. TASK-005-10.
- Any span creation, MDC manipulation, or logging configuration. ADR-002 §3.1's MDC wiring is a separate concern and no task in this feature owns it.
- Reading a traceparent from an SQS message attribute — that is the consumer's side, and the consumer is out of scope for FEAT-005.

## Acceptance Criteria

- [ ] One interface, one method, in a new `application/port/out/tracing` package.
- [ ] Zero framework imports; `java.util.Optional` and nothing else.
- [ ] Javadoc cites ADR-002 §3.1 and Amendment C3, ADR-003 Amendment A3, and states both that `empty()` is normal and that the method never throws.
- [ ] Tests written and passing: a shape test in the style of the existing port tests.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A09:** a traceparent is metadata, never payload; this port must never grow a method that returns anything derived from `content`.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
