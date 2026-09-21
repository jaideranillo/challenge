---
id: TASK-001-04
feature: FEAT-001
title: Structural fix for the local webhook stub receiver (package & class hygiene)
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-001-03]
date: 2026-09-20
---

# TASK-001-04: Structural fix for the local webhook stub receiver (package & class hygiene)

## Feature

FEAT-001 — Local development environment

## Assigned Agent

`backend-engineer` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

A **pure structural refactor** of the code delivered by TASK-001-03. No behavior changes, no new endpoints, no new dependencies. Every acceptance criterion of TASK-001-03 must still hold, and its tests must still pass with only package/import and type-reference edits.

This task exists because the delivered code violates three rules that are now mandatory in the `backend-engineer` agent definition, section **Package & Class Hygiene**:

1. **One type per file** — a nested interface/sealed interface representing a distinct concept must not live inside an unrelated host class.
2. **Controllers are thin** — no utility/helper methods inside the controller class; delegate to the collaborator.
3. **Adapter packages are sub-divided by concern** — never a flat bucket; `adapter/in/web` is not itself a leaf package.

### Confirmed violations (verified in the working tree)

**Rule 1 — one type per file.** `LocalWebhookStubRecorder.java` hosts two distinct concepts as nested types:
- `sealed interface ForcedBehavior` with its `None` / `Status` / `Hang` records and the `none()` factory (lines 79-90). `ForcedBehavior` is the response-policy model of the stub; the recorder is a bounded store of received requests. Two concepts, one file.
- `record RecordedRequest` (lines 67-77) — the captured-request model, likewise a distinct concept from the store that holds it. It is also the type serialized on the `GET /requests` response, referenced by the controller as `LocalWebhookStubRecorder.RecordedRequest`, which is exactly the coupling the rule targets.

**Rule 2 — thin controller.** `LocalWebhookStubController.java` carries three private helper methods that are not orchestration:
- `hang(Duration)` (lines 93-99) — the park/interrupt handling of the forced-hang behavior.
- `headersOf(HttpServletRequest)` (lines 101-111) — servlet header enumeration into a `Map<String, List<String>>`.
- `bodyOf(HttpServletRequest)` (lines 113-116) — bounded body read and UTF-8 decode.

Related leak from the same rule: `LocalWebhookStubRecorder.maxBodyBytes()` (recorder lines 63-65) exists only so the controller can enforce the body cap itself. The cap belongs with the type that models the captured request, not exposed as a static accessor on the store.

**Rule 3 — package sub-division.** The entire `adapter/in` tree is `web/local` and nothing else; `local` is a flat leaf bucket holding the controller, the store, and (nested) both model types. `local` names the *profile gate*, not the *concern*. With only this one concern existing today, the correct minimal split is to name that concern explicitly so the next inbound web concern lands beside it rather than merging into the same bucket. Do **not** invent packages for concerns that do not exist yet (no `webhook/ingest`, no `payments`, nothing speculative) — YAGNI still applies.

### Target structure

`RecordedRequest` is the type serialized as the `GET /requests` JSON payload — it plays a **DTO** role (wire representation) and lives in `dto/`. `ForcedBehavior` is internal control-plane state, never serialized — it plays a **model** role and lives in `model/`. Different roles, different subfolders (per the agent definition's Package & Class Hygiene rule on record/DTO placement).

Main:

```
adapter/in/web/local/webhookstub/
    LocalWebhookStubController.java   (moved, slimmed)
    LocalWebhookStubRecorder.java     (moved, nested types extracted)
    dto/
        RecordedRequest.java          (new file, extracted)
    model/
        ForcedBehavior.java           (new file, extracted)
```

Test:

```
adapter/in/web/local/webhookstub/
    LocalWebhookStubControllerTest.java  (moved; package + imports only)
```

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/web/local/webhookstub/LocalWebhookStubController.java` (moved from `.../local/`, helpers removed)
  - `src/main/java/com/cobre/challenge/adapter/in/web/local/webhookstub/LocalWebhookStubRecorder.java` (moved, nested types and `maxBodyBytes()` removed)
  - `src/main/java/com/cobre/challenge/adapter/in/web/local/webhookstub/dto/RecordedRequest.java` (new — extracted, DTO role)
  - `src/main/java/com/cobre/challenge/adapter/in/web/local/webhookstub/model/ForcedBehavior.java` (new — extracted, model role)
  - `src/test/java/com/cobre/challenge/adapter/in/web/local/webhookstub/LocalWebhookStubControllerTest.java` (moved; package declaration and type references only)
- Concern: the file/package structure of the local webhook stub. Nothing else.

This is five files rather than the usual ~3 because "one type per file" mechanically splits two existing files into four; it is still exactly one concern and one sitting's review — the diff is a move plus two extractions plus deletions of helper methods.

### Required changes

1. **Extract `RecordedRequest`** into its own file in the new `dto/` subpackage (it is the wire payload of `GET /requests`), unchanged in shape (same components, same compact-constructor normalization: non-null immutable `headers` copy, non-null `body`). Its JSON shape must stay identical — `method`, `headers`, `body`, `receivedAt` — because the test asserts on `$[0].method`, `$[0].body` and `$[0].headers['X-Webhook-Signature'][0]`.

2. **Extract `ForcedBehavior`** into its own file in the new `model/` subpackage (it is internal control-plane state, never serialized) as a top-level `sealed interface` with the same `None` / `Status` / `Hang` permitted records and the same `none()` factory.

3. **Move the controller's helpers onto their collaborators.** The controller must end up with no private helper methods — only the mapped handler methods, each of which validates/maps input, delegates, and maps the result. Concretely:
   - Header enumeration and bounded body read become a static factory on `RecordedRequest` (Effective Java Item 1), e.g. `RecordedRequest.from(HttpServletRequest request)`, and the `MAX_BODY_BYTES` cap moves to `RecordedRequest` alongside it. Delete `LocalWebhookStubRecorder.maxBodyBytes()`.
   - The park/interrupt handling of the hang moves onto `ForcedBehavior.Hang` (e.g. an `await()` method on the record). Keeping it there also keeps the no-pinning constraint expressed in exactly one place.
   - If you judge a different collaborator placement to be cleaner, that is acceptable **provided** the controller has zero private methods and no new class outside the file list above is introduced.

4. **Move all five files** into `...adapter/in/web/local/webhookstub/` (`RecordedRequest` under its `dto/` subpackage, `ForcedBehavior` under its `model/` subpackage), updating the `package` declaration and every import/reference. The old `...adapter/in/web/local` package must be left empty (no leftover files).

5. **Drop the `LocalWebhookStubRecorder.` qualifier everywhere** — `RecordedRequest` and `ForcedBehavior` are now same-package top-level types, so the controller and test reference them directly.

### Hard constraints

1. **Behavior is frozen.** Same URL paths (`/local/webhook-stub/**`), same HTTP methods, same status codes, same JSON field names and ordering semantics (most-recent-first), same `@Profile("local")` gate on both beans. A caller or demo script cannot tell this task ran.
2. **Test assertions are frozen.** `LocalWebhookStubControllerTest` changes only in its `package` line, its imports, and dropping the `LocalWebhookStubRecorder.` qualifier where nested types were referenced. No assertion text, no new test method, no removed test method. If an assertion has to change to make the code compile, the refactor went too far — stop and report instead.
3. **All TASK-001-03 acceptance criteria still hold**, in particular: no virtual-thread pinning in the hang path (no `synchronized`, no lock held across the wait, no spin loop — it stays a `Thread.sleep`/`parkNanos` park, just relocated); bounded, thread-safe record; no logging of bodies or headers at INFO or above; zero coupling to `domain/`, `application/`, any port, use case or repository.
4. **No new dependency, no build file change, no configuration change.**
5. **`@Profile("local")` stays on the two beans** (controller and recorder). `RecordedRequest` and `ForcedBehavior` are plain types, not beans — they get no Spring annotations.

## Out of Scope

- `docs/features/FEAT-001-local-dev-environment/tasks/TASK-001-03-stub-webhook-receiver.md` — do not edit that file, including its status.
- Any behavior change, new endpoint, new control mode, new field on `RecordedRequest`, or change to the bounded-record policy.
- `compose.yaml`, `application.yaml`, `application-local.yaml`, `build.gradle` — owned by TASK-001-01 / TASK-001-02.
- Any `SecurityConfig` or Spring Security wiring; the `addFilters = false` comment in the test stays as-is.
- Any domain model, use case, port, or persistence code. The stub still has no port behind it, and this task does not add one.
- Creating packages for concerns that do not exist yet (webhook ingest, payments, anything future-facing). One concern exists; name exactly that one.
- Renaming the classes themselves. Only their package and their file boundaries change.

## Acceptance Criteria

- [ ] `ForcedBehavior` is a top-level `sealed interface` in its own file; `RecordedRequest` is a top-level record in its own file; `LocalWebhookStubRecorder` declares no nested types (rule 1)
- [ ] `LocalWebhookStubController` contains no private helper methods — every method is a mapped handler that delegates to a collaborator (rule 2)
- [ ] `LocalWebhookStubRecorder.maxBodyBytes()` is gone; the body cap lives with the type that builds the captured request
- [ ] All five files live under `com.cobre.challenge.adapter.in.web.local.webhookstub`; `...adapter.in.web.local` contains no files of its own (rule 3), and no speculative sibling packages were created
- [ ] `LocalWebhookStubControllerTest` differs only in its `package` line, its imports, and dropped `LocalWebhookStubRecorder.` qualifiers — no assertion added, removed, or reworded
- [ ] `./gradlew test` passes, including both `WithLocalProfile` and `WithoutLocalProfile` nested classes
- [ ] Endpoint paths, HTTP methods, status codes and JSON field names are byte-identical to TASK-001-03's behavior
- [ ] The hang path still uses no `synchronized` block, no lock held across the wait, and no spin loop
- [ ] Nothing in `domain/` or `application/` references these classes, and these classes reference no port, use case, domain type, or repository
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition — in particular SRP for the extracted types, Item 1 for the static factory, Item 17 for the immutable record
- [ ] No new OWASP Top 10:2025 exposure introduced. The A01 control is unchanged (`@Profile("local")` on both beans, asserted by the existing test); A09 unchanged (no body/header logging); A10 unchanged (bounded, unpinned park)

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
