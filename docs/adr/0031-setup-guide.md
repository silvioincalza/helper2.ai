# 0031 — Setup guide: "Guide me" from source data, one snippet per AI client, checked steps from the README

**Status.** Accepted (2026-10-27). **Builds on:** [0002](0002-modular-monolith-hexagonal.md), [0004](0004-persistence-and-provenance.md), [0015](0015-ai-guide-evidence-gate.md)

## Context
The AI guide picks among search candidates; asked "What is X, and what can I use it for?" on a tool's own page,
it can only repeat the tool back with a short reason. What a user needs on that page is different: how to install
the tool, what it depends on, what to configure, and how to connect it to the AI tool they use (Claude Code,
Cursor, VS Code, Codex and many more). A wrong install command is worse than none: it is copied into a terminal,
and a snippet copied into 19 clients' config files runs on the user's machine.

## Decision
1. **A separate endpoint**, `GET /api/v1/resources/{id}/setup`, in its own module `ai.helper2.setup`, apart from
   the recommendation guide.
2. **Facts from the source, at request time.** For an MCP server with an Official MCP Registry record, the latest
   entry is read by name (`GET /v0.1/servers/{name}/versions/latest`): remotes (Streamable HTTP or SSE, `https`
   only), packages run over stdio (npm, PyPI, OCI, NuGet) with their arguments, environment variables and headers.
   The answer lists where and when it was read. Nothing is stored in the database, so the Scout's change
   detection is untouched. Answers (a missing entry too) are kept in memory for an hour, at most 500 names, so page
   views do not become registry calls; failures are not kept.
3. **Launch from the registry type.** `runtimeHint` when the entry has one, else `npx -y pkg@v`, `uvx pkg==v`,
   `docker run -i --rm -e VAR image`, `dnx pkg@v --yes`. A registry type without a known launcher, or a package
   that is not stdio, is left out rather than guessed.
4. **Only package runners, checked before any snippet is written** (`RunOptionRules`, domain). The same rules
   apply to registry entries and README examples; an option that fails is dropped, never repaired.
   - **Launcher:** `npx`, `uvx`, `bunx`, `dnx`, or `docker run`. Never an interpreter (`node`, `python`, `deno`,
     `bun`), a shell, `uv run`, `podman` or a path to a program.
   - **Launcher flags:** a short allow-list per runner before the package (`-y`/`--yes`/`-q` for npx; `--from`,
     `--with`, `--python`, `--isolated` for uvx with checked values; `--bun` for bunx). Anything else that starts
     with `-` before the package is rejected, which excludes eval and inline code (`-c`, `--call`, `-e`, `-p`).
   - **Package:** the first positional, never a URL, a path, a `git+` spec or a leading `-`.
   - **docker:** `run`, then only `-i`, `--rm`, `-t`, `--init`, `-e NAME[=value]`, a named volume (`-v name:/path`),
     a named network other than `host`, `--name`, `--pull`, then the image as the only positional with nothing
     after it. Privileged mode, host paths, host network or pid, added capabilities, env files and published ports
     are rejected.
   - **Grammar:** registry identifiers per registry type (npm name, PyPI name, NuGet id, OCI reference); a version
     is `[A-Za-z0-9][A-Za-z0-9._+-]*`; environment variable names `[A-Za-z_][A-Za-z0-9_]*`; header names the RFC
     9110 token grammar; argument names `-x` or `--name`. No name, value or argument holds a control character.
5. **Secrets are never filled in.** A secret's default is dropped when parsing; every value the user supplies is
   a `<placeholder>`. A published template such as `Bearer {api_key}` keeps its shape with `<api_key>`. VS Code gets
   a password `input` instead of a placeholder.
6. **Clients are a table** (`Clients.ALL`): id, name, the client's own documentation URL, and how it renders each
   run option. Each format was checked against that documentation on 2026-10-27 (19 clients). When the
   documentation gives no way to configure an option (SSE in Codex, Goose, Zed, Kiro, opencode; env or headers in
   JetBrains; headers in Amazon Q; local servers in LM Studio, whose documentation shows only remote ones), the
   client says so instead of a snippet. Adding a client is adding a row. The writers (JSON, TOML, YAML, POSIX shell)
   are framework-free; every string escapes controls, DEL, C1, U+2028/U+2029 and the byte order mark as `\uXXXX`,
   and a shell word holding one of them is written with `$'…'` escapes, so a pasted snippet holds only printable
   characters. A parse-back test reads every client's snippet with a real JSON, TOML and YAML parser and a POSIX
   word splitter, with hostile keys and values.
7. **No setup data, no invention.** A resource whose sources state nothing gets an empty answer; the page says so
   and offers the step-by-step guide below.
8. **README config examples (no model).** Without a registry entry, an MCP server's README is read (GitHub REST
   API, or the npm README on jsDelivr) and its `mcpServers` / `servers` / `context_servers` / `mcp` JSON example
   gives the run option, under the rules of point 4. A combined example often configures other servers next to
   this one; only the entry whose key or package names this resource (its npm package, repository name or title,
   compared without case, punctuation or the words "mcp" and "server") is kept. A variable named like a secret
   becomes a placeholder. The page says such a command is copied from the README and not reviewed for safety.
   `GET /setup` has its own rate limit, because it can fetch.
9. **Fixed hosts, checked names.** Documents are fetched only from GitHub, jsDelivr and Hugging Face, by
   identifiers parsed from the resource's links: a GitHub owner follows GitHub's rules (1–39 letters, digits and
   single inner hyphens), a repository name is never `.` or `..`, and each part of a Hugging Face id follows the
   Hub's rules (no leading or trailing `-` or `.`, no `--` or `..`). A link can therefore never reach another API
   path with the server's token.
10. **Step-by-step guide (model, checked).** `POST /resources/{id}/guide` sends the tool's own documents (GitHub
   README, npm README, Hugging Face model or Space card; the useful sections of long ones) to the fast model, which
   may only pick and order passages. `ToolGuideValidator` decides what is shown:
   - a **command** is whole logical lines of one fenced block (a backslash-continued command counts as one line)
     or one whole inline code span, compared case-sensitively with whitespace collapsed; a command that does not
     pass is dropped, never shortened or merged;
   - a step without a valid command stands only on a **quote** found in the document (markdown, links and fences
     ignored); a valid command is its own evidence;
   - a command that hands text to a shell or interpreter (a pipe into `sh`, `bash`, `python`, `node`, `perl`,
     `pwsh`, `iex`, also through `sudo` or `env`; `sh -c "$(curl …)"`, `bash <(curl …)`, `eval "$(wget …)"`,
     `Invoke-Expression`), uses sudo, runs `chmod 777` or deletes recursively is kept but marked `caution`;
   - the page says every quote and command is copied exactly from the document and that commands are not
     reviewed for safety;
   - a step is never repeated in a later section; at most 8 per section.
   Measured on 11 real resources (2026-10-28): 9 with steps, in 1–10 s; one gated Hugging Face model had no
   readable card, one README gave nothing that passed. The fixed setup set of [0030](0030-guide-evaluation.md)
   (point 6) measures steps kept and rejected, snippet coverage and latency on every run.
11. **Same function for "ask" about a tool.** The same endpoint takes an optional question; the answer is an
   ANSWER section with the same checks. Answers are cached in Redis for 7 days, keyed by a hash of resource,
   documents, question and prompt. A cached answer holds the checked steps and the documents' links and read
   dates, never the question or a document's text; the question is added back to the response only. The endpoint
   shares the guide's rate limit and has its own daily cap (`helper2.setup.guide.daily-model-calls`).

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
`RunOptionRules.kt`, `Formats.kt`, `Materials.kt`, `ToolGuide.kt`), `backend/src/main/resources/prompts/setup/tool-guide.txt`,
`backend/src/main/java/ai/helper2/setup/` (registry adapter, endpoint), `frontend/src/components/SetupGuide.tsx`.

## Diagrams
[flow](../diagrams/flow-0031-setup-guide.svg) · [sequence](../diagrams/sequence-0031-setup-guide.svg)

## Roadmap
- Guide me on every result card (same panel, inline).
- PyPI package pages as a document source (no PyPI source in the catalog yet).
