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

1. User lands on the home page; the search box is the first focusable element after the skip link.
2. There is one box and no scope to choose: the same search covers tools, models, MCP servers, libraries, papers and news.
3. Types a name ("ollama"), keywords ("mcp postgres"), an intent ("I want to summarise PDFs"), or a use case ("traduzione").
4. Enter opens the results page (`/search`); there, results follow the box when typing pauses. The first search shows a skeleton and a per-source "querying…" line; a later search keeps the previous results on screen, dimmed, until the new ones arrive.
5. Results arrive in one response, once every source has answered or hit its timeout; under the results a summary line states e.g. "4 of 5 sources responded · 1 timed out", and one tile per source states its outcome.

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
2. The guide's picks appear in a bar above the results; the list keeps its order, and each pick gets a badge and its reason in its own row. Query, filters, sort, page and the selected result persist (they live in the URL: `q`, `ask`, filters, `id`).
3. Closing the answer keeps the search; back/forward and shared links reproduce the same state, the selected result included.

**Success:** zero re-typing; URL fully describes the state. A new question while one is being answered replaces it.
**Failure case:** the guide fails or is unavailable, or its stream stalls for 45 s or ends without an answer → the search results stay, the answer area says what went wrong and offers **Try again**.

## UJ-3 Refine: filter and sort

1. User opens the filters, folded above the list: type, audience, use case, tech level, source, license, pricing, platform, open-source, updated-after.
2. Each facet shows counts and an **Unknown** bucket for results where the source did not say.
3. Sort: Relevance (default), Newest (publication date), Recently updated. Results with unknown dates sort last and are labeled "date unknown".

**Key decision:** include or exclude unknowns. Selecting a value excludes unknowns; selecting "Unknown" includes them explicitly.
**Failure case:** filters produce zero results → empty state lists the active filters with one-click removal.

## UJ-4 Evaluate a result (preview → resource page)

**Entry:** a row on the results page (the preview), "Full details" in the preview, a News or Events row on the home page, a pick chip for a resource outside the list, or a shared `/resource/:id` link.

1. The preview shows the selected result without a page load: type, site, title, Open on source (new tab, `noopener noreferrer nofollow`), Guide me for tools, why it matches or its description (and where the text came from), Use and Build facts (Unknown when no source says), Listed by, and the three dates (published / last updated / discovered by Helper2).
2. "Full details" opens `/resource/:id`: the same parts, from the same component, with the title as the page heading, plus what the type means, the tags, the AI summary with citations per claim (labelled and marked AI-written), what each source says (value, origin, verification, evidence), relationships (verified apart from unverified candidates; rejected never shown), the source records (provenance) and the report form (UJ-5).
3. Guide me opens under the actions with focus on its heading: snippets per AI tool, checked steps from the tool's own documents, a follow-up question.

**Exit:** "Back to results" (shown when the page was opened from the results) works like the browser's Back: the same search, the same selected row, the same scroll position. Otherwise the box in the header and the logo.

| State | Behavior |
|---|---|
| Loading | "Loading…" and skeleton blocks. |
| Not in the index | "Resource not found" as the page heading, why, and the way home; the box stays in the header. No Try again. |
| Error (network, server) | An alert "Could not load this resource" with Try again and the way home. After Try again focus lands on the resource title, or on the error heading when it fails again. |
| No AI summary | Says why, from the enrichment status (not run, unavailable model, rejected). |
| No relationships | "No verified relationships."; the candidates part is left out. |

**Success:** user can answer "who says so, and when?" for every displayed fact.
**Failure case:** sources disagree → "Sources disagree on license: MIT (GitHub) vs Apache-2.0 (npm)", with each source named — never silently resolved.

## UJ-5 Report wrong or outdated information

1. On the resource page, "Report wrong or outdated info" → kind (wrong / outdated / broken link / other), optional field, message.
2. Submit → 202 "Thanks — a human will review it". Report enters the review queue; nothing on the page changes automatically.

**Failure cases:** validation error inline; rate-limited → friendly message; network error → retry with text preserved.

## UJ-6 Save resources

**Entry:** Save in a result's preview or on a resource page; Saved in the header.

1. The user presses Save. A guest's bookmark goes to browser storage (`h2.bookmarks.v1`); a signed-in user's to the account. The button stays pressed: "Saved".
2. Saved (`/saved`) lists them: type tag, site, the date saved, the title linking to the resource page, Open on source and Remove.
3. Remove takes the row out. Focus goes to the next row's Remove (or the one above), or to the empty state's heading; a status line says "Removed … from saved."
4. The public site never asks for an account: the page says "Saved in this browser only." A user who signed in (only possible on `/admin`, UJ-7) gets the browser's bookmarks merged into the account once; the page then says "Saved on your account." and the list follows the account across devices.

**Exit:** a title to its resource page (UJ-4), Open on source, the box in the header, the logo.

| State | Behavior |
|---|---|
| Loading | Signed in, while the account list loads: "Loading…" and skeleton rows, never "Nothing saved yet". |
| Empty | "Nothing saved yet", "Use Save on any result to keep it here.", and Search. |
| Storage blocked | Saving works until the user leaves the page; Saved warns that the browser blocked storage. |
| Account list fails | An alert "Could not load your saved resources" with Try again; focus stays on the page heading and moves to the list when it loads. |
| Account refuses a change | The item comes back and an alert says the account could not be updated. |

## UJ-7 Sign in (admin only)

Sign-in exists for administrators ([ADR 0023](adr/0023-identity-and-admin.md)); the header never offers it.

1. An anonymous visitor to `/admin` sees a prompt and the configured OAuth2 provider (a GitHub OAuth App, profile `oauth-github`); without a provider the app is anonymous-only and `/admin` says an administrator account is needed.
2. After login the user returns to `/admin`; the header shows the display name and **Sign out**; bookmarks merge (UJ-6). A signed-in user without the `ADMIN` role sees the forbidden message.
3. Sign-out returns to anonymous mode; search keeps working.

**Failure case:** sign-in fails or the provider is down → `/admin?signin=failed` says "Sign-in did not complete. Try again."; anonymous use is unaffected.

**Delete the account.** The signed-in name in the header opens the account page (`/account`): who is signed in, and **Delete my account**. A dialog asks to confirm, with focus on Cancel; Escape closes it and focus goes back to the button. On confirm the account and its saved items are deleted and the page starts over, signed out. If the deletion fails, an alert says so and the account stays. While it asks who is signed in, it says "Loading…". Signed out, the page says there is no account to delete and links to Privacy; it offers no sign-in.

## UJ-8 Understand coverage and sources

**Entry:** Sources in the footer of every page; the sources under every search (UJ-1).

1. **Sources** (`/sources`) says Helper2.ai searches these sources and nothing else, not the whole web.
2. Last outcome: one tile per configured source with its last outcome and how long ago (as the server that answered saw it in searches and Scout runs), "Not asked yet", or "Turned off".
3. How sources work: what LIVE, INDEXED, CURATED and DEMO mean.
4. What each source covers: the source (its homepage), mode, resource types, terms and attribution.

Response times, cache ages and the web searches left today belong to a search: they are under the results (UJ-1), not on this page.

**Exit:** a source's homepage or terms (new tab), the box in the header, the logo.

| State | Behavior |
|---|---|
| Loading | "Loading…" and a skeleton block. |
| Error | An alert "Could not load the sources" with Try again; focus stays on the page heading. |
| Empty | "No sources are configured" and the way home. |

## UJ-9 Admin: keep the catalog honest

**Entry:** **Admin** in the header, for an administrator only (UJ-7).

1. Admin signs in (role ADMIN from an allow-list), opens **Admin**.
2. Review queue: enrichment outputs that failed validation, source conflicts, unverified relationship candidates, course candidates, user reports. Each row: the kind, the date, the resource (a link to its page), the reason and the details. A status filter (Open, Accepted, Rejected, Superseded) and pages of 20. Accept or Reject on each open item; a resolved item shows its status.
3. Scout runs: **Run now** starts a run of every source; the table lists each source's runs with the status, start, items seen and changed, and the message. While a run is running the table follows it until it ends.

One action at a time: while a request runs, the other buttons wait.

**Exit:** a resource page, the box in the header, the logo.

| State | Behavior |
|---|---|
| Loading | Skeleton rows for the queue, "Loading…" for the runs. |
| Queue error | An alert "Could not load the review queue" with Try again; focus stays on the queue's heading. |
| Empty | "Nothing in this queue." / "No runs yet." |
| Action failed | An alert line: the action failed, or a Scout run is already in progress. |
| Not an administrator | An alert that an administrator account is needed, and the way home. |

**Failure cases:** LLM provider unavailable → runs complete with `LLM_UNAVAILABLE`, original source metadata stays published; one connector failing does not stop others.

## UJ-10 Read the mission, privacy notice and terms

**Entry:** Mission, Privacy and Terms in the footer of every page; the account page links to Privacy.

1. **Mission** (`/mission`): why Helper2.ai exists, what it promises, how it marks AI-written text and where the labels sit on screen, who is behind it, how the guide answers and the rules it never breaks.
2. **Privacy** (`/privacy`): the contact, what is processed and why, the legal basis, cookies and browser storage, who receives data, how long it is kept, deleting the account, the user's rights.
3. **Terms** (`/terms`): the service, no guarantee on results, third-party content, acceptable use, liability, law; a link to Privacy.

**Exit:** GitHub issues (Mission, new tab), the contact e-mail, the box in the header, the logo.

## UJ-11 See what is new (home boards)

**Entry:** the home page, under the box and the examples.

1. **News**: what came into the Helper2 index in the last 7 days, by the date each source states (Published, Updated), or First seen when no source states one. Tools and research only.
2. **Events**: AI events in the next three months, soonest first, with the days, the kind, the place or online, and how soon ("in 13 days", "On now").
3. Each row: the date, the type tag, the source or the place, the status pill, and the name. The name opens the resource page (UJ-4).
4. A board with more rows than fit moves up one row every 4 seconds. Hover, keyboard focus, a tap or the Pause button stop it. With reduced motion it never moves by itself. A screen reader reads a normal list.

**Exit:** a row's name to its resource page, the box, the examples.

| State | Behavior |
|---|---|
| Loading | "Loading…" and skeleton rows. |
| Empty | The board says there is nothing in its window ("Nothing with a stated publication date in the last 7 days."). The box above is the next step. |
| Error | An alert under the board's heading with Try again; focus stays on the board's heading. |

## Success criteria (product level)

| ID | Criterion | Verified by |
|---|---|---|
| SC-1 | One query returns aggregated results from every enabled source that responded within its timeout | Today: API integration tests; UI component tests (Vitest + Testing Library, API mocked) for results, partial banner and empty/all-failed states. [Roadmap](roadmap.md): E2E journey (not yet automated) |
| SC-2 | Every configured source appears in the status list with a truthful outcome | Today: orchestrator unit tests; UI component tests for the source tiles under the results (every outcome in words). [Roadmap](roadmap.md): E2E journey (not yet automated) |
| SC-3 | Asking the guide never loses query/filters | Today: UI component tests: the URL keeps `q`, `ask` and filters; closing the answer keeps the search; back/forward restore the box; retry and replacing a running question. [Roadmap](roadmap.md): E2E journey (not yet automated) |
| SC-4 | No invented dates/popularity; unknowns labeled | domain tests + UI tests |
| SC-5 | Every published AI-extracted fact links to evidence; unsupported → unverified/review | enrichment validator tests |
| SC-6 | Search works with cache down, LLM down, one or more sources down | resilience tests |
| SC-7 | Keyboard-only and screen-reader users can complete UJ-1…UJ-6 | axe smoke tests in jsdom (color contrast excluded), focus and live-region component tests + manual checklist |
