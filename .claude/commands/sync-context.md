# /sync-context — Sync Session Context

## Description
Saves work from the current session to the **challenge** QMD collection and re-indexes.

## Syntax
```
/sync-context                     # Full sync (session + detected topics)
/sync-context --topic security    # Sync only to a specific topic
/sync-context --session-only      # Only save session summary
```

## Content Filter (mandatory, applies to every file this command writes)

What gets written to `.claude/context/topics/` is a **distilled technical record**, not a transcript.

**Never write:**
- Explanations given to the user because they asked ("what does X mean", "why does Y work this way") — that's teaching, not project knowledge.
- Small talk, back-and-forth, or anything conversational (greetings, clarifying exchanges, confirmations like "ok" / "got it").
- Anything already fully captured in `docs/` (ADRs, features, tasks) — reference it (`See ADR-NNN`), don't restate it.
- Raw chat excerpts or quoted dialogue.

**Only write:**
- A technical fact, pattern, bug cause+fix, or non-architectural decision that will matter to a future session and doesn't already live in `docs/`.
- Written as a standalone technical note (title + terse content), as if for someone who never saw the conversation — not "the user asked me to..." or "I explained that...".

If nothing in the session clears this bar, write nothing — an empty/skipped sync is correct, not a failure.

## Execution

### 1. Gather session information

Analyze the current conversation and extract:
- **Files created/modified**: list with brief description of each change
- **Problems solved**: errors found and how they were fixed
- **Technical decisions**: architectural, security, or design decisions made
- **Topics worked on**: backend, database, security, infra
- **Pending tasks**: what was left incomplete

### 2. Generate session summary

Create a file in `.claude/context/topics/sessions/YYYY-MM-DD-<description>.md`:

```markdown
# Session: [YYYY-MM-DD] [Brief description]

**Date:** YYYY-MM-DD HH:MM
**Topics:** backend, security, etc.

## Work Completed

### Files Modified
- `path/to/file` — Description of change

### Problems Solved
1. **[Problem title]** — symptom, root cause, fix. One tight paragraph or three bullets, not a retelling of the debugging session.

### Technical Decisions
- **[Decision]**: [Reason]. If this is architectural, it should already exist as `docs/architecture/adr/ADR-NNN-slug.md` — reference it here (`See ADR-NNN`), don't restate its content.

## Status at End
- Completed: ...
- In progress: ...
- Pending: ...

## Notes for Next Session
- ...
```

### 3. Update relevant topics

- **New errors** -> `.claude/context/topics/errors/YYYY-MM-DD-<description>.md`
- **Security findings** -> `.claude/context/topics/security/YYYY-MM-DD-<description>.md`
- **Other technical knowledge** -> `.claude/context/topics/<topic>/YYYY-MM-DD-<description>.md`
- **Architectural decisions** -> NOT a topic file. Write/verify `docs/architecture/adr/ADR-NNN-slug.md` instead (`software-architect` agent) — qmd indexes it automatically, no separate topic note needed.

### 4. Re-index

```bash
qmd update
qmd embed challenge
```

### 5. Confirmation

Show a summary of files saved and re-index counts.

## Full Flow

```
/sync-context
  |
  |-> 1. Analyze current conversation
  |-> 2. Create summary in .claude/context/topics/sessions/
  |-> 3. Create topic files for new knowledge
  |-> 4. Run: qmd update
  |-> 5. Run: qmd embed
```
