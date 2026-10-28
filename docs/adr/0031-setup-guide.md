# 0031 — Setup guide: "Guide me" from source data, one snippet per AI client, checked steps from the README

**Status.** Accepted (2026-10-27). **Builds on:** [0002](0002-modular-monolith-hexagonal.md), [0004](0004-persistence-and-provenance.md), [0015](0015-ai-guide-evidence-gate.md)

## Context
"Guide me" on a tool's page used to ask the AI guide "What is X, and what can I use it for?". The guide picks
among search candidates, so the answer was mostly the tool repeated back with a short reason, and the search
results under it were unrelated. What a user needs on that page is different: how to install the tool, what it
depends on, what to configure, and how to connect it to the AI tool they use (Claude Code, Cursor, VS Code, Codex
and many more). A wrong install command is worse than none: it is copied into a terminal.

## Decision
1. **A separate endpoint**, `GET /api/v1/resources/{id}/setup`, in its own module `ai.helper2.setup`. The
   recommendation guide (`/guide`) is unchanged.
2. **Facts from the source, at request time.** For an MCP server with an Official MCP Registry record, the latest
   entry is read by name (`GET /v0.1/servers/{name}/versions/latest`): remotes (Streamable HTTP or SSE, `https`
   only), packages run over stdio (npm, PyPI, OCI, NuGet) with their arguments, environment variables and headers.
   The answer lists where and when it was read. Nothing is stored in the database, so the Scout's change
   detection is untouched. Answers (a missing entry too) are kept in memory for an hour, at most 500 names, so page
   views do not become registry calls; failures are not kept.
3. **Launch from the registry type.** `runtimeHint` when the entry has one, else `npx -y pkg@v`, `uvx pkg==v`,
   `docker run -i --rm -e VAR image`, `dnx pkg@v --yes`. A registry type without a known launcher, or a package
   that is not stdio, is left out rather than guessed.
4. **Secrets are never filled in.** A secret's default is dropped when parsing; every value the user supplies is
   a `<placeholder>`. A published template such as `Bearer {api_key}` keeps its shape with `<api_key>`. VS Code gets
   a password `input` instead of a placeholder.
5. **Clients are a table** (`Clients.ALL`): id, name, the client's own documentation URL, and how it renders each
   run option. Each format was checked against that documentation on 2026-10-27 (19 clients). When the
   documentation gives no way to configure an option (SSE in Codex, Goose, Zed, Kiro, opencode; env or headers in
   JetBrains; headers in Amazon Q; local servers in LM Studio, whose documentation shows only remote ones), the client says so instead of a snippet. Adding a client is adding a row.
6. **No setup data, no invention.** A resource whose sources state nothing gets an empty answer; the page says so
   and offers the AI guide question as before.

7. **README config examples (no model).** Without a registry entry, an MCP server's README is read (GitHub REST
   API, or the npm README on jsDelivr) and its `mcpServers` / `servers` / `context_servers` / `mcp` JSON example
   gives the run option. Only known launchers (npx, uvx, docker, node, python, …) are kept, never a shell; a
   variable named like a secret becomes a placeholder. `GET /setup` has its own rate limit, because it can fetch.
8. **Step-by-step guide (model, checked).** `POST /resources/{id}/guide` sends the tool's own documents (GitHub
   README, npm README, Hugging Face model or Space card; the useful sections of long ones) to the fast model, which
   may only pick and order passages. `ToolGuideValidator` decides what is shown:
   - a **command** is whole logical lines of one fenced block (a backslash-continued command counts as one line)
     or one whole inline code span, compared case-sensitively with whitespace collapsed; a command that does not
     pass is dropped, never shortened or merged;
   - a step without a valid command stands only on a **quote** found in the document (markdown, links and fences
     ignored); a valid command is its own evidence;
   - commands that pipe a download into a shell, use sudo or delete recursively are kept but marked `caution`;
   - a step is never repeated in a later section; at most 8 per section.
   Measured on 11 real resources (2026-10-28): 9 with steps, in 1–10 s; one gated Hugging Face model had no
   readable card, one README gave nothing that passed.
9. **Same function for "ask" about a tool.** The same endpoint takes an optional question; the answer is an ANSWER
   section with the same checks. Answers are cached in Redis for 7 days by resource, documents, question and
   prompt; the endpoint shares the guide's rate limit and has its own daily cap (`helper2.setup.guide.daily-model-calls`).

## Consequences
- Every MCP server with a registry entry or a README config example gets copy-paste setup for 19 clients without a
  model call; every tool with a GitHub, npm or Hugging Face document gets checked steps.
- The guide is only as good as the tool's own documentation; it never fills gaps.
- Client formats change; the docs URL on each row is where to re-check them.

## Alternatives considered
- **Model-written guide from its own knowledge.** Richest, but breaks the evidence rule and invents commands.
- **Capture registry install data in `source_record.extra` at collect time.** Gives provenance with the record,
  but changes every MCP record's fingerprint on the next pass and queues enrichments for all of them.

## Code
`backend/src/main/kotlin/ai/helper2/setup/` (domain: `Setup.kt`, `Launch.kt`, `Clients.kt`, `SetupGuides.kt`,
`Materials.kt`, `ToolGuide.kt`), `backend/src/main/resources/prompts/setup/tool-guide.txt`,
`backend/src/main/java/ai/helper2/setup/` (registry adapter, endpoint), `frontend/src/components/SetupGuide.tsx`.

## Roadmap
- Guide me on every result card (same panel, inline).
- A fixed set of resources in the eval script (ADR 0030) with steps kept and rejected per reason.
- PyPI package pages as a document source (no PyPI source in the catalog yet).
