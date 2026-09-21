---
id: TASK-002-07
feature: FEAT-002
title: "Remove the partition-maintenance migration and its test"
status: Ready for Review
agent: dba
depends_on: []
date: 2026-09-20
---

# TASK-002-07: Remove the partition-maintenance migration and its test

## Feature

FEAT-002 — Webhook notification delivery, persistence schema

## Assigned Agent

`dba` — this task is only for this agent.

## Why this exists

Partitioning is deferred (ADR-003 §3; TASK-002-06 marked `Deferred`). The partition-maintenance migration and its test were already written against the now-superseded scope and must come out before `V2`/`V3` are rewritten as unpartitioned tables — `provision_delivery_partitions()` and `drop_delivery_partitions()` operate on partitioned parents that will no longer exist, so leaving them would break the Flyway run.

**Run this first in the dba dispatch**, before TASK-002-03 and TASK-002-04.

## Scope

- File(s) (2, both deletions):
  - `src/main/resources/db/migration/V4__partition_maintenance.sql` — delete
  - `src/test/java/com/cobre/challenge/schema/PartitionMaintenanceTest.java` — delete
- Concern: removing the partition-lifecycle artefacts. Nothing else.

Deleting rather than stacking a corrective migration is correct here: these migrations are working-tree-only and uncommitted (`src/main/resources/db/` is untracked), so no environment has applied them and there is no migration history to preserve.

## Out of Scope

- `V1`, `V2`, `V3` — TASK-002-02/03/04 own those. Do not edit them here.
- `DeliveryIdempotencyIndexTest` / `DeliveryDueQueryIndexTest` — TASK-002-05 owns their update.
- `TestcontainersConfiguration` (including its `public` visibility, which stays — it is still needed by the TASK-002-05 test classes).
- Any Java production source, `build.gradle`, `application.yaml`.
- Writing any replacement retention mechanism. There is none, by decision (ADR-003 §3).

## Acceptance Criteria

- [ ] `V4__partition_maintenance.sql` no longer exists
- [ ] `PartitionMaintenanceTest.java` no longer exists
- [ ] No remaining reference anywhere in `src/` to `provision_delivery_partitions`, `plan_delivery_partition_drops`, or `drop_delivery_partitions`
- [ ] No production Java source file is added or modified
- [ ] `./gradlew test` passes once TASK-002-03/04/05 are also done (this task alone may leave the tree transiently inconsistent, which is expected — note it rather than working around it)

## Definition of Done

Both files deleted, no dangling references. **Do not run `git add` or `git commit`.** Set this task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->

## Completion note (coordinator, ahead of dba dispatch)

Deletions done directly by the coordinator, not by the `dba` agent, in response to an urgent mid-session user request to remove the partitioning artefacts before the dba re-dispatch. Both files confirmed deleted (`git status` / `ls`); `grep -rn "provision_delivery_partitions\|plan_delivery_partition_drops\|drop_delivery_partitions" src/` returns no matches. `./gradlew test` (full suite, forced rerun) passes now that TASK-002-03/04/05 are also done.
