---
id: TASK-007-03
feature: FEAT-007
title: Retry schedule properties and the RetryPolicy bean
status: Ready for Review
agent: devops-engineer
depends_on: []
date: 2026-09-21
---

# TASK-007-03: Retry schedule properties and the `RetryPolicy` bean

## Feature

FEAT-007

## Assigned Agent

`devops-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/messaging/config/RetryProperties.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/in/messaging/config/RetryPolicyConfig.java` (new)
  - `src/main/resources/application.yaml` (modified)
- Concern: bind `challenge.retry.backoff` and construct the one `RetryPolicy` bean. Wiring only.

### What this closes

`application-local.yaml` already declares `challenge.retry.backoff: 2s, 5s, 10s`, but nothing binds
it and no `RetryPolicy` bean exists, so the merged domain class is currently unreachable from the
application. ADR-004 §1's note states the shape exactly: the schedule is a
`@ConfigurationProperties`-bound value, read once at startup and passed into the framework-free
`RetryPolicy` at construction, so the domain object stays unaware of Spring and only the wiring
adapter knows where the numbers came from. That is this task, and nothing more.

### Details

- `RetryProperties`: `@Validated @ConfigurationProperties("challenge.retry")`, one component
  `@NotEmpty List<Duration> backoff`.
- `application.yaml` gets ADR-004 §1's production schedule as the default:
  `5s, 30s, 2m, 10m, 1h, 6h`. `application-local.yaml`'s compressed list stays exactly as it is.
- `RetryPolicyConfig` exposes `RetryPolicy retryPolicy(RetryProperties)` built as
  `new RetryPolicy(properties.backoff(), RandomGenerator.getDefault())`.
  `RandomGenerator.getDefault()` returns a per-call instance in the JDK — construct it once here
  and hand it to the single `RetryPolicy` bean; do not construct a generator per call anywhere.
- `RetryPolicy` already validates the list (non-empty, positive, strictly increasing) and already
  defines `maxAttempts()` as the schedule length. **Do not re-validate and do not wrap it.**
- **No `@ConditionalOnProperty`.** The retry policy is not optional; a worker without one cannot
  classify an exhausted schedule.

### Addendum 3a (2026-09-21): this config also declares the `Clock` and `RandomGenerator` beans

Found while implementing TASK-007-14: `AttemptDeliveryUseCaseImpl` injects a `Clock` (one instant
per attempt) and a `RandomGenerator` (deferral jitter), and **neither bean exists anywhere in this
application context**. Constructor injection compiles without them, so `./gradlew compileJava`
says nothing; `bootRun` and any `@SpringBootTest` would fail to start. Not a task-sized concern of
its own, so it lands here — `RetryPolicyConfig` is already the file that constructs a
`RandomGenerator`, and having two places create one is how they drift.

Declare both in `RetryPolicyConfig`, and have the `RetryPolicy` bean consume the
`RandomGenerator` bean rather than calling `RandomGenerator.getDefault()` inline:

```java
@Bean Clock clock()                      // Clock.systemUTC()
@Bean RandomGenerator randomGenerator()  // RandomGenerator.getDefault()
@Bean RetryPolicy retryPolicy(RetryProperties properties, RandomGenerator randomGenerator)
```

- `Clock.systemUTC()`, not `Clock.systemDefaultZone()`: every instant this service persists or
  compares is UTC, and a host timezone must not be able to change behavior.
- `RandomGenerator.getDefault()`. **No ADR specifies a jitter algorithm** — ADR-004 §1 and
  ADR-006 §1 require only that jitter exist and be ±20%, and the merged `RetryPolicy` javadoc
  records "randomness is injected via `RandomGenerator`" as a labelled implementation choice. The
  default is fine and deliberately **not** a `SecureRandom`: jitter is a thundering-herd
  mitigation, not a security control, and nothing downstream depends on it being unpredictable.
- One `RandomGenerator` bean for the whole process, shared by `RetryPolicy` and the use case's
  deferral jitter. `RandomGenerator.getDefault()` returns a **new** instance per call, so calling
  it in two places would silently create two generators; declaring it once as a bean is the fix.
  Confirm the returned implementation is thread-safe for concurrent use from many virtual threads,
  and if it is not, pick a `RandomGeneratorFactory` implementation that is (e.g. a splittable or
  thread-safe algorithm) and say which in your handover. This is the one detail here worth a
  minute's checking — thousands of virtual threads share this bean.
- Both beans are overridable by a test that wants a fixed clock or a seeded generator, which is
  what makes TASK-007-15's determinism possible without a Spring context.

## Out of Scope

- `RetryPolicy` itself (merged, framework-free, tested — do not touch it).
- `challenge.worker.*` (TASK-007-02).
- Any caller of the bean. `DeliveryOutcomeWriter` (TASK-007-12) is the only one and it is a
  separate task.

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker. Add a plain JUnit test that calls `RetryPolicyConfig.retryPolicy(...)` directly with a
hand-built `RetryProperties` and asserts the resulting policy's `maxAttempts()` equals the
configured list size, and that an empty list is rejected.

**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileJava
compileTestJava` and report the tests as written and pending the Tech Lead's later run.

## Acceptance Criteria

- [ ] `challenge.retry.backoff` binds to `List<Duration>` and is validated non-empty.
- [ ] `application.yaml` carries ADR-004 §1's production schedule `5s, 30s, 2m, 10m, 1h, 6h`.
- [ ] `application-local.yaml` is unchanged.
- [ ] Exactly one `RetryPolicy` bean exists, constructed from the injected `RandomGenerator` bean.
- [ ] A `Clock` bean (`Clock.systemUTC()`) and a `RandomGenerator` bean
      (`RandomGenerator.getDefault()`) are declared, exactly once each, in this config.
- [ ] No other class calls `RandomGenerator.getDefault()` or `Clock.system*()` directly.
- [ ] The handover states whether the chosen `RandomGenerator` implementation is thread-safe for
      concurrent virtual threads, and which algorithm was used if the default was replaced.
- [ ] `RetryPolicy` is not modified, subclassed or wrapped.
- [ ] Unit test (plain JUnit, no Spring context) covers bean construction and the empty-list rejection.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer).

## Definition of Done

Code written, tests written but **not run**. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
