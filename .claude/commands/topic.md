# /topic — Load Topic Context

## Description
Loads context from a specific topic. Reads files from `.claude/context/topics/<name>/` and summarizes them for the session.

## Syntax
```
/topic <name>
/topic list
/topic all
```

## Available Topics

| Topic | Description | Path |
|-------|-------------|------|
| `backend` | Domain/use case/persistence patterns, hexagonal architecture | `.claude/context/topics/backend/` |
| `database` | Schema, migrations, Spring Data JDBC / JdbcTemplate patterns | `.claude/context/topics/database/` |
| `security` | Authn/authz, Spring Security, injection/OWASP findings | `.claude/context/topics/security/` |
| `infra` | Gradle build, Docker Compose, observability | `.claude/context/topics/infra/` |
| `errors` | Bugs and solutions | `.claude/context/topics/errors/` |
| `sessions` | Session summaries | `.claude/context/topics/sessions/` |

There is no `decisions` topic folder. Architectural decisions live only in `docs/architecture/adr/` — use `/topic decisions` as a shorthand that reads that folder directly (see below), not a `topics/decisions/` directory.

## Execution

### `/topic list`
List topics with file counts and last-updated date.

### `/topic <name>`

1. Read all files from `.claude/context/topics/<name>/`
2. `/topic decisions` is a special case: read `docs/architecture/adr/` instead (there's no corresponding `topics/` folder)
3. Show a summary of what was loaded

### `/topic all`
Load a summary from all topics — useful at session start. Reads `_index.md` from each topic folder.

## Search within a topic

To search content within a topic using QMD (always filtered to the `challenge` collection):
```
/recall --topic security "password hashing"
/recall --topic decisions "Spring Data JDBC vs JPA"
```
