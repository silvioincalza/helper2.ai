---
description: Run the AI architecture check on a change or design
argument-hint: [scope, e.g. a branch, a module or a design doc]
---
Run the AI architecture check from `AGENTS.md` on: $ARGUMENTS
(if empty: the uncommitted changes, `git diff HEAD`).

Output one table: layer, status (covered / gap / deferred), one line of evidence or reason.
Then list the gaps in priority order, each with the smallest fix.
