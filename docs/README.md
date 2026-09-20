# docs/ — Decision & Delivery Workflow

This folder is the single source of truth for architecture decisions and planned work. Nothing here is optional scaffolding — every non-trivial feature goes through this pipeline before code is written.

## Layout

```
docs/
  architecture/adr/   ADR-NNN-slug.md      Architecture Decision Records
  rfc/                RFC-NNN-slug.md      Optional pre-ADR discussion docs for open-ended proposals
  features/
    FEAT-NNN-slug/
      feature.md                          Feature breakdown, generated after its ADR is Accepted
      tasks/
        TASK-NNN-01-slug.md                One reviewable unit of work, assigned to one agent role
        TASK-NNN-02-slug.md
        ...
```

## Workflow (mandatory, in order)

1. **ADR generated** — the `software-architect` agent (Atlas) writes `docs/architecture/adr/ADR-NNN-slug.md` from `_template.md`, status `Proposed`.
2. **Human review** — the user reads and edits the ADR's `Status` field to `Accepted` or `Rejected`. No agent sets an ADR to `Accepted` — that is the user's call only.
3. **Feature + task breakdown** — once (and only once) an ADR's `Status` is `Accepted`, Atlas generates `docs/features/FEAT-NNN-slug/feature.md` plus one `tasks/TASK-NNN-XX-slug.md` per unit of work, assigned to the agent role that owns it (`backend-engineer`, `dba`, `security-engineer`, `devops-engineer`), delivered **in dependency order** (a task that depends on another states it explicitly and is numbered after it).
4. **Implementation, no commits** — each implementing agent works only from its assigned task file, implements exactly its scope, and **never runs `git add`/`git commit`**. The user reviews the working tree and commits manually. This is why task sizing matters (see below).

Atlas MUST check an ADR's `Status` field before generating anything downstream from it. Generating a feature/task breakdown from a `Proposed` (unapproved) ADR is a process violation, not a helpful shortcut.

## Task sizing rule (hard constraint)

A task must be reviewable by one person in one sitting. As a concrete bar: **one task touches at most ~3 files (or one migration + its adapter) and one concern.** If a unit of work doesn't fit that, Atlas splits it into multiple ordered tasks instead of writing one large task. "Implement the feature" is never a valid task — "Add the `Foo` domain model and `FooPort` interface" is.

## Status values

- **ADR / RFC**: `Proposed` -> `Accepted` | `Rejected` -> (later) `Superseded by ADR-NNN`
- **Feature**: `Planned` -> `In Progress` -> `Done`
- **Task**: `Not Started` -> `In Progress` -> `Ready for Review` -> `Done`

An implementing agent moves its task to `Ready for Review` when finished — never to `Done`. Only the user marks a task `Done`, since that's a proxy for "reviewed and committed."
