---
id: TASK-001-05
feature: FEAT-001
title: Rename ForcedBehavior's package from model/ to behavior/
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-001-04]
date: 2026-09-20
---

# TASK-001-05: Rename ForcedBehavior's package from model/ to behavior/

## Feature

FEAT-001 — Local development environment

## Assigned Agent

`backend-engineer` — this task is only for this agent.

## Scope

Pure rename, no behavior change. `ForcedBehavior` currently lives in
`com.cobre.challenge.adapter.in.web.local.webhookstub.model`. CLAUDE.md's
hexagonal layout reserves the term `model` for `domain/model` — framework-free
domain types. `ForcedBehavior` is adapter-internal control-plane state, not a
domain concept, so it must not share that vocabulary.

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/web/local/webhookstub/model/ForcedBehavior.java` -> move to `.../webhookstub/behavior/ForcedBehavior.java`
  - `src/main/java/com/cobre/challenge/adapter/in/web/local/webhookstub/LocalWebhookStubController.java` (import update only)
  - `src/main/java/com/cobre/challenge/adapter/in/web/local/webhookstub/LocalWebhookStubRecorder.java` (import update only)
  - `src/test/java/com/cobre/challenge/adapter/in/web/local/webhookstub/LocalWebhookStubControllerTest.java` (import update only, if it imports `ForcedBehavior` directly)
- Concern: package name of `ForcedBehavior` only. `RecordedRequest` stays in `dto/` — that placement is correct and out of scope here.

## Required changes

1. Move `ForcedBehavior.java` from `webhookstub/model/` to `webhookstub/behavior/`, updating its `package` declaration.
2. Delete the now-empty `webhookstub/model/` directory.
3. Update every import referencing `...webhookstub.model.ForcedBehavior` to `...webhookstub.behavior.ForcedBehavior`.
4. No change to `ForcedBehavior`'s own content (still `sealed interface`, same `None`/`Status`/`Hang` variants, same `Hang.await()`, same `none()` factory).

## Out of Scope

- Any change to `RecordedRequest` or its `dto/` package.
- Any behavior, endpoint, or test-assertion change.
- Any other file in the feature.

## Acceptance Criteria

- [ ] `ForcedBehavior.java` lives at `.../webhookstub/behavior/ForcedBehavior.java`, package declaration matches
- [ ] `webhookstub/model/` directory no longer exists
- [ ] All references compile against the new package
- [ ] `./gradlew test` passes unchanged

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
