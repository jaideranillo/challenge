---
id: TASK-005-10
feature: FEAT-005
title: "MicrometerTraceContextAdapter: the current W3C traceparent, or empty"
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-005-05]
date: 2026-09-20
---

# TASK-005-10: `TraceContextPort` implementation

## Feature

FEAT-005

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/out/tracing/MicrometerTraceContextAdapter.java` (new)
  - `src/test/java/com/cobre/challenge/adapter/out/tracing/MicrometerTraceContextAdapterTest.java` (new)
- Concern: reading the active span's W3C `traceparent`, in the adapter layer where framework types belong.

Implements `TraceContextPort` over Micrometer Tracing (`io.micrometer.tracing.Tracer`), which the app already has via `spring-boot-starter-opentelemetry`. Build the value in W3C trace-context format — `00-<trace-id>-<span-id>-<flags>` — from the current span's context, so the string the worker later reads back (ADR-002 §3.1's fallback) is the same format an SQS `traceparent` message attribute carries. The two paths must produce the same shape, or the fallback silently yields an unparseable value.

**Never throws.** No active span, no tracer, a malformed context — all return `Optional.empty()`. An observability read must not be able to fail an ingest whose data is otherwise fine (A10). Catch broadly here, and log at debug, not error.

**No `ThreadLocal` of its own, and nothing held across a blocking call.** Read the current context, format it, return. The span context is scoped by the tracer; this adapter adds no storage.

Tests: with an active span the returned value parses as W3C trace-context and carries the span's trace id; with no active span the result is `empty()` and nothing is thrown. A `SimpleTracer` or the OTel test SDK is fine — this is the one adapter whose collaborator is an in-process library rather than an external service, so Testcontainers is not required and the CLAUDE.md "no mocked SQS / real Postgres" rule does not apply.

## Out of Scope

- Span creation, naming, or the `notification.ingest` span itself. ADR-002 §3's span shape is a separate concern that no task in this feature owns.
- MDC population and the Logback JSON encoder (ADR-002 §3.1). Not this feature.
- Reading a traceparent from an SQS message attribute — consumer side, out of scope.
- Any use case or adapter other than this one.

## Acceptance Criteria

- [ ] Implements `TraceContextPort`; one method, no extras.
- [ ] The returned string is valid W3C trace-context format and carries the active span's trace id.
- [ ] Returns `Optional.empty()` with no active span, and cannot throw under any input.
- [ ] No `synchronized` block and no adapter-owned `ThreadLocal`.
- [ ] Framework imports are confined to this file; nothing in `application/` or `domain/` gains a Micrometer import.
- [ ] Tests written and passing: both the active-span and no-span cases.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced. **A09:** the value is metadata only; no payload field is ever read here. **A10:** the swallow-and-return-empty behavior is the control and is tested.

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
