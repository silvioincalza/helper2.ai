---
description: After a PR merges, update the worktrees that depend on it, then clean up merged ones
argument-hint: [merged PR number or branch]
---
Merged: $ARGUMENTS (if empty: everything merged into origin/main since the last sync)

1. `git fetch --prune`. List worktrees with `git worktree list` and, for each, its branch,
   its base (the branch it was created from or stacked on, from its PR: `Depends on #N`),
   whether the working tree is clean, and whether its PR is merged.
2. Order the open worktrees by dependency: direct dependents of the merged branch first.
3. For each open worktree, in that order:
   - clean: rebase onto `origin/main` (stacked after a squash merge:
     `git rebase --onto origin/main <old-base>`). On conflict: stop, `git rebase --abort`,
     report. Otherwise run the tests the branch touches, push with `--force-with-lease`,
     and `gh pr edit <n> --base main` if its PR targeted the merged branch;
   - not clean or owned by a running session: do not touch it; append a line to
     `.private/notes/worktrees.md` (date, worktree, what it needs to rebase onto).
4. For each merged branch: `git worktree remove .claude/worktrees/<branch>` (only if clean)
   and delete the branch locally and on origin.
5. Report: a table of worktree, branch, action (rebased / skipped / removed), tests.
