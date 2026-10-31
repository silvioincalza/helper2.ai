# 0031 — Setup guide: "Guide me" from source data, one snippet per AI client, checked steps from the README

**Status.** Accepted. **Builds on:** [0002](../foundation/0002-modular-monolith-hexagonal.md), [0004](../foundation/0004-persistence-and-provenance.md), [0015](0015-ai-guide-evidence-gate.md)

## Context
The AI guide picks among search candidates; asked "What is X, and what can I use it for?" on a tool's own page,
it can only repeat the tool back with a short reason. What a user needs on that page is different: how to install
the tool, what it depends on, what to configure, and how to connect it to the AI tool they use (Claude Code,
Cursor, VS Code, Codex and others). A wrong install command is worse than none: it is copied into a terminal,
and a snippet copied into an AI client's config file runs on the user's machine.

## Decision
1. **A separate endpoint**, `GET /api/v1/resources/{id}/setup`, in its own module `ai.helper2.setup`, apart from
   the recommendation guide. Two use cases, `GetResourceSetup` and `GetToolGuide` (`setup/usecase`), find the
   resource in the catalog, take its MCP Registry name and the identifiers of its documents (`ToolRef`), and call the
   domain; `SetupController` only maps requests and answers.
2. **Facts from the source, at request time.** For an MCP server with an Official MCP Registry record, the latest
   entry is read by name (`GET /v0.1/servers/{name}/versions/latest`): remotes (Streamable HTTP or SSE, `https`
   only), packages run over stdio (npm, PyPI, OCI, NuGet) with their arguments, environment variables and headers.
   The answer lists where and when it was read. Nothing is stored in the database, so the Scout's change
   detection is untouched. Answers (a missing entry too) are kept in memory for `H2_SETUP_CACHE_TTL` (an hour), at
   most 500 names, so page views do not become registry calls; failures are not kept. When the registry cannot be
   read, the README config example of point 8 answers; without one the endpoint says 503.
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
   run option. Each format follows that documentation. There are 8: Claude Code, Claude Desktop, Cursor, VS Code
   (GitHub Copilot), OpenAI Codex, Windsurf, Gemini CLI and Cline, the MCP-capable clients with the widest reach in
   October 2026 (no usage data of our own yet). When the documentation gives no way to configure an option (SSE in
   Codex), the client says so instead of a snippet. Adding a client is adding a row. The writers (JSON, TOML, POSIX
   shell) are framework-free; every string escapes controls, DEL, C1, U+2028/U+2029 and the byte order mark as
   `\uXXXX`, and a shell word holding one of them is written with `$'…'` escapes, so a pasted snippet holds only
   printable characters. A parse-back test reads every client's snippet with a real JSON and TOML parser and a
   POSIX word splitter, with hostile keys and values.
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
   - **Own GitHub token.** READMEs are read with the setup guide's own token, `H2_SETUP_GITHUB_TOKEN` (5,000 calls
     an hour), never the Scout's `H2_GITHUB_TOKEN`. Without it, GitHub is read anonymously (60 calls an hour).
   - **Hourly fetch budget.** Every document fetch takes one unit of `H2_SETUP_HOURLY_FETCHES` (1,000 per UTC
     hour), counted in Redis across replicas under the `redis` policy
     ([0033](../foundation/0033-resilience-policies.md)); when Redis cannot answer, each replica counts on its own.
     Over the budget nothing is fetched, the answer is not cached, and `helper2.setup.fetches.refused` counts the
     refusal.
   - **Document cache.** Documents are kept in memory per replica for `H2_SETUP_CACHE_TTL`, up to
     `H2_SETUP_DOCUMENT_CACHE_MB` (32 MB) of text in all, the least recently used going first (Caffeine, weighed by
     length). A document is cut at 512 KB.
10. **Step-by-step guide (model, checked).** `POST /resources/{id}/guide` sends the tool's own documents (GitHub
   README, npm README, Hugging Face model or Space card; the useful sections of long ones) to the fast model. For
   each step it writes one short instruction and picks the passage and the command behind it; the instruction is
   the only text it writes, and the page labels and marks it as AI-written ([0038](0038-ai-content-marking.md)).
   `ToolGuideValidator` decides what is shown:
   - a **command** is whole logical lines of one fenced block (a backslash-continued command counts as one line)
     or one whole inline code span, compared case-sensitively with whitespace collapsed; a command that does not
     pass is dropped, never shortened or merged;
   - a step without a valid command stands only on a **quote** found in the document (markdown, links and fences
     ignored); a valid command is its own evidence;
   - a command that hands text to a shell or interpreter (a pipe into `sh`, `bash`, `python`, `node`, `perl`,
     `pwsh`, `iex`, also through `sudo` or `env`; `sh -c "$(curl …)"`, `bash <(curl …)`, `eval "$(wget …)"`,
     `Invoke-Expression`), uses sudo, runs `chmod 777` or deletes recursively is kept but marked `caution`;
   - the page says the steps are written by AI from the document, that every quote and command is copied exactly
     from it and that commands are not reviewed for safety;
   - a step is never repeated in a later section; at most 8 per section.
   The fixed setup set of [0030](../quality/0030-guide-evaluation.md)
   (point 6) measures steps kept and rejected, snippet coverage and latency on every run.
11. **One guide per tool, no free-form questions.** The guide answers only what the page is for: setup options,
   client snippets and checked steps. It is the same for every visitor and kept for 7 days in the shared cache
   `tool-guide` (table `ai_cache`, [0004](../foundation/0004-persistence-and-provenance.md)), keyed by a hash of resource,
   documents and prompt. A cached answer holds the checked steps and the documents' links and
   read dates, never a document's text. The endpoint takes no input but the resource id, shares the guide's rate
   limit and has its own daily cap (`H2_SETUP_GUIDE_DAILY_MODEL_CALLS`, 300); an answer is kept for
   `H2_SETUP_GUIDE_CACHE_TTL`. A database error or a lookup over its time limit is a miss. An entry that does not decode is logged and treated as a miss, and the next answer
   replaces it, so it costs one model call, not one per request. Each model call runs under the `llm-chat` circuit
   ([0033](../foundation/0033-resilience-policies.md)); a malformed answer is asked for once more. When both answers are not the
   requested JSON, the "unavailable" answer is cached for `H2_SETUP_UNREADABLE_TTL` (10 minutes), so the same
   resource does not spend two model calls on every request. A provider error, an open circuit or a spent
   budget is not cached: each recovers by itself. The user sees the same "unavailable" answer in every case but the
   budget.
12. **Settings.** Every knob is an `H2_SETUP_*` variable in the `helper2.setup` block of `application.yaml`:
   `DOCUMENT_TIMEOUT` (10 s), `NPM_README_BASE_URL`, `CACHE_TTL`, `DOCUMENT_CACHE_MB`, `HOURLY_FETCHES`,
   `GITHUB_TOKEN` (empty), `GUIDE_DAILY_MODEL_CALLS`, `GUIDE_REASONING` (false), `GUIDE_CACHE_TTL` (7 days),
   `UNREADABLE_TTL` (10 minutes).

## Consequences
- Every MCP server with a registry entry or a README config example gets copy-paste setup for 8 clients without a
  model call; every tool with a GitHub, npm or Hugging Face document gets checked steps.
- A visitor types nothing into Guide me, so no user text reaches the model, the cache or the logs from this page.
- The guide never fills gaps: every step comes from the tool's own documentation.
- README fetches never spend the Scout's GitHub quota; the hourly budget bounds the setup guide's own. Registry
  snippets need no fetch budget.
- One more secret to rotate (`H2_SETUP_GITHUB_TOKEN`, a read-only token for public repositories).
- Each client row links its own documentation, so every format can be re-checked at its source.

## Alternatives considered
- **Model-written guide from its own knowledge.** Richest, but breaks the evidence rule and invents commands.
- **Capture registry install data in `source_record.extra` at collect time.** Gives provenance with the record,
  but changes every MCP record's fingerprint on the next pass and queues enrichments for all of them.
- **Caches: a hand-written LRU map vs Caffeine (chosen).** A map bounded by a count of resources cannot bound
  memory when one resource holds up to three 512 KB documents. Caffeine weighs entries by length, expires them by
  time and loads each key once under concurrency, in one small, widely used library (3.2.3, Apache 2.0).
- **The Scout's GitHub token with a hard hourly budget.** No secret to add, but every README fetch still comes out of
  the Scout's 5,000 calls, so a busy hour of Guide me slows the Scout. An own token (chosen) keeps the quotas apart.
- **Free-form questions about a tool.** A text box answered from the tool's documents with the same checks. It turns
  the page into a chat with one README: a second prompt branch, user text in the cache key and the model input,
  and an answer type no other part of Helper2 needs. The plain guide covers what the page is for.
- **Every client whose documentation we could check (19).** More reach at the margin, but each format must be
  re-checked when its client changes, and several need their own writer (YAML) and their own "not supported"
  rules. Eight clients cover the large majority of MCP users.

## Code
- `backend/src/main/kotlin/ai/helper2/setup/` — domain: `Setup.kt`, `Launch.kt`, `Clients.kt`, `SetupGuides.kt`,
  `RunOptionRules.kt`, `Formats.kt`, `Materials.kt`, `ToolGuideAnswer.kt`, `ToolGuidePorts.kt`,
  `ToolGuideValidator.kt`, `ToolGuides.kt`.
- `backend/src/main/java/ai/helper2/setup/usecase/` — `GetResourceSetup`, `GetToolGuide`.
- `backend/src/main/java/ai/helper2/setup/internal/` — `RegistryMcpServerSpecs`, `HttpToolMaterials`,
  `HourlyFetchBudget`, `ReadmeMcpConfigs`, `SpringAiToolGuideModel`, `SharedToolGuideCache`, `SetupConfiguration`.
- `backend/src/main/java/ai/helper2/setup/web/SetupController.java`, `backend/src/main/resources/prompts/setup/tool-guide.txt`,
  `frontend/src/components/SetupGuide.tsx`.

## Diagrams
[flow](../../diagrams/flow-0031-setup-guide.svg) · [sequence](../../diagrams/sequence-0031-setup-guide.svg)

## Roadmap
- Guide me on every result card (same panel, inline).
- PyPI package pages as a document source (no PyPI source in the catalog yet).
- Known limits: [roadmap § 10](../../roadmap.md#10-known-limits).
