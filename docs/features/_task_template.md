---
id: TASK-NNN-XX
feature: FEAT-NNN
title: <task title>
status: Not Started
agent: backend-engineer | dba | security-engineer | devops-engineer
depends_on: []
date: YYYY-MM-DD
---

# TASK-NNN-XX: <Title>

## Feature

FEAT-NNN

## Assigned Agent

`<agent-role>` — this task is only for this agent. If it needs work from another role, that's a separate task, not scope creep on this one.

## Scope

Exactly what to change. Name the files (new or modified) — target: at most ~3 files, one concern. If this can't be described that tightly, it's not one task; go back to the Architect to split it.

- File(s):
- Concern:

## Out of Scope

What NOT to touch, explicitly, even if it looks related.

## Acceptance Criteria

- [ ] Concrete, testable condition
- [ ] Tests written and passing (unit and/or integration as appropriate to the role)
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer if unavoidable)

## Definition of Done

Code written and tests passing locally. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop — the user reviews the working tree and commits.

## Status

Not Started <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
