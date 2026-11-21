---
description: Run the AI architecture check (15 layers) on a change or design - this project's own check, no AI Unified Process skill covers it
argument-hint: [scope, e.g. a branch, a module or a design doc]
---
Run the AI architecture check of the skill `ai-architecture-check` (`.claude/skills/ai-architecture-check/SKILL.md`)
on: $ARGUMENTS
(if empty: the uncommitted changes, `git diff HEAD`).

The check is this project's own: no AI Unified Process skill covers it. It writes no file. A gap in a specification
goes to `/open-questions`; a decision it leads to is an ADR, made with `/adr`.

Output one table: layer, status (covered / gap / deferred), one line of evidence or reason, naming the `UC-*`,
`NFR-*` or `C-*` the evidence comes from when there is one.
Then list the gaps in priority order, each with the smallest fix.
