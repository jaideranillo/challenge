# /save-context — Save Context to QMD

## Description
Saves a note, decision, pattern, or insight directly to the **challenge** QMD collection. Use this for specific learnings mid-session without waiting for `/sync-context`.

## Syntax
```
/save-context "Title" "Content" [category] [tags]
```

## Parameters
- **title**: Short descriptive title (required)
- **description**: The note, pattern, decision, or learning (required)
- **category**: One of the categories below (optional, default: `general`)
- **tags**: Comma-separated tags for search (optional)

## Categories

| Category | Use for |
|----------|---------|
| `backend` | Domain/use case/persistence patterns, sub-ADR implementation reasoning |
| `database` | Schema, migrations, Spring Data JDBC / JdbcTemplate patterns |
| `security` | Authn/authz, Spring Security config, injection/OWASP findings |
| `infra` | Gradle build, Docker Compose, observability (OTel/Grafana) |
| `errors` | Bugs found and how they were resolved |
| `general` | Miscellaneous project notes |

**No `decisions` category here.** Architectural decisions are ADRs — they live in `docs/architecture/adr/ADR-NNN-slug.md` (owned by the `software-architect` agent, see `docs/README.md`), not in `topics/`. qmd already indexes the whole repo, so an ADR is searchable via `/recall` as soon as `qmd update` runs, with no `/save-context` step needed. Use the categories above only for reasoning that's real but too small/informal for a full ADR.

## Content Filter (mandatory)

The saved note is a **distilled technical fact**, not a transcript. Never save: explanations given because the user asked, small talk, conversational back-and-forth, or anything already in `docs/`. Write it as a standalone note for a future session that never saw this conversation — not "the user asked me to..." or "I explained that...".

## Behavior

1. Create `.claude/context/topics/<category>/YYYY-MM-DD-<slug>.md`
2. Re-index the `challenge` collection:
   ```bash
   qmd update
   qmd embed challenge
   ```
3. File is immediately searchable via `/recall`

## Examples

Save a security finding:
```
/save-context "Missing authz on DELETE endpoint" "DELETE /api/x had @Authenticated but no role check, any logged-in user could delete any resource. Fixed with @PreAuthorize on the use case." "security" "authz,spring-security"
```

Save a backend pattern:
```
/save-context "Port fake for use case tests" "Use an in-memory fake implementing the port/out interface for use case tests, never a real database." "backend" "testing,ports"
```

Do NOT use `/save-context` for an architectural decision — write an ADR instead (ask the `software-architect` agent), it's indexed by qmd automatically.

Save a resolved error:
```
/save-context "Virtual thread pinning under load" "A synchronized block around a JDBC call pinned virtual threads under concurrent load, capping throughput at the platform-thread count. Fixed by replacing the lock with a non-blocking structure." "errors" "virtual-threads,jdbc"
```

## When to Use vs /sync-context

| Use `/save-context` when... | Use `/sync-context` when... |
|-----------------------------|------------------------------|
| You discover a specific pattern mid-session | End of session — full summary |
| You close a design question with a decision | You want to save all session work at once |
| You solve a tricky bug | Session wrap-up |
