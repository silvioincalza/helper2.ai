# Helper2.ai — User Journeys

The journeys Helper2.ai is built and tested against.
The success criteria at the end say which tests verify each journey today. What is implemented: see [the ADRs](adr/README.md).

## Personas

| Persona | Looks for | Goal | Vocabulary | Main risk |
|---|---|---|---|---|
| **Giulia** — marketing manager, non-technical, Italian speaker | Everyday tools | Find a trustworthy tool to draft and translate newsletters | "scrivere", "tradurre", "gratis?" | Overwhelmed by jargon; trusts a stale or wrong listing |
| **Sam** — student / knowledge worker | Everyday tools | Find something to summarize research PDFs | "summarize papers", "free" | Picks a tool that no longer exists |
| **Dev** — backend engineer | Developer resources | Find an MCP server for Postgres and a model that runs locally | "mcp postgres", "llama gguf", "license" | Integrates abandoned or non-OSS code |
| **Ava** — AI builder / agent developer | Developer resources | Find agent skills, prompts, and libraries that work with a given framework | "skill", "langchain", "compatible with" | Trusts an AI-invented "integrates with" claim |
| **Ops/Admin** — Helper2 maintainer | — | Keep data honest: review queue, scout runs, source health | — | Silent connector failure; hallucinated metadata published |

## UJ-1 Search once (one box)

1. User lands on the home page; the search box sits in the middle of the hero, above the News and Events boards.
2. There is one box and no scope to choose: the same search covers tools, models, MCP servers, libraries, papers and news.
3. Types a name ("ollama"), keywords ("mcp postgres"), an intent ("I want to summarise PDFs"), or a use case ("traduzione").
4. On the home page, Enter (or the button) opens the results on `/search`; focus moves to the results. There, results follow typing when it pauses (or on Enter). The first search shows skeleton rows and a per-source "querying…" line; a later search keeps the previous results on screen, dimmed, until the new ones arrive.
5. Results arrive in one response, once every source has answered or hit its timeout. The list is on the left, a preview of the selected result on the right (inside the list on a phone); the selected result is in the URL. Under the results, a line states e.g. "4 of 5 sources answered" and a tile per source shows its outcome, response time or cache age.

**Key decisions:** whether to refine, or to ask the guide. **Success:** relevant result in the first page; user understands where results came from.

| Variant | Behavior |
|---|---|
| Success | Aggregated, deduplicated results; every configured source listed with outcome. |
| Empty | "No results from the N sources searched" + the list of sources + suggestions: remove filters, ask the guide, try English keywords. Never "nothing exists". |
| Partial | Banner: "Partial results — GitHub timed out". Results from other sources still shown. Coverage marked `partial`. |
| Stale cache | Source failed live but a recent cached response exists: results shown, badge "cached 14 min ago — live check failed". |
| All failed / API down | Error state with retry; no fake results. |
| Rate-limited | Source marked RATE_LIMITED (not hidden), others continue. |
| Invalid input | A query longer than 200 characters is cut to 200 in the URL and the request (the guide takes 3–500); an empty box plus Enter returns to the empty home page. The API answers an invalid `q` with 400 problem+json. |

## UJ-2 Ask the guide without losing the search

1. With a query and filters on the page, the user clicks **Ask the guide** (or presses Shift+Enter, or follows the "Sounds like a question" hint after 4+ words).
2. The guide's answer appears above the results; query, filters, sort and page persist (they live in the URL: `q`, `ask`, filters).
3. Closing the answer keeps the search; back/forward and shared links reproduce the same state. `/search` and `/guide` links land on the one page.

**Success:** zero re-typing; URL fully describes the state. A new question while one is being answered replaces it.
**Failure case:** the guide fails or is unavailable, or its stream stalls for 45 s or ends without an answer → the search results stay, the answer area says what went wrong and offers **Try again**.

## UJ-3 Refine: filter and sort

1. User opens filters (drawer on mobile): type, audience, use case, tech level, source, license, pricing, platform, open-source, updated-after.
2. Each facet shows counts and an **Unknown** bucket for results where the source did not say.
3. Sort: Relevance (default), Newest (publication date), Recently updated. Results with unknown dates sort last and are labeled "date unknown".

**Key decision:** include or exclude unknowns. Selecting a value excludes unknowns; selecting "Unknown" includes them explicitly.
**Failure case:** filters produce zero results → empty state lists the active filters with one-click removal.

## UJ-4 Evaluate a result (card → detail)

1. Card shows: title, concise description (+ where the text came from), type, tags, audience, source badges, the three dates (published / last updated / discovered by Helper2), freshness, real popularity metrics with their source, link to the original.
2. User opens the detail page: metadata per field with origin and verification, evidence (source URL, retrieval time, excerpt), relationships (verified vs unverified candidates kept apart), optional AI summary with citations per claim, source records (provenance).
3. User follows "Open original" (new tab, `noopener noreferrer`), hostname visible before clicking.

**Success:** user can answer "who says so, and when?" for every displayed fact.
**Failure cases:** sources disagree → "Sources disagree on license: MIT (GitHub) vs Apache-2.0 (npm)" — never silently resolved; resource not yet indexed → 404 with link back to search.

## UJ-5 Report wrong or outdated information

1. On a detail page, "Report a problem" → kind (wrong / outdated / broken link / other), optional field, message.
2. Submit → 202 "Thanks — a human will review it". Report enters the review queue; nothing on the page changes automatically.

**Failure cases:** validation error inline; rate-limited → friendly message; network error → retry with text preserved.

## UJ-6 Save resources

1. Anonymous user bookmarks a card → stored in `localStorage`; visible on **Saved**.
2. If storage is unavailable (private mode), the toggle explains saving is unavailable in this browser.
3. The public site never asks for an account: the Saved page says "Saved in this browser only." A user who signed in (only possible on `/admin`, UJ-7) gets local bookmarks merged into the account once; they then follow the account across devices.

## UJ-7 Sign in (admin only)

Sign-in exists for administrators ([ADR 0023](adr/0023-identity-and-admin.md)); the header never offers it.

1. An anonymous visitor to `/admin` sees a prompt and the configured OAuth2 provider (a GitHub OAuth App, profile `oauth-github`); without a provider the app is anonymous-only and `/admin` says an administrator account is needed.
2. After login the user returns to `/admin`; the header shows the display name and **Sign out**; bookmarks merge (UJ-6). A signed-in user without the `ADMIN` role sees the forbidden message.
3. Sign-out returns to anonymous mode; search keeps working.

**Failure case:** sign-in fails or the provider is down → `/admin?signin=failed` says "Sign-in did not complete. Try again."; anonymous use is unaffected.

## UJ-8 Understand coverage and sources

1. **Sources** page lists every configured source: mode (LIVE, INDEXED, CURATED, DEMO), what it covers, homepage, terms, attribution, last outcome and check time.
2. Footer on every page: "Helper2.ai searches the sources listed here — not the whole web."

## UJ-9 Admin: keep the catalog honest

1. Admin signs in (role ADMIN from an allow-list), opens **Admin**.
2. Sees the review queue: enrichment outputs that failed validation, source conflicts, unverified relationship candidates, user reports. Accepts or rejects each with a note.
3. Triggers a scout run for one source or all; sees run history with items seen/changed and failures.

**Failure cases:** LLM provider unavailable → runs complete with `LLM_UNAVAILABLE`, original source metadata stays published; one connector failing does not stop others.

## Success criteria (product level)

| ID | Criterion | Verified by |
|---|---|---|
| SC-1 | One query returns aggregated results from every enabled source that responded within its timeout | Today: API integration tests; UI component tests (Vitest + Testing Library, API mocked) for results, partial banner and empty/all-failed states. [Roadmap](roadmap.md): E2E journey (not yet automated) |
| SC-2 | Every configured source appears in the status list with a truthful outcome | Today: orchestrator unit tests; UI component tests for the source status panel (every outcome in words). [Roadmap](roadmap.md): E2E journey (not yet automated) |
| SC-3 | Asking the guide never loses query/filters | Today: UI component tests: the URL keeps `q`, `ask` and filters; closing the answer keeps the search; back/forward restore the box; retry and replacing a running question. [Roadmap](roadmap.md): E2E journey (not yet automated) |
| SC-4 | No invented dates/popularity; unknowns labeled | domain tests + UI tests |
| SC-5 | Every published AI-extracted fact links to evidence; unsupported → unverified/review | enrichment validator tests |
| SC-6 | Search works with cache down, LLM down, one or more sources down | resilience tests |
| SC-7 | Keyboard-only and screen-reader users can complete UJ-1…UJ-6 | axe smoke tests in jsdom (color contrast excluded), focus and live-region component tests + manual checklist |
