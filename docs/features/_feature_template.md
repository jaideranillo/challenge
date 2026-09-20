---
id: FEAT-NNN
title: <feature title>
status: Planned
adr: ADR-NNN
date: YYYY-MM-DD
authors: software-architect (Atlas)
---

# FEAT-NNN: <Title>

## Source ADR

ADR-NNN (must be `Accepted` — this file must not exist if it isn't).

## Scope (MVP / Post-MVP)

What's in this feature, what's explicitly deferred.

## Architecture

Mermaid diagram: adapter/in -> port/in -> usecase -> domain <- port/out <- adapter/out.

## Port Contracts

`port/in` and `port/out` interfaces this feature introduces or changes.

## Data Model Impact

Tables/columns touched, referencing the DBA agent's eventual migration.

## Security Impact

Authn/authz requirements and OWASP Top 10:2025 categories this feature is exposed to.

## Task Breakdown

Ordered list of tasks, each a link to `tasks/TASK-NNN-XX-slug.md`, with its assigned agent and what it depends on.

| # | Task | Agent | Depends on |
|---|------|-------|------------|
| 01 | ... | dba | - |
| 02 | ... | backend-engineer | 01 |
| 03 | ... | security-engineer | 02 |

## Status

Planned <!-- Planned | In Progress | Done -->
