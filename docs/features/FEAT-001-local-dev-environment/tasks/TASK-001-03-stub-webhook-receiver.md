---
id: TASK-001-03
feature: FEAT-001
title: Stub webhook receiver behind the `local` profile
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-001-02]
date: 2026-09-20
---

# TASK-001-03: Stub webhook receiver behind the `local` profile

## Feature

FEAT-001 — Local development environment

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

A stub webhook receiver, active under the `local` profile only, that records what it receives and can be forced to return an arbitrary status code or to hang. It is the target endpoint for the live demo and for later worker tests of timeout/retry behavior.

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/web/local/LocalWebhookStubController.java` (new) — the receiving endpoint plus the control endpoints
  - `src/main/java/com/cobre/challenge/adapter/in/web/local/LocalWebhookStubRecorder.java` (new) — bounded in-memory record of received requests plus the current forced behavior
  - `src/test/java/com/cobre/challenge/adapter/in/web/local/LocalWebhookStubControllerTest.java` (new) — slice test
- Concern: the local stub receiver. Nothing else.

Both production classes live under `adapter/in/web/local/`, a package whose name makes the gate visible in the import line of anything that ever tries to reference it.

### Behavior

**Receive.** One endpoint that accepts any HTTP method and any body, and records: HTTP method, request headers, body, and a receive timestamp. Headers are recorded as received — in a real demo run this includes the webhook signature header from ADR-004 §1.1, which is exactly what makes the stub useful for verifying signing.

**Inspect.** An endpoint returning the recorded requests, most recent first, so a demo or a test can assert what arrived. An endpoint (or equivalent) to clear the record, so a test starts from a known state.

**Force a status code.** A control endpoint that sets the status the receive endpoint returns for subsequent requests. It must accept any status the pipeline classifies differently — at minimum 200, 429 (throttle, ADR-006), 500 (retryable), 400 (non-retryable) — without the stub itself deciding which are legal.

**Force a hang.** A control mode that makes the receive endpoint block for a configured duration (or until cleared) before responding. This is what exercises the 2s connect / 5s read per-attempt timeouts in ADR-004 §1, so a hang longer than the read timeout must be expressible.

**Reset.** Clearing forced behavior returns the stub to "record and return 200".

### Hard constraints

1. **Profile gate on the beans themselves.** Both classes carry `@Profile("local")`. This is not a feature flag and not a property check — under any other profile the beans must not exist in the application context. An open, unauthenticated endpoint that echoes attacker-chosen status codes is OWASP A01 if it ever ships; the gate is the entire control (FEAT-001 "Security Impact"). A test must assert the endpoint is absent without the `local` profile.

2. **No virtual-thread pinning in the hang path.** Implement the hang as `Thread.sleep(...)` or `LockSupport.parkNanos(...)` on the request thread. Do **not** use a `synchronized` block, a lock held across the wait, or a spin loop — any of those pins the carrier thread and a handful of concurrent hangs will starve the demo. The whole service is blocking-on-virtual-threads (CLAUDE.md); a parked virtual thread is free, a pinned one is not.

3. **Bounded memory.** The record is capped (a ring buffer or a bounded deque, oldest evicted). An unbounded list behind an endpoint that accepts arbitrary bodies is a local OOM waiting for a load test. State that holds concurrent writes must be thread-safe — many virtual threads will hit this concurrently.

4. **No logging of bodies or headers at INFO or above** (OWASP A09). The record is returned on request, not written to the log stream.

5. **Zero coupling to the rest of the application.** No `port/in`, no `port/out`, no use case, no domain type, no repository. The stub has no port behind it because nothing in the application layer calls it; adding one would be a YAGNI violation. Nothing in `domain/` or `application/` may reference these classes.

6. **Request cap.** Reject or truncate absurdly large bodies rather than recording them whole.

## Out of Scope

- `compose.yaml`, the init script, `application-local.yaml`, `application.yaml`, `build.gradle` — TASK-001-01 and TASK-001-02 own those. If the stub needs a tunable (record capacity, say), give it a sensible default in code rather than opening a config file this task does not own.
- Any Spring Security configuration or `SecurityConfig` class. When ADR-007's security config lands, its author handles this path; that is a security-engineer task in a different feature.
- Any domain model, use case, port, or persistence code.
- Webhook signature *verification*. The stub records the signature header; it does not validate it. Verification logic belongs with ADR-004's signing feature.
- Any production-profile behavior whatsoever.

## Acceptance Criteria

- [ ] Under the `local` profile, a request to the receive endpoint is recorded with its method, headers, body and timestamp, and is readable from the inspect endpoint
- [ ] A forced status code is returned by subsequent receives, for at least 200 / 400 / 429 / 500
- [ ] A forced hang blocks the response for the configured duration; a hang longer than 5s is expressible, so ADR-004 §1's read timeout can be exercised
- [ ] Clearing the forced behavior returns the stub to record-and-200
- [ ] The record is bounded and thread-safe; concurrent receives do not lose or corrupt entries
- [ ] Both beans are `@Profile("local")`, and a test asserts the endpoint is **not** registered when the `local` profile is inactive
- [ ] The hang path uses no `synchronized` block, no lock held across the wait, and no spin loop
- [ ] Tests written and passing (`./gradlew test`), MockMvc slice or equivalent — no Testcontainers needed, the stub touches no infrastructure
- [ ] Nothing in `domain/` or `application/` references these classes
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition — in particular: no null returns from the recorder (empty collection or `Optional`), immutable record types for the recorded request
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable). A01 is controlled by the profile gate; A09 by not logging recorded bodies/headers; A10 by the hang being a bounded, unpinned park

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
