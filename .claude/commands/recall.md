# /recall — Search Context with qmd MCP

## Description
Searches the **challenge** QMD collection using semantic search across:
- `docs/` — ADRs, architecture, feature specs
- `.claude/context/topics/` — session notes, errors, patterns, decisions

## Syntax
```
/recall <query>
/recall --topic <name> <query>
/recall --mode <search|vsearch|query> <query>
```

## MCP Integration

QMD runs as a Claude Code plugin. Claude invokes these MCP tools directly, always scoped to `collection: "challenge"`:

```
qmd_search    -> Keyword search (BM25)
qmd_vsearch   -> Semantic search (embeddings)
qmd_query     -> Hybrid + re-ranking (default, best for questions)
```

## Search Modes

| Mode | Description | Best For |
|------|-------------|----------|
| `search` | Full-text keyword matching | Exact terms, error messages, class names |
| `vsearch` | Semantic similarity | Concepts, "how to" questions |
| `query` | Hybrid + LLM re-ranking | **Default** — best overall results |

## Execution

### Default (no flags)
```
qmd_query(query: "<user query>", collection: "challenge")
```

### Filter by topic (`--topic <name>`)
Translate to a path filter within the `challenge` collection:

| Topic flag | Path filter |
|------------|-------------|
| `--topic backend` | `.claude/context/topics/backend` |
| `--topic database` | `.claude/context/topics/database` |
| `--topic security` | `.claude/context/topics/security` |
| `--topic infra` | `.claude/context/topics/infra` |
| `--topic errors` | `.claude/context/topics/errors` |
| `--topic decisions` | `docs/architecture/adr` (no `topics/decisions/` folder — ADRs are the only source) |
| `--topic sessions` | `.claude/context/topics/sessions` |

### Force search mode (`--mode`)
```
/recall --mode search "duplicate key on migration V3"
  -> qmd_search(query: "...", collection: "challenge")

/recall --mode vsearch "how are use cases wired to ports"
  -> qmd_vsearch(query: "...", collection: "challenge")
```

## Usage Examples

```
/recall port/out naming convention
/recall --topic security "password hashing"
/recall --topic decisions "why Spring Data JDBC over JPA"
/recall --topic sessions "what did we work on last week"
```

## Requirements

- QMD plugin installed: `claude plugin install qmd@qmd`
- Collection created: `qmd collection add /path/to/challenge --name challenge`
- Index up to date: `qmd update` (run after new files are added)
- Embeddings ready: `qmd embed` (run once after `qmd update`)
