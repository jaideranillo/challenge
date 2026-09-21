---
id: TASK-003-07
feature: FEAT-003
title: ResponseClassifier tests covering every ADR-004 §1 row
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-003-06]
date: 2026-09-20
---

# TASK-003-07: ResponseClassifier tests covering every ADR-004 §1 row

## Feature

FEAT-003

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

Plain-JUnit coverage of the classifier. No Spring context, no HTTP, no mocks.

- File(s):
  - `src/test/java/com/cobre/challenge/domain/policy/ResponseClassifierTest.java` (new)
- Concern: proving the ADR-004 §1 table is implemented exactly.

Required cases, one parameterized source per group:
1. **2xx**: at least 200, 201, 202, 204, 299 -> `SUCCESS`.
2. **3xx**: at least 301, 302, 303, 307, 308 -> `NON_RETRYABLE_REDIRECT`, and assert `countsTowardCircuitBreaker()` is true. A redirect is never treated as retryable and never as success.
3. **400, 422** -> `NON_RETRYABLE`.
4. **401, 403** -> `NON_RETRYABLE`.
5. **404, 410** -> `NON_RETRYABLE_DEACTIVATE_SUBSCRIPTION`, distinct from plain `NON_RETRYABLE`. Both codes, not just 410 (ADR-004 §1 is explicit that 404 deactivates too).
6. **408** -> `RETRYABLE`, breaker-counting true.
7. **429** -> `RETRYABLE_THROTTLED`, breaker-counting **false**. Assert the false explicitly: ADR-004 §1 calls this out as a deliberate exception and it is the easiest row to get wrong.
8. **5xx**: at least 500, 502, 503, 504, 599 -> `RETRYABLE`, breaker-counting true.
9. **Transport failures**: each of `TIMEOUT`, `CONNECTION_RESET`, `DNS_FAILURE`, `TLS_FAILURE` with `statusCode = 0` -> `RETRYABLE`, breaker-counting true.
10. **Unnamed 4xx** (e.g. 418, 451) -> `NON_RETRYABLE`, documenting the deliberate default.
11. **Out-of-range** status with `TransportFailure.NONE` (e.g. 0, 99, 600) throws `IllegalArgumentException`.
12. **Completeness guard**: a test asserting that every `AttemptOutcome` value is produced by at least one input in this suite, so a newly added outcome cannot ship untested.

## Out of Scope

- No test of `Delivery`, `RetryPolicy` or any port.
- No `@SpringBootTest`, no Testcontainers, no WireMock or any HTTP stub — the function under test takes an `int`.
- No assertion about what the use case then does with the outcome (setting `throttled_until`, deactivating the subscription); those are later features' tests.

## Acceptance Criteria

- [ ] All twelve groups above are covered, each as a named test or parameterized case.
- [ ] Breaker-counting is asserted, not just the outcome value, for groups 2, 6, 7, 8 and 9.
- [ ] The completeness guard (group 12) fails if a new `AttemptOutcome` value is added without a case.
- [ ] No Spring or HTTP import in the test file.
- [ ] `./gradlew test --tests "com.cobre.challenge.domain.policy.ResponseClassifierTest"` passes.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable).

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
