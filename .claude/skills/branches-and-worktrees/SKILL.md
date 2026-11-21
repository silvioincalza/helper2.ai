---
name: branches-and-worktrees
description: Use before creating a worktree or a branch, naming a branch, setting work aside, stacking a branch on another, opening or readying a pull request, answering a request to merge, after a pull request merges (the /sync-worktrees command), and when deciding whether a file (AI-tool setup, notes, plans, reviews) is committed. Holds the worktree, branch-name, stash, stacking, pull request, merge and commit rules.
---

# Branches and worktrees

- Every session works in its own git worktree, on its own branch from the latest `origin/main`:
  `git worktree add .claude/worktrees/<folder> -b <branch> origin/main` (git-ignored folder).
  `<folder>` is the branch name with every `/` replaced by `-` (`fix/review-findings` →
  `fix-review-findings`), so every worktree sits directly under `.claude/worktrees/`.
  The main checkout stays on `main` and clean: no session edits or switches branches there.
- A branch is named `<prefix>/<short-name>`, lower-case prefix, with one of: `feature/`, `fix/`, `docs/`, `chore/`,
  `ci/`, `refactor/`, `test/`, `release/` (a release line, `release/YYYY.N`), `replay/` (a replay pull request).
  CI fails a pull request from a branch with another prefix (`.github/scripts/branch-name.sh`).
- Do not use `git stash` in a worktree: the stash list is shared by every worktree of the repo, so another
  session can pop your changes. Set work aside with a temporary commit instead.
- A branch that needs another unmerged branch is stacked on it. Say so in the PR description:
  `Depends on #N`.
- After a PR merges, update every open worktree that depends on it, direct dependents first:
  - clean worktree: rebase onto `origin/main` (a stacked branch after a squash merge:
    `git rebase --onto origin/main <old-base>`), run its tests, ask the human in the loop, then push with `--force-with-lease`
    and retarget its PR to `main`;
  - worktree with uncommitted work, or owned by a running session: do not touch it; add a line
    to `.private/notes/worktrees.md` so its owner rebases at a safe point.
- When the human in the loop has merged a branch, remove its worktree (`git worktree remove`) and delete the branch.
- `/sync-worktrees` does the update steps above.

## Pull requests and merges

- Every change reaches `main` through a pull request. The session that makes the change opens it, always, and only
  after `scripts/verify.sh` passes for every part the change touches (`backend`, `frontend`, `docs`). Open it with
  `gh` from the human in the loop's machine (never through a tool that adds an AI footer). Its description says
  which `verify.sh` parts ran and what could not run, and closes the stories it builds, one `Closes #n` line each.
  The human in the loop reviews and merges it himself: never merge to `main`, never push to `main` directly, never
  deploy to prod.
- This holds even when the human in the loop asks for a merge in a session ("merge", "merge all"). Get the PR
  ready (checks green, review threads answered), then give him the command to run, one line per
  PR: `gh pr merge <n> --squash --delete-branch`. For a stack, give the order. Never use
  `gh pr merge`, `gh stack merge` or auto-merge yourself.
- Never add AI attribution, "Generated with" footers or Co-Authored-By to commits, PRs,
  comments, docs or code.

## What is committed

- AI-tool setup and settings are committed (`AGENTS.md`, `CLAUDE.md`, `.claude/settings.json`,
  `.claude/agents/`, `.claude/commands/`, `.claude/skills/`, `.claude/hooks/`). AI notes, reviews, plans, memory and machine-local
  settings never are: they go in the git-ignored `.private/` folder (or `CLAUDE.local.md`,
  `.claude/settings.local.json`). Plans, including the refactor plan and the AI scaffold map,
  live in `.private/plans/`. Product prompts the app runs are code and stay in the repo.
