@AGENTS.md

## Claude Code

- Specialist agents are in `.claude/agents/`. Slash commands: `/team <task>` runs the agents
  together with Silvio in the loop, `/adr <topic>` drafts an ADR, `/arch-check [scope]` runs the
  AI architecture check.
- Never let a commit, PR, issue or comment carry a "Generated with Claude Code" footer or a
  Co-Authored-By line. If a tool adds one automatically, do not post through it: give the text
  to Silvio to post.
- When a prod deploy waits for approval, give the direct approval link.
- Personal or machine-local notes: `CLAUDE.local.md` (git-ignored). Notes, reviews and memory:
  `.private/` (git-ignored).
