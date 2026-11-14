@AGENTS.md

## Claude Code

- Specialist agents are in `.claude/agents/`, slash commands in `.claude/commands/`. Each file
  says in its `description` when to use it.
- Never let a commit, PR, issue or comment carry a "Generated with Claude Code" footer or a
  Co-Authored-By line. If a tool adds one automatically, do not post through it: give the text
  to Silvio to post.
- When a prod deploy waits for approval, give the direct approval link.
- Merging is denied in `.claude/settings.json`. Do not add an `allow` for it, in this file or in
  `.claude/settings.local.json`: Silvio merges every PR himself (see AGENTS.md). The deny rules
  catch the usual command, not every form of it: the rule in AGENTS.md holds either way.
- Personal or machine-local notes: `CLAUDE.local.md` (git-ignored). Notes, reviews and memory:
  `.private/` (git-ignored).
