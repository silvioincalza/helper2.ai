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

1. User lands on the home page: one headline says what the site is for, the search box sits under it, and the New this week and Coming up lists show at first sight; what helper2 does follows below them.
2. There is one box and no scope to choose: the same search covers tools, models, MCP servers, libraries, papers and news.
   Under the box, **Try:** shows four examples, picked at random on each visit from a pool: two for people who use AI
   ("Take meeting notes"), two for people who build with it ("Vector database"). An example searches its words with
   its audience path (`path=USE_AI` or `BUILD_WITH_AI`), which changes the ranking only. It does not ask the guide, so
   it spends no daily AI answer. The results page shows that path as chosen under **Best for**. New words in the box
   drop the path. Every example in the pool has a clearly fitting
   result in the top 3. The pool comes from the server (`GET /home/examples`); when it fails, takes more than 800 ms,
   or has fewer than two examples for a path, the page uses its built-in list. While it waits, the row keeps its
   height and shows nothing to click, so the page does not jump. An example a model drafted and a person checked has
   a small "AI" mark: "Drafted with AI, checked by a person".
3. Types a name ("ollama"), keywords ("mcp postgres"), an intent ("I want to summarise PDFs"), or a use case ("traduzione").
4. On the home page, Enter (or the button) opens the results on `/search`; focus moves to the results. There, results follow typing when it pauses (or on Enter). The first search shows skeleton rows and one "Searching the listed sources…" line; a later search keeps the previous results on screen, dimmed, until the new ones arrive.
5. Results arrive in one response, once every source has answered or hit its timeout. They load on demand: until the results view arrives, skeleton rows of the list's shape hold its place under the box, so nothing moves. One column, no preview: the heading says **All results** over `$ helper2 search · 104` (the total from the API), with Filters, the sort and List / Timeline next to it, then the type tabs (All, the four types with most results, More). Each row shows the type, host and date ("updated Oct 2026", or "first seen" when no source states one), the title, the description and the sources that list it. Nothing is selected, also on a phone. The title opens the resource page; a ctrl or cmd click opens it in a new tab. Back returns to the same place in the list. Under the results, a line states e.g. "4 of 5 sources answered" and a tile per source shows its outcome, response time or cache age.
6. Under the type tabs, **Best for** offers three chips: **All**, **Apps I can use** (`path=USE_AI`) and **Build
   with AI** (`path=BUILD_WITH_AI`). A chip searches again with that path: it changes what ranks first and never
   hides a result. The path is in the URL, so Back returns to the previous choice. New words in the box go back to
   **All**: the choice was for the old words.

**Key decisions:** whether to refine, or to ask the guide. **Success:** relevant result in the first page; user understands where results came from.

| Variant | Behavior |
|---|---|
| Success | Aggregated, deduplicated results; every configured source listed with outcome. |
| Empty | "No results from the N sources searched" + the list of sources + suggestions: remove filters, ask the guide, try English keywords. Never "nothing exists". |
| Partial | One quiet line under the heading, no banner: "2 of 5 sources did not answer · Which?". **Which?** lists those sources and why, in words: took too long, returned an error, paused after a rate limit, paused after repeated errors, busy. **Try again** shows only when a retry can help (an error, a timeout, a busy source); never while a source is paused or its circuit is open. The line is not announced to screen readers: it is read with the heading. Coverage marked `partial`. A source that is paused or failing counts as queried; a source left out on purpose (disabled, no web credits left) does not, and its tile says why. This line and the one under the results use the same two numbers. |
| Stale cache | Source failed live but a recent cached response exists: results shown, badge "cached 14 min ago — live check failed". |
| All failed / API down | A prominent error state: "No source responded", not an empty result; no fake results. It offers Try again, except when every source is paused (a retry cannot help then), and a link to the Sources page. |
| Rate-limited | Source marked RATE_LIMITED (not hidden), others continue. |
| Invalid input | A query longer than 200 characters is cut to 200 in the URL and the request (the guide takes 3–500); an empty box plus Enter returns to the empty home page. The **Search** button is never disabled: pressed with an empty box, it puts the cursor in the box and says "Type what you need first." under it (read as the box's description and as a polite status), and searches nothing. The API answers an invalid `q` with 400 problem+json. |

## UJ-2 Ask the guide without losing the search

1. With a query and filters on the page, the user clicks **Ask the guide** next to **Search** (any length; an icon button on a phone) or presses Shift+Enter. Enter and **Search** only search, also for a long question: the guide runs only when the user chooses it, because each answer costs model calls.
2. The results show at once; the picks bar above them says the guide is reading. Query, filters, sort and page persist (they live in the URL: `q`, `ask`, filters).
3. The answer arrives: the picks show as chips in the bar; a chip jumps to its row. The rows keep the search order, so nothing moves: a picked row shows **Guide pick #1**, the guide's reason (labelled AI-written) and its quote, highlighted in the source's own words with "Checked word for word in the source". The row opens the resource page, which says **Why the guide picked it**: the reason, whether a second model call checked it, and the requirements checked against the source.
4. Nothing fits: the bar says why in one line and offers **Ask without “…”** for each requirement the guide read (when it is short enough to keep in the URL), and **Rephrase**. **How the guide decided** stays folded under the bar: the steps, and how the guide read the question.
5. Back/forward and shared links reproduce the same state. A shared link that asks the guide works on the first visit too: when the browser has no CSRF cookie yet, the page gets it and sends the question once more.

**AI answers left:** while the button says **Ask the guide**, a quiet line under the box says how many AI answers are left today, e.g. "2 of 3 AI answers left today". The numbers come from `limits.guideQuestions` on `/me`; the page reads `/me` again after each answer. The limit is per address, so a signed-in user sees it too. With none left the line says so in words, and when the guide shows search results without AI picks again (the next 00:00 UTC, in the user's own clock). The button still works and Enter still searches. No line when the guide has no model, or for a test environment's high ceiling while answers are left.

**Success:** zero re-typing; URL fully describes the state. A new question while one is being answered replaces it.
Changing the words in the box searches again and drops the old question with its picks: the picks answered that question, not the new words. Shift+Enter or **Ask the guide** asks again.
**Failure case:** the guide fails or is unavailable, or its stream stalls for 45 s or ends without an answer → the search results stay, the answer area says what went wrong and offers **Try again**.

## UJ-3 Refine: filter and sort

1. User opens **Filters**: in place under the heading on a wide screen, a drawer over the page on a phone (up to 720px) with **Close** and **Show N results**; Escape and Close give focus back to Filters. The button says how many are on ("Filters · 2 on"). The type tabs choose one type. The use cases the search detected sit under the tabs as one-tap chips. Filters: type, audience, use case, tech level, source, license, pricing, platform, open-source, updated-after.
2. Each facet shows counts and an **Unknown** bucket for results where the source did not say.
3. Sort: Relevance (default), Newest (publication date), Recently updated. Results with unknown dates sort last and are labeled "date unknown".
4. **Timeline** shows the same results on a time axis, `$ helper2 search --timeline · N`: one month beside a line of dots, newest first, by the date each item was created as its source states it. Items with no stated creation date sit in their own group at the end; the date Helper2 found them is never used. It pages like the list.

**Key decision:** include or exclude unknowns. Selecting a value excludes unknowns; selecting "Unknown" includes them explicitly.
**Failure case:** filters produce zero results → empty state lists the active filters with one-click removal.

## UJ-4 Evaluate a result (row → resource page)

1. A result row shows: type, host, a date (updated, created, or first seen by Helper2), the title, the description (labelled when AI wrote it), a guide pick with its reason and quote, and the sources that list it.
2. The row opens the resource page, one column: **← Back to results** (back in history, so the list keeps its place; "Back to search" when opened directly), the type, host and who lists it, the title, one primary button **Open on host ↗**, Save, and **Set it up** only for things you install or run. Then **Why the guide picked it** (only when opened from a pick), **From the source** (the checked sentence highlighted), **At a glance** (Use and Build facts, "Not stated" where no source says), **Listed by**, the three dates, then metadata per field with origin and verification, evidence, relationships (verified vs unverified candidates kept apart), the optional AI summary with citations per claim, and source records (provenance).
3. User follows "Open original" (new tab, `noopener noreferrer`), hostname visible before clicking.

**Success:** user can answer "who says so, and when?" for every displayed fact.
**Failure cases:** sources disagree → "Sources disagree on license: MIT (GitHub) vs Apache-2.0 (npm)" — never silently resolved; resource not yet indexed → 404 with link back to search.

## UJ-5 Report wrong or outdated information

1. On a detail page, "Report a problem" → kind (wrong / outdated / broken link / other), optional field, message.
2. Submit → 202 "Thanks — a human will review it". Report enters the review queue; nothing on the page changes automatically.

**Failure cases:** validation error inline; rate-limited → friendly message; network error → retry with text preserved.

## UJ-6 Save resources

1. Anonymous user saves a resource with **Save** on its page → stored in `localStorage`; visible on **Saved**.
2. If storage is unavailable (private mode), the toggle explains saving is unavailable in this browser.
3. Signed out, the Saved page says "Saved in this browser only." and offers sign-in. After sign-in (UJ-7) the browser's saved items move to the account once and are removed from the browser; from then on they follow the account across devices. If the merge fails, the browser's items stay on screen with **Try again**.

## UJ-7 Sign in

Anyone can sign in, to keep saved items on the account and get a larger daily quota. Nothing needs an account: search, the guide and saving in the browser work signed out. Administrators use the same sign-in on the back office host ([ADR 0023](adr/edge/0023-identity-and-admin.md), [ADR 0035](adr/edge/0035-admin-host.md)).

1. Signed out, the header shows **Sign in** on every page (nothing when no provider is configured). It opens the sign-in page (`/signin`): why to sign in (saved items and settings on every device), then **Continue with GitHub**, one button per configured provider. The Saved page offers the provider buttons directly. Opened directly, the page returns to Home after sign-in; signed in, it links to the account; on a site with no provider it says sign-in is not offered here.
2. The page the user was on is kept in this tab. After the provider, the user comes back to that page, signed in; saved items merge (UJ-6).
3. Signed in, the header shows the display name, which opens the account page, and **Sign out**. Sign-out returns to anonymous mode; search keeps working.

**Failure case:** sign-in fails or the provider is down → the user lands back with `?signin=failed`, and the page says "Sign-in did not work. Please try again." with a link to the sign-in page; anonymous use is unaffected.

**Delete the account.** The signed-in name in the header opens the account page (`/account`): who is signed in, the AI answers and web searches left today with their reset time (from GET /me; a count the server does not give is not shown), the setting **Radar: keep moving**, and **Delete my account**. A dialog asks to confirm, with focus on Cancel; Escape closes it and focus goes back to the button. On confirm the account and its saved items are deleted and the page reloads, signed out. If the deletion fails, an alert says so and the account stays. While the page asks who is signed in it says "Loading…". Signed out, the page says there is no account to delete, links to Privacy and, when the site has a provider, to the sign-in page.

## UJ-8 Understand coverage and sources

1. **Sources** page lists every configured source: mode (LIVE, INDEXED, CURATED, DEMO), what it covers, homepage, terms, attribution, last outcome and check time. A called source also shows its response time ("No calls yet" on a live source before its first call). A source whose circuit is open says "Paused after repeated failures, since HH:MM:SS UTC. It tries again on its own."; half open, "Trying again". The metered web source shows today's web credits for all visitors ("312 of 1,000 (all visitors)", "312 (no daily cap)" or "Not available right now"). The summary counts the last outcome only: a paused source is not counted as limited. One line under the list says response time and pauses come from the server that answered the page. Back on the tab after a minute or more, the page asks again and keeps the list on screen meanwhile; if that fails, it keeps the old list and says "Could not refresh; showing the list from HH:MM UTC".
2. Footer on every page: "Helper2.ai searches the sources listed here — not the whole web."

## UJ-9 Admin: keep the catalog honest

**Entry:** the admin host, `/admin` (only over the VPN, [ADR 0035](adr/edge/0035-admin-host.md)).

1. Admin signs in (role ADMIN from an allow-list), opens **Admin**. A sub-navigation links the parts: **Overview**, **Review**, **Scout runs**, **Examples**, **Audit**. Each part has its own address.
2. **Overview** (the landing page, [ADR 0045](adr/edge/0045-back-office.md)): what needs a person now. How many review items wait, per kind; each count is a link to the queue filtered by that kind (`/admin/review?kind=USER_REPORT`). The enrichment backlog, the failed Scout runs of the last 24 hours (a link to the runs), today's model budgets ("120 of 500", "Off" at 0, "Unknown" when the shared counter cannot answer), web credits today, the last Scout runs, and a link to Grafana for trends. How many home examples are hidden now: per check (almost, in the top 10; not in the top 10; waiting for the check), each a link to the Examples page filtered by that check (`/admin/examples?outcome=FAIL`), and how many are paused. "Shown now" says how many each path has; a path with fewer than 2 gets the Examples page's warning, with a link to the examples. "Counted at" says when; Refresh asks again (the server counts at most once a minute).
3. **Review**: enrichment outputs that failed validation or contradict a source, unverified relationship candidates, user reports, course candidates. Filters by status and by kind; filters and page live in the address, so Back works. Accepts or rejects each, with an optional note.
4. **Scout runs**: sees run history with items seen/changed and failures. **Run all now** starts every enabled collector; **Run now** on a source's newest run starts only that source. The listing follows a started run until it ends. One run at a time: while a start is pending or a run is running, every start button waits, and the page says a run is in progress.
5. **Examples** ([ADR 0049](adr/search/0049-home-examples.md)): the example searches the home page picks from. One line
   says how many are shown now for each kind (people who use AI, builders); a warning when a kind has fewer than 2.
   **Add an example**: the label on the chip, what it searches, for whom, and 1 to 3 expected results found with a
   catalog search. Each row says its check in words: "Shown · expected result at #2", "Hidden · best expected result
   at #7", "Hidden · no expected result in the top 10", "Waiting for the check". **Edit** loads it into the form;
   **Pause** / **Activate**; **Remove** asks first, with focus on Cancel. The check filter lives in the address
   (`/admin/examples?outcome=FAIL`). A new or changed search waits for the check, up to 5 minutes.
   **Draft examples with AI** asks a model for examples for well-known resources that no example expects yet: "3 drafts
   wait in the Review queue" with a link to them (`/admin/review?kind=EXAMPLE_CANDIDATE`), or why there is none (drafts
   off, today's runs used, the model cannot answer). In the queue a draft shows its label, what it searches, for whom
   and "Drafted with AI (model)"; **Accept** adds it as an example, which then waits for the check.
6. **Audit**: what administrators did, newest first: when, the action, the target, the outcome, who.

Each item is a row: the kind, the date and the resource in the meta line, the reason and the details, a note field, then **Accept** and **Reject**. A closed item, and each run, shows its status in words in a pill. One action runs at a time; the other buttons wait.

| State | Behavior |
|---|---|
| Loading | "Loading…" in each part. On Refresh the overview keeps showing the last counts. |
| Empty | "Nothing in this queue." / "No runs yet." / "No entries yet." |
| Error | "Could not load the overview." (or the review queue, the Scout runs, or the audit trail) with Try again; focus stays on the part's heading. A reload of the Scout runs that fails keeps the last list and says so, with Try again. An action that fails says so above the queue or the runs; a start refused because a run holds the lock says a run is in progress. |
| Already decided | Someone else decided the item first (or a newer enrichment run replaced it): "This item was already decided." The list reloads and shows its current status. |
| Example refused | The server's rule in words: "Not saved: query: no web addresses." A duplicate label or query: "Another example already has this label or this query." Someone changed the example first: the list reloads, and the change is made again. |
| Unknown filter in the address | Falls back to open items of every kind, first page. |
| Not an admin | A signed-in user without the role sees that an administrator account is needed, with a link to the home page. No admin endpoint is called. |

**Exit:** any link back to the public site, or the next part in the sub-navigation.

**Failure cases:** LLM provider unavailable → runs complete with `LLM_UNAVAILABLE`, original source metadata stays published; one connector failing does not stop others.

## UJ-10 See what is new (home lists)

**Entry:** the home page, right under the box and the examples; or **News** or **Events** in the header (`/#news`, `/#events`).

1. **New this week**: what came into the Helper2 index in the last 7 days, by the date each source states (Published, Updated), or First seen when no source states one. Tools and research only.
2. **Coming up**: AI events in the next three months, soonest first, only those whose source states the dates. Under the date it says how soon ("Today", "In 2 weeks"); an event that started before today says "on now" and "until Nov 8" (only "on now" when the source states no last day).
3. On a wide screen the two lists are two tables side by side, like `ls -l`: Date · Type · Title · Source and When · Type · Event · Where. Below 920px they stack; below 768px they become one panel with two tabs, `> new this week 38` and `coming up 12`, with one log row per item. The header links (`/#news`, `/#events`) open their tab.
4. A row is its title, never a description (no AI-written text). The title opens the resource page (UJ-4). A date shows its year only when it is not the current year.
5. A list shows its first six rows. `$ helper2 news --all · show all 24` opens the rest in place; **show fewer** closes them. The heading shows the API's total. When the API counts more than it sends (it sends at most 60), the heading still shows the total and **show all** counts only the rows it opens. Nothing moves on its own.

**Exit:** a row's name to its resource page, the box, the examples.

| State | Behavior |
|---|---|
| Loading | Skeleton rows. |
| Empty | The list says there is nothing in its window ("Nothing with a stated publication date in the last 7 days."). The box above is the next step. |
| Error | An alert under the list's heading with Try again. |

## Success criteria (product level)

| ID | Criterion | Verified by |
|---|---|---|
| SC-1 | One query returns aggregated results from every enabled source that responded within its timeout | Today: API integration tests; UI component tests (Vitest + Testing Library, API mocked) for results, the partial line and empty/all-failed states. [Roadmap](roadmap.md): E2E journey (not yet automated) |
| SC-2 | Every configured source appears in the status list with a truthful outcome | Today: orchestrator unit tests; UI component tests for the source status panel (every outcome in words). [Roadmap](roadmap.md): E2E journey (not yet automated) |
| SC-3 | Asking the guide never loses query/filters | Today: UI component tests: the URL keeps `q`, `ask` and filters; closing the answer keeps the search; back/forward restore the box; retry and replacing a running question. [Roadmap](roadmap.md): E2E journey (not yet automated) |
| SC-4 | No invented dates/popularity; unknowns labeled | domain tests + UI tests |
| SC-5 | Every published AI-extracted fact links to evidence; unsupported → unverified/review | enrichment validator tests |
| SC-6 | Search works with cache down, LLM down, one or more sources down | resilience tests |
| SC-7 | Keyboard-only and screen-reader users can complete UJ-1…UJ-6 | axe smoke tests in jsdom (color contrast excluded), focus and live-region component tests + manual checklist |
