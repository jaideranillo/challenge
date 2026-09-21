---
id: TASK-001-06
feature: FEAT-001
title: Standardize LocalWebhookStubController return types on ResponseEntity
status: Ready for Review
agent: backend-engineer
depends_on: [TASK-001-05]
date: 2026-09-20
---

# TASK-001-06: Standardize LocalWebhookStubController return types on ResponseEntity

## Feature

FEAT-001 — Local development environment

## Assigned Agent

`backend-engineer` — this task is only for this agent.

## Scope

Pure consistency fix, no behavior change. Every handler in
`LocalWebhookStubController` returns `ResponseEntity<...>` except
`requests()`, which returns `List<RecordedRequest>` directly. Spring MVC
serializes both the same way (200 + JSON body), so this is not a bug, but it
breaks the uniform "endpoint = ResponseEntity" pattern the rest of the class
follows.

- File(s):
  - `src/main/java/com/cobre/challenge/adapter/in/web/local/webhookstub/LocalWebhookStubController.java` (change `requests()` return type only)
  - `src/test/java/com/cobre/challenge/adapter/in/web/local/webhookstub/LocalWebhookStubControllerTest.java` (no assertion change expected — same 200 + JSON body; touch only if compilation requires it)
- Concern: return-type consistency of one method. Nothing else.

## Required change

Change:
```java
@GetMapping("/requests")
public List<RecordedRequest> requests() {
    return recorder.recentFirst();
}
```
to:
```java
@GetMapping("/requests")
public ResponseEntity<List<RecordedRequest>> requests() {
    return ResponseEntity.ok(recorder.recentFirst());
}
```

## Out of Scope

- Any other endpoint, any other file.
- Any behavior, status code, or JSON shape change — response body and status must be identical to before.
- Adding/removing endpoints (already revalidated: all 6 are required by TASK-001-03's acceptance criteria — receive, inspect, clear, force-status, force-hang, reset).

## Acceptance Criteria

- [ ] `requests()` returns `ResponseEntity<List<RecordedRequest>>` via `ResponseEntity.ok(...)`
- [ ] `GET /local/webhook-stub/requests` still returns 200 with the identical JSON body shape
- [ ] `./gradlew test` passes unchanged

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
