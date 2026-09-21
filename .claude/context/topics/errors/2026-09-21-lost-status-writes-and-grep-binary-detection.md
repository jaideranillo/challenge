# Lost task-status writes (recurring) + grep binary false-negative on task docs

## Lost "Ready for Review" status writes

Second occurrence across two features (FEAT-005, then FEAT-006 TASK-006-10/TASK-006-12): an implementing agent completes real, verifiable work — files exist on disk, tests pass, content is written — but the task file's `status:` frontmatter field is left at `Not Started` instead of being updated to `Ready for Review`. The agent's own final-message summary claims completion; the on-disk field disagrees.

**Do not trust an agent's "Ready for Review" claim.** Before reporting a multi-task feature complete, grep every task file's `status:` line directly and cross-check against real evidence (test file exists and passes, findings section is written, etc.) rather than the agent's self-report alone. If status is stale but the work is genuinely present, fix the `status:` field directly — don't re-dispatch the agent to redo work that already exists.

## grep binary false-negative

Several `docs/features/**/tasks/*.md` files contain non-ASCII bytes (em dash `—`). BSD `grep` (macOS default) classifies such a file as `data` and silently returns zero matches for any pattern — no error, no warning — even for a plain `status:` line that is visibly present when the file is read directly. This produced a false "status field missing entirely" alarm before the real lost-write bug above was confirmed.

**Always use `grep -a` (force text mode) when grepping these task docs**, or any repo markdown that may contain em dashes/smart quotes.
