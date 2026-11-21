@AGENTS.md

## Claude Code

The workflow, the reading list, the commands and the rules are in `AGENTS.md`. This file holds only what is specific
to Claude Code.

- Specialist agents are in `.claude/agents/`, slash commands in `.claude/commands/`, skills in `.claude/skills/`.
  Each file says in its `description` when to use it. The skills hold the detail of the rules: load the one that
  names your work before you start (the index is in `AGENTS.md`, "Detail lives in").
- `.claude/hooks/guard-bash.py` stops a merge, a push to main, a plain force push, a change to prod and AI
  attribution before the command runs. Do not work around it: ask the human in the loop.
- Never let a commit, PR, issue or comment carry a "Generated with Claude Code" footer or a
  Co-Authored-By line. If a tool adds one automatically, do not post through it: give the text
  to the human in the loop to post.
- When a prod deploy waits for approval, give the direct approval link.
- Merging is denied in `.claude/settings.json`. Do not add an `allow` for it, in this file or in
  `.claude/settings.local.json`: the human in the loop merges every PR himself (see AGENTS.md). The deny rules
  catch the usual command, not every form of it: the rule in AGENTS.md holds either way.
- Personal or machine-local notes: `CLAUDE.local.md` (git-ignored). Notes, reviews and memory:
  `.private/` (git-ignored).
