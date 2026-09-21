# Concurrent-agent `git stash` race clobbers sibling agent's work

**Date:** 2026-09-20
**Topics:** backend, workflow

## Symptom
Two `dba` subagents dispatched in parallel against files in the same source-set directory (`src/test/java/.../adapter/out/persistence/`). One (TASK-004-15) used `git stash push -u -- <file>` / `git stash pop` to isolate-compile its own two files while diagnosing an unrelated compile error. The stash push/pop pair, run against a working tree another agent (TASK-004-09) was actively editing at the same moment, captured and restored the *entire* working tree rather than just the targeted file — silently reverting a completed sibling fix (`DeliveryAttemptJdbcRepositoryTest.java` back to its pre-fix broken constructor calls) and flipping an unrelated task file's `status:` field back to `Not Started`.

## Root Cause
`git stash` operates on the whole working tree by default; passing specific pathspecs to `stash push` narrows what gets *stashed*, but `stash pop` still restores everything in that stash entry, and two agents mutating the same directory tree concurrently means the stash snapshot and the pop target can diverge — the pop silently overwrote work done by the other agent in the intervening window.

## Fix
No code fix — this is a dispatch-sequencing rule, not a bug:
- Never run two agents in parallel if either might invoke `git stash` (or any other whole-tree git operation) for isolation, when both could touch files in the same directory.
- After any wave of parallel agents finishes, re-diff each agent's *claimed* changes against actual disk state (`git status`, targeted `grep`) before trusting a self-reported "Ready for Review" — an agent's summary describes what it intended, not necessarily the final state of a file another concurrent process also touched.
- Prefer strictly sequential dispatch whenever file collision is plausible, even if the task dependency graph on paper allows parallelism (independent *logical* tasks can still share a physical file or directory).
