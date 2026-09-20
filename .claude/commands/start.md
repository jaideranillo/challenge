# /start — Start Work Session

## Description
Starts a work session. Loads last session summary and project status from the **challenge** QMD collection.

## Syntax
```
/start                    # General start — shows last session + available topics
/start security           # Start focused on a topic
/start security backend   # Start with multiple topics
```

## Execution

### 1. Show last session (if exists)

Read the most recent file from `.claude/context/topics/sessions/` and show a summary:

```
Last session: [date] — [title]

Work completed:
- ...

Pending:
- ...
```

### 2. List available topics

```
Topics [collection: challenge]

  backend    | N docs | Domain/use case/persistence patterns, hexagonal architecture
  database   | N docs | Schema, migrations, Spring Data JDBC
  security   | N docs | Authn/authz, Spring Security, injection/OWASP findings
  infra      | N docs | Gradle build, Docker Compose, observability
  errors     | N docs | Resolved bugs and solutions
  decisions  | N docs | ADRs, sourced from docs/architecture/adr/ (no topics/decisions/ folder)
  sessions   | N docs | Session summaries
```

### 3. Load requested context

If the user specified topics (`/start security backend`):

1. Read `_index.md` from each topic folder
2. Read the most recent 3-5 files from each topic
3. `decisions` has no `topics/` folder — read `docs/architecture/adr/` directly instead
4. Show a summary of what was loaded

### 4. Suggest next steps

```
Search context:  /recall "your question"
Load a topic:    /topic security
Save a note:     /save-context "title" "content" "category"
End of session:  /sync-context
```

## Without Arguments

```
User: /start

Claude:
Last session: [date] — [title]

Topics [collection: challenge]: backend, database, security, infra, errors, decisions

Use /start <topic> to load specific context, e.g. /start security

What are we working on today?
```
