# helper2.ai — user journeys

The 5 personas and the 13 journeys helper2.ai is built and tested against, as they work now. Every UI change starts
from them. Each journey names its states (empty, loading, partial, error, success) and ends with the features and
stories that build it. The success criteria at the end say which tests verify each journey. The decisions are in
[the ADRs](adr/README.md).

## Personas

| Persona | Looks for | Goal | Vocabulary | Main risk |
|---|---|---|---|---|
| **Giulia** — marketing manager, non-technical, Italian speaker | Everyday tools | Find a trustworthy tool to draft and translate newsletters | "scrivere", "tradurre", "gratis?" | Overwhelmed by jargon; trusts a stale or wrong listing |
| **Sam** — student / knowledge worker | Everyday tools | Find something to summarize research PDFs | "summarize papers", "free" | Picks a tool that no longer exists |
| **Dev** — backend engineer | Developer resources | Find an MCP server for Postgres and a model that runs locally | "mcp postgres", "llama gguf", "license" | Integrates abandoned or non-OSS code |
| **Ava** — AI builder / agent developer | Developer resources | Find agent skills, prompts, and libraries that work with a given framework | "skill", "langchain", "compatible with" | Trusts an AI-invented "integrates with" claim |
| **Ops/Admin** — Helper2 maintainer | — | Keep data honest: review queue, scout runs, source health | — | Silent connector failure; hallucinated metadata published |

## UJ-1 Search once (one box)

1. User lands on the home page: one headline says what the site is for, the search box sits under it, and the New in the catalog and Coming up lists show at first sight; what helper2.ai does follows below them. With the keyboard, Tab goes from the header to the search box, then to the examples, the radar dots and the lists. A focused item is never under the header or the pinned box.
2. There is one box and no scope to choose: the same search covers tools, models, MCP servers, libraries, papers and news.
   Under the box, **Try:** shows four examples, picked at random on each visit from a pool: two for people who use AI
   ("Take meeting notes"), two for people who build with it ("Vector database"). An example searches its words with
   its audience path (`path=USE_AI` or `BUILD_WITH_AI`), which changes the ranking only. It does not ask the guide, so
   it spends no daily AI answer. The results page shows that path as chosen under **Best for**. New words in the box
   drop the path. Every example in the pool has a clearly fitting
   result in the top 3. The pool comes from the server (`GET /home/examples`); when it fails, takes more than 800 ms,
   or has fewer than two examples for a path, the page uses its built-in list. While it waits, the row keeps its
   height and shows nothing to click, so the page does not jump. An example a model drafted and a person checked has
   an "AI draft" label; its tooltip says "Drafted with AI, checked by a person".
3. Types a name ("ollama"), keywords ("mcp postgres"), an intent ("I want to summarise PDFs"), or a use case ("traduzione").
4. On the home page, Enter (or the button) opens the results on `/search`; focus moves to the results. There, results follow typing when it pauses (or on Enter). The first search shows skeleton rows and one "Searching the listed sources…" line; a later search keeps the previous results on screen, at full contrast, under the same "Searching the listed sources…" line until the new ones arrive. When a search is done, the result count ("38 results") is said once, politely, to a screen reader; a search with no result says its own message instead.
5. Results arrive in one response, once every source has answered or hit its timeout. They load on demand: until the results view arrives, skeleton rows of the list's shape hold its place under the box, so nothing moves. One column, no preview: the heading says **Results** over `$ helper2 search · 104` (the total from the API), with Filters, the sort and List / Timeline next to it, then the type tabs (All, the four types with most results, More). On a phone (up to 520px) List / Timeline, Filters and the sort share one row: the heading and the words "Sort by" are not shown, a screen reader still gets both, and the count is on the All tab. Each row shows the type, host and date ("updated Oct 2026", or "first seen" when no source states one), the title, the description and the sources that list it. A row names each source once: our catalog searched by words and by meaning is one name, "Helper2.ai catalog"; the tiles under the list keep the two apart. The description is plain text: Markdown code fences and backticks from a source are not shown. Nothing is selected, also on a phone. The title opens the resource page; a ctrl or cmd click opens it in a new tab. The host in the row is a link that opens the resource itself in a new tab (`noopener noreferrer`), so one click from the list reaches the tool. **Save** sits next to the title and keeps the result without leaving the list. Back returns to the same place in the list. Under the list, **Show more** adds the next page in place: focus moves to its first row and "20 more results" is said once, politely. The page is in the URL (`page`), so Back and a reload come back to the same rows. Under it, a line states e.g. "4 of 5 sources answered" and a tile per source shows its outcome, response time or cache age.
6. Under the type tabs, **Best for** offers three chips: **Anyone**, **Apps I can use** (`path=USE_AI`) and **Build
   with AI** (`path=BUILD_WITH_AI`). A chip searches again with that path: it changes what ranks first and never
   hides a result. The path is in the URL, so Back returns to the previous choice. New words in the box go back to
   **All**: the choice was for the old words.

**Key decisions:** whether to refine, or to ask the guide. **Success:** relevant result in the first page; user understands where results came from.

| Variant | Behavior |
|---|---|
| Loading | The first search shows skeleton rows and one "Searching the listed sources…" line; a later search keeps the previous results on screen, at full contrast, under the same line until the new ones arrive. |
| Success | Aggregated, deduplicated results; every configured source listed with outcome. |
| Empty | "No results from the sources searched" + the list of sources + suggestions: remove filters, ask the guide, "Try different or fewer words." Never "nothing exists". |
| Partial | One quiet line under the heading, no banner: "2 of 5 sources did not answer · Which?". On a phone (up to 520px) it is shorter, "2 sources did not answer · Which? · Try again", so it stays on one line; up to 359px Which? and Try again go together on the line below. **Which?** lists those sources and why, in words: took too long, returned an error, paused after a rate limit, paused after repeated errors, busy. **Try again** shows only when a retry can help (an error, a timeout, a busy source); never while a source is paused or its circuit is open. The line is not announced to screen readers: it is read with the heading. Coverage marked `partial`. A source that is paused or failing counts as queried; a source left out on purpose (disabled, no web credits left) does not, and its tile says why. This line and the one under the results use the same two numbers. |
| Stale cache | Source failed live but a recent cached response exists: results shown, badge "cached 14 min ago — live check failed". |
| Found only in a list | A result whose own text does not match but an awesome list's words around it do ([ADR 0023](adr/search/0023-catalog-full-text-index.md)) ranks below direct matches and shows one small muted line under its text: "Found in owner/awesome-llm under Tools > Vector databases". No AI label: it is the list's own words. A list itself can be a result (type Article), found by its headings. |
| All failed / API down | A prominent error state: "No source responded", not an empty result; no fake results. It offers Try again, except when every source is paused (a retry cannot help then), and a link to the Sources page. |
| Rate-limited | Source marked RATE_LIMITED (not hidden), others continue. |
| Invalid input | A query longer than 200 characters is cut to 200 in the URL and the request (the guide takes 3–500); an empty box plus Enter returns to the empty home page. The **Search** button is never disabled: pressed with an empty box, it puts the cursor in the box and says "Type what you need first." under it (read as the box's description and as a polite status), and searches nothing. The API answers an invalid `q` with 400 problem+json. |

**Built by:** features [F7.1](https://github.com/silvioincalza/helper2.ai/issues/87), [F3.1](https://github.com/silvioincalza/helper2.ai/issues/47), [F3.4](https://github.com/silvioincalza/helper2.ai/issues/50), [F3.7](https://github.com/silvioincalza/helper2.ai/issues/53), [F3.15](https://github.com/silvioincalza/helper2.ai/issues/61); stories [S7.1.1](https://github.com/silvioincalza/helper2.ai/issues/310), [S3.1.1](https://github.com/silvioincalza/helper2.ai/issues/222), [S3.1.2](https://github.com/silvioincalza/helper2.ai/issues/223), [S3.1.3](https://github.com/silvioincalza/helper2.ai/issues/224), [S3.1.5](https://github.com/silvioincalza/helper2.ai/issues/226), [S3.4.2](https://github.com/silvioincalza/helper2.ai/issues/233), [S3.7.1](https://github.com/silvioincalza/helper2.ai/issues/243), [S3.15.1](https://github.com/silvioincalza/helper2.ai/issues/257).

## UJ-2 Ask the guide without losing the search

1. With a query and filters on the page, the user clicks **Ask the guide** next to **Search** (any length; the button shows its icon and its words, and says **Ask** on a phone, where Search and Ask sit side by side under the box) or presses Shift+Enter. Enter and **Search** only search, also for a long question: the guide runs only when the user chooses it, because each answer costs model calls.
2. The results show at once; the picks bar above them says the guide is reading. Query, filters, sort and page persist (they live in the URL: `q`, `ask`, filters).
3. The answer arrives: the picks show as a list in the bar, one under the other, each with its reason (labelled AI-written), on a phone too. A pick jumps to its row; a pick that is not in the list opens its resource page. A comparison ("X vs Y") or a "what it is" answer shows open under the bar: the table or the sentences, each with its quote. On a phone (up to 520px) the comparison is one card per aspect, with each tool and its answer under the other, so nothing scrolls sideways. The rows keep the search order, so nothing moves: a picked row shows **Guide pick #1**, the guide's reason (labelled AI-written) and its quote, highlighted in the source's own words with "Checked word for word in the source". The row opens the resource page, which says **Why the guide picked it**: the reason, whether a second model call checked it, and the requirements checked against the source.
4. Nothing fits: the bar says why in one line and offers **Ask without “…”** for each requirement the guide read (when it is short enough to keep in the URL), and **Rephrase**. **How the guide decided** stays folded under the bar: the steps, and how the guide read the question. In the results, the key **g** moves focus to **Ask the guide** and asks nothing; Enter then asks.
5. Back/forward and shared links reproduce the same state. A shared link that asks the guide works on the first visit too: when the browser has no CSRF cookie yet, the page gets it and sends the question once more.

**AI answers left:** once there are words in the box, a quiet line under it says how many AI answers are left today, e.g. "2 of 3 AI answers left today". The numbers come from `limits.guideQuestions` on `/me`; the page reads `/me` again after each answer. The limit is per address, so a signed-in user sees it too. With none left the line says so in words, and when the guide shows search results without AI picks again (the next 00:00 UTC, in the user's own clock). The button still works and Enter still searches. No line when the guide has no model, or for a test environment's high ceiling while answers are left.

**Success:** zero re-typing; URL fully describes the state. A new question while one is being answered replaces it.
Changing the words in the box searches again and drops the old question with its picks: the picks answered that question, not the new words. Shift+Enter or **Ask the guide** asks again.
**Failure case:** the guide fails or is unavailable, or its stream stalls for 45 s or ends without an answer → the search results stay, the answer area says what went wrong and offers **Try again**.

| State | Behavior |
|---|---|
| Loading | The results show at once; the picks bar above them says the guide is reading. |
| Success | The picks in the bar, each with its reason (labelled AI-written) and its quote, checked word for word; a comparison or a "what it is" answer shows open. |
| Empty | Nothing fits: the bar says why in one line and offers **Ask without “…”** and **Rephrase**. |
| Partial | Not specified. |
| Error | The guide fails, is unavailable, or its stream stalls for 45 s or ends without an answer: the search results stay, the answer area says what went wrong and offers **Try again**. |

**Built by:** features [F4.1](https://github.com/silvioincalza/helper2.ai/issues/64), [F4.2](https://github.com/silvioincalza/helper2.ai/issues/65), [F4.3](https://github.com/silvioincalza/helper2.ai/issues/66), [F7.1](https://github.com/silvioincalza/helper2.ai/issues/87); stories [S4.1.1](https://github.com/silvioincalza/helper2.ai/issues/260), [S4.1.2](https://github.com/silvioincalza/helper2.ai/issues/261), [S4.1.3](https://github.com/silvioincalza/helper2.ai/issues/262), [S4.1.4](https://github.com/silvioincalza/helper2.ai/issues/263), [S4.1.5](https://github.com/silvioincalza/helper2.ai/issues/264), [S4.2.1](https://github.com/silvioincalza/helper2.ai/issues/265), [S4.2.3](https://github.com/silvioincalza/helper2.ai/issues/267), [S4.3.1](https://github.com/silvioincalza/helper2.ai/issues/269), [S4.3.3](https://github.com/silvioincalza/helper2.ai/issues/271), [S7.1.2](https://github.com/silvioincalza/helper2.ai/issues/311).

## UJ-3 Refine: filter and sort

1. User opens **Filters**: in place under the heading on a wide screen, a drawer over the page on a phone (up to 720px) with **Close** and **Show N results**; Escape and Close give focus back to Filters. The button says how many are on ("Filters · 2 on"). The type tabs choose one type. The use cases the search detected sit under the tabs as one-tap chips. On a phone each row of chips is one line that scrolls sideways: the side with more chips fades out, and Tab brings each chip into view. Filters: category, type, use case, tech level, source, license, pricing, platform, open-source, updated-after.
2. Each facet shows counts and a **Not stated** bucket for results where the source did not say. **Category** (ADR 0026) groups the categories by family (Everyday apps, Developers, ...), lists only the ones with results or already chosen, and ends with **No category yet**. It says when the names are loading or could not be loaded (with **Try again**). A chosen category is a chip and is in the URL (`?category=coding`); asking the guide on that page picks only in those categories. A resource page lists its categories, each a link to a search filtered by it.
3. Sort: Relevance (default), Newest (publication date), Recently updated. Results with unknown dates sort last and are labeled "date unknown".
4. **Timeline** shows the same results on a time axis, `$ helper2 search --timeline · N`: one month beside a line of dots, newest first, by the date each item was created as its source states it. Items with no stated creation date sit in one folded group at the end, "Date unknown · N · Show them" (a button with `aria-expanded`); the date Helper2 found them is never used. **Show more** works as in the list.

**Key decision:** include or exclude unknowns. Selecting a value excludes unknowns; selecting "Not stated" includes them explicitly.
**Failure case:** filters produce zero results → empty state lists the active filters with one-click removal.

| State | Behavior |
|---|---|
| Loading | Category says that the names are loading. |
| Success | Each facet with its counts and a **Not stated** bucket; the list in the chosen order, unknown dates last. |
| Empty | The filters produce zero results: the empty state lists the active filters with one-click removal. |
| Partial | As in UJ-1: the line under the heading says which sources did not answer. |
| Error | The category names could not be loaded: Category says so, with **Try again**. |

**Built by:** features [F3.5](https://github.com/silvioincalza/helper2.ai/issues/51), [F3.16](https://github.com/silvioincalza/helper2.ai/issues/62); stories [S3.5.1](https://github.com/silvioincalza/helper2.ai/issues/235), [S3.5.2](https://github.com/silvioincalza/helper2.ai/issues/236), [S3.5.4](https://github.com/silvioincalza/helper2.ai/issues/238), [S3.16.1](https://github.com/silvioincalza/helper2.ai/issues/258).

## UJ-4 Evaluate a result (row → resource page)

1. A result row shows: type, host (a link to the resource itself, new tab), a date (updated, created, or first seen by Helper2), the title (a heading, so a screen reader can jump from result to result) with Save next to it, the description (labelled when AI wrote it), a guide pick with its reason and quote, and the sources that list it.
2. The row opens the resource page, one column: **← Back to results** (back in history, so the list keeps its place). Opened from another list the link names it: **← Back to news**, **← Back to events**, **← Back to saved**, **← Back** from Home; "Back to search" when opened directly. Then the title, under it type · host · the same date as the row, one primary button **Open on host ↗** (it says it opens a new tab), Save (named "Save" or "Saved" with the title, like the word it shows), and **Set it up** only for things you install or run. Under Set it up one line says how many AI setups are left today before the click, e.g. "18 of 20 AI setups left today. Steps that are already written do not count." The numbers come from `limits.setupGuides` on `/me`; the page reads `/me` again after each guide it asks for. The limit is per address, so a signed-in user sees it too. With none left the line says so in words, that written guides still open, and when new ones are back (the next 00:00 UTC, in the user's own clock); the button still opens. No line when the setup guide has no model, or for a test environment's high ceiling while guides are left. Then **Why the guide picked it** (only when opened from a pick), **From the source** (the checked sentence highlighted), **At a glance** (only the Use and Build facts that have a value, each once; a value the AI read carries its AI label; then one line for the rest: "Not stated by any source: pricing, platforms"), the three dates, the AI summary with citations per claim (only when there is one), relationships (verified vs unverified candidates kept apart), then **Where this information comes from**, folded and one click away: each source, its mode and when it was read, a link to Sources, every field with its origin ("Not stated" per field), the facts with origin, verification and evidence (field names in plain words, dates as on the rest of the page), the source records (provenance), and one line when there is no AI summary and why.
3. User follows "Open original" (new tab, `noopener noreferrer`), hostname visible before clicking.

**Success:** user can answer "who says so, and when?" for every displayed fact.
**Failure cases:** sources disagree → "Sources disagree on license: MIT (GitHub) vs Apache-2.0 (npm)" — never silently resolved; resource not yet indexed → 404 with link back to search.

| State | Behavior |
|---|---|
| Loading | Not specified. |
| Success | The user can answer "who says so, and when?" for every displayed fact. |
| Empty | A fact no source states is not shown as a fact: one line says "Not stated by any source: pricing, platforms". No AI summary: one line says so and why. |
| Partial | Sources disagree: "Sources disagree on license: MIT (GitHub) vs Apache-2.0 (npm)", never silently resolved. |
| Error | The resource is not indexed: 404 with a link back to search. |

**Built by:** features [F7.4](https://github.com/silvioincalza/helper2.ai/issues/90), [F4.4](https://github.com/silvioincalza/helper2.ai/issues/67), [F4.3](https://github.com/silvioincalza/helper2.ai/issues/66), [F3.5](https://github.com/silvioincalza/helper2.ai/issues/51); stories [S7.4.1](https://github.com/silvioincalza/helper2.ai/issues/319), [S7.4.2](https://github.com/silvioincalza/helper2.ai/issues/320), [S4.4.1](https://github.com/silvioincalza/helper2.ai/issues/274), [S4.4.2](https://github.com/silvioincalza/helper2.ai/issues/275), [S4.4.3](https://github.com/silvioincalza/helper2.ai/issues/276), [S4.4.4](https://github.com/silvioincalza/helper2.ai/issues/277), [S4.3.1](https://github.com/silvioincalza/helper2.ai/issues/269), [S3.5.3](https://github.com/silvioincalza/helper2.ai/issues/237).

## UJ-5 Report wrong or outdated information

1. On a detail page, "Report a problem" → kind (wrong / outdated / broken link / other), optional field, message (marked "required").
2. Submit → 202 "Thanks — a human will review it". Report enters the review queue; nothing on the page changes automatically.

**Failure cases:** validation error inline; rate-limited → friendly message; network error → retry with text preserved.

| State | Behavior |
|---|---|
| Loading | Not specified. |
| Success | 202 "Thanks — a human will review it". The report enters the review queue; nothing on the page changes automatically. |
| Empty | Does not apply: the journey is one form. |
| Partial | Does not apply. |
| Error | A validation error shows inline; rate-limited: a friendly message; a network error: retry with the text preserved. |

**Built by:** features [F5.3](https://github.com/silvioincalza/helper2.ai/issues/75), [F6.2](https://github.com/silvioincalza/helper2.ai/issues/82); stories [S5.3.1](https://github.com/silvioincalza/helper2.ai/issues/290), [S6.2.2](https://github.com/silvioincalza/helper2.ai/issues/300).

## UJ-6 Save resources

1. Anonymous user saves a resource with **Save** on its result row or on its page → stored in `localStorage`; visible on **Saved**.
2. If storage is unavailable (private mode), the toggle explains saving is unavailable in this browser.
3. Signed out, the Saved page says "Saved in this browser only." and offers sign-in. After sign-in (UJ-7) the browser's saved items move to the account once and are removed from the browser; from then on they follow the account across devices. If the merge fails, the browser's items stay on screen with **Try again**.

| State | Behavior |
|---|---|
| Loading | Not specified. |
| Success | The resource is on **Saved**; signed in, saved items follow the account across devices. |
| Empty | Not specified. |
| Partial | The merge into the account fails: the browser's items stay on screen with **Try again**. |
| Error | Storage is unavailable (private mode): the toggle explains that saving is unavailable in this browser. |

**Built by:** features [F5.2](https://github.com/silvioincalza/helper2.ai/issues/74); stories [S5.2.1](https://github.com/silvioincalza/helper2.ai/issues/289).

## UJ-7 Sign in

Anyone can sign in, to keep saved items on the account. The daily limits are per address, the same signed in or not (a larger limit for signed-in users is open: [S5.5.1](https://github.com/silvioincalza/helper2.ai/issues/293)). Nothing needs an account: search, the guide and saving in the browser work signed out. Administrators use the same sign-in on the back office host ([ADR 0036](adr/identity/0036-identity-and-the-admin-host.md)).

1. Signed out, the header shows **Sign in** on every page (nothing when no provider is configured). It opens the sign-in page (`/signin`): why to sign in (saved items and settings on every device), then **Continue with GitHub**, one button per configured provider. The Saved page offers the provider buttons directly. Opened directly, the page returns to Home after sign-in; signed in, it links to the account; on a site with no provider it says sign-in is not offered here.
2. The page the user was on is kept in this tab. After the provider, the user comes back to that page, signed in; saved items merge (UJ-6).
3. Signed in, the header shows the display name, which opens the account page, and **Sign out**. Sign-out returns to anonymous mode; search keeps working.

**Failure case:** sign-in fails or the provider is down → the user lands back with `?signin=failed`, and the page says "Sign-in did not work. Please try again." with a link to the sign-in page; anonymous use is unaffected.

**Delete the account.** The signed-in name in the header opens the account page (`/account`): who is signed in, the AI answers, AI setups and web searches left today with their reset time (from GET /me; a count the server does not give is not shown), the setting **Home page motion: radar and lists**, and **Delete my account**. A dialog asks to confirm, with focus on Cancel; Escape closes it and focus goes back to the button. On confirm the account and its saved items are deleted and the page reloads, signed out. If the deletion fails, an alert says so and the account stays. While the page asks who is signed in it says "Loading…". Signed out, the page says there is no account to delete, links to Privacy and, when the site has a provider, to the sign-in page.

| State | Behavior |
|---|---|
| Loading | While the page asks who is signed in it says "Loading…". |
| Success | The user comes back to the page they were on, signed in; saved items merge (UJ-6). |
| Empty | No provider is configured: the header shows no **Sign in**, and the sign-in page says sign-in is not offered here. Signed out, the account page says there is no account to delete. |
| Partial | Does not apply. |
| Error | Sign-in fails or the provider is down: `?signin=failed` and "Sign-in did not work. Please try again."; anonymous use is unaffected. A deletion that fails: an alert says so and the account stays. |

**Built by:** features [F5.1](https://github.com/silvioincalza/helper2.ai/issues/73), [F5.8](https://github.com/silvioincalza/helper2.ai/issues/80); stories [S5.1.1](https://github.com/silvioincalza/helper2.ai/issues/286), [S5.1.2](https://github.com/silvioincalza/helper2.ai/issues/287), [S5.1.3](https://github.com/silvioincalza/helper2.ai/issues/288), [S5.8.1](https://github.com/silvioincalza/helper2.ai/issues/296).

## UJ-8 Understand coverage and sources

1. **Sources** page lists every configured source: mode (LIVE, INDEXED, CURATED, DEMO), what it covers, homepage, terms, attribution, last outcome and check time. A called source also shows its response time ("No calls yet" on a live source before its first call). A source whose circuit is open says "Paused after repeated failures, since HH:MM:SS UTC. It tries again on its own."; half open, "Trying again". The metered web source shows today's web credits for all visitors ("312 of 1,000 (all visitors)", "312 (no daily cap)" or "Not available right now"). The summary counts the last outcome only: a paused source is not counted as limited. One line under the list says response time and pauses come from the server that answered the page. Back on the tab after a minute or more, the page asks again and keeps the list on screen meanwhile; if that fails, it keeps the old list and says "Could not refresh; showing the list from HH:MM UTC".
2. Footer on every page: "Helper2.ai searches the sources listed here — not the whole web."

| State | Behavior |
|---|---|
| Loading | Back on the tab after a minute or more, the page asks again and keeps the list on screen meanwhile. |
| Success | Every configured source with its mode, what it covers, terms, attribution, last outcome and check time. |
| Empty | A live source before its first call says "No calls yet". |
| Partial | A source whose circuit is open says "Paused after repeated failures, since HH:MM:SS UTC. It tries again on its own."; web credits the server cannot read say "Not available right now". |
| Error | The refresh fails: the page keeps the old list and says "Could not refresh; showing the list from HH:MM UTC". |

**Built by:** features [F3.17](https://github.com/silvioincalza/helper2.ai/issues/63), [F3.6](https://github.com/silvioincalza/helper2.ai/issues/52); stories [S3.17.1](https://github.com/silvioincalza/helper2.ai/issues/259), [S3.6.2](https://github.com/silvioincalza/helper2.ai/issues/241).

## UJ-9 Admin: keep the catalog honest

**Entry:** the admin host, `/admin` (only over the VPN, [ADR 0036](adr/identity/0036-identity-and-the-admin-host.md)).

1. Admin signs in (role ADMIN from an allow-list), opens **Admin**. A sub-navigation links the parts: **Overview**, **Review**, **Scout runs**, **Enrichment**, **Examples**, **Audit**, **Settings**. Each part has its own address.
2. **Overview** (the landing page, [ADR 0038](adr/backoffice/0038-back-office-and-review.md)): what needs a person now. How many review items wait, per kind; each count is a link to the queue filtered by that kind (`/admin/review?kind=USER_REPORT`). The enrichment backlog, the failed Scout runs of the last 24 hours (a link to the runs), today's model budgets ("120 of 500", "Off" at 0, "Unknown" when the shared counter cannot answer), web credits today, the last Scout runs, and a link to Grafana for trends. How many home examples are hidden now: per check (almost, in the top 10; not in the top 10; waiting for the check), each a link to the Examples page filtered by that check (`/admin/examples?outcome=FAIL`), and how many are paused. "Shown now" says how many each path has; a path with fewer than 2 gets the Examples page's warning, with a link to the examples. "Counted at" says when; Refresh asks again (the server counts at most once a minute).
3. **Review**: enrichment outputs that failed validation or contradict a source, unverified relationship candidates, user reports, course candidates. Filters by status and by kind; filters and page live in the address, so Back works. The list is on the left, the selected item on the right (stacked on a narrow screen). The first item is selected; a click on its reason selects another. The selected item shows its details, a note field, **Accept** and **Reject**, and its **Evidence** ([ADR 0038](adr/backoffice/0038-back-office-and-review.md)):
   the run (model, prompt, outcome, when it finished, the README it also read), what the code check refused (labelled "Includes model output", since it quotes the model), and the fields the run stored, conflicts first, each with its origin in words ("Extracted by AI", "AI-estimated") and up to 5 quotes, each labelled "Source quote", with their source and date. A relationship candidate shows the relationship and its quotes. A user report or a candidate page has no run: the panel says so.
   Keys, only while focus is in the queue and never in a field: **j** / **k** next and previous item, **a** accept, **r** reject, **n** the note (Escape goes back to the list), **?** the list of keys (**Keys (?)** opens it too). A held key counts once: holding **a** accepts nothing more. After a decision the next item is selected and takes focus, and a screen reader hears "Accepted. 11 left."
   **Several at once**: each open row has a box (**x** picks the selected item). A bar under the list says "4 selected · Accept · Reject · Clear". Accept or Reject opens a dialog that repeats the count by kind ("3 Enrichment validation · 1 User report"), with focus on Cancel. Accept says that only enrichment checks with no conflict on licence, open source or pricing are accepted together; the others stay open. After the decision a line says "11 done, 1 failed (already decided)"; the items that failed stay picked. A new filter, or Clear, unpicks everything. Decisions are final.
4. **Scout runs**: sees run history with items seen/changed and failures. **Run all now** starts every enabled collector; **Run now** on a source's newest run starts only that source. The listing follows a started run until it ends. One run at a time: while a start is pending or a run is running, every start button waits, and the page says a run is in progress.
   **Enrichment**: what the Scout enriches next, in its order: the ones I moved to the top first, then never-run resources, then the ones to try again, the most popular first. Each row: the resource (a link to its page), its status in words (At the top, Not run yet, Model was unavailable, Retry after a failure), when it was found, how many runs failed, the last run, and its actions. **Move to top** puts it first until its next run (at most 50 at the top). **Skip** asks first, with an optional reason (the hint says "No personal data") and focus on the reason field; Cancel gives focus back to Skip. **Show** switches the view: waiting, **Skipped** (the latest skip first, with when and why) or **Out of retries** (its runs failed 3 times). In those two views **Requeue** puts the resource back in its own place; one out of retries gets 3 more tries. After an action a line says what happened ("“Acme MCP” is at the top. The Scout takes it in its next run, within the daily limit.") and takes focus, and the list reloads. An action changes the order only, never the daily limit. Next page and First page; the view and the page live in the address. The overview's backlog count links here. Empty: "Nothing waits for enrichment." / "No resource is skipped." / "No resource is out of retries."
5. **Examples** ([ADR 0028](adr/search/0028-home-examples.md)): the example searches the home page picks from. One line
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
7. **Settings** (read-only, [ADR 0038](adr/backoffice/0038-back-office-and-review.md)): what the app runs with now, one table per
   part (Models, AI guide, Search, Scout, Scout sources, Setup guide, Home examples). Each row: the setting in words,
   its value ("500 calls a day", "No cap", "On", "None" when no model is set) and the `H2_` variable that sets it, or
   "No variable". Secrets are never shown. A change needs a new value and a restart, except for the spending
   limits at the top ([ADR 0007](adr/foundation/0007-configuration-secrets-runtime-settings.md)): Scout enrichments per run, enrichment
   calls and gap rounds a day, the guide's model calls a day, translations a day, Live web credits a day. Each row:
   the value now with "Changed" or "Default", the default and its variable, the allowed range ("0 to 3000 calls a
   day") and one line of help. **Change** opens a number field; a value outside the range says "Enter a whole
   number from 0 to 3000." and nothing is sent. **Review changes** shows "600 calls a day → 50 calls a day", says
   when 0 turns the use off, and asks for an optional reason; focus moves to the review. **Confirm change** saves:
   "Saved: … is now 50 calls a day." **Reset to default** and **Restore** (in History, the last 10 changes with
   who, when and why) go through the same review. Someone changed the limit first: "Someone changed this limit
   first." and the row shows the current value. Every replica applies a change within about 20 seconds. The
   overview says "2 spending limits changed at runtime", a link to this page; the audit trail shows each change
   as "default 600 → 50" with its reason.
   **Known vendors and services** ([ADR 0019](adr/scout/0019-known-targets-vendors-and-services.md)): the names a relationship may point
   at with no catalog resource, one table of vendors and one of services. Each row: the name, its other names, how
   many relationships carry it, who added it and when. **Add name** takes a kind, a name and other names, one per
   line; an empty name says "Enter a name, such as Slack." and nothing is sent; a name another target holds
   says "Not saved: Open AI already means OpenAI." **Change** opens the stored kind and names and says that a
   relationship under a name taken away goes back to the review list. **Remove** first says "3 relationships
   carry it. They become unverified and go back to the review list, one item each.", then asks. Focus moves to
   the form or the question; Cancel closes it.
   A name the Scout proposes waits in **Review** as a "Known target proposal": the owner of 5 or more GitHub or
   Hugging Face resources. The card shows the name, the kind, "The owner rule: 22 GitHub or Hugging Face resources
   have this owner." and up to 5 of them. **Change the proposal** opens the kind, the name and the other names:
   `meta-llama` becomes "Meta" with `meta-llama` as another name; the card then says "Changed by an admin."
   **Accept** adds it to this list, marked "proposed by a rule". When the name is one a vendor or service of the
   same kind already has, Accept adds the other names to it instead. A name a target of another kind holds says so
   and the item stays open. **Reject** is remembered: the name is not proposed again.
   A person's account is rejected.

Each item is a row: the kind, the resource, the reason, the date and the status in words in a pill. The selected row has a saffron bar. Each run shows its status in a pill too. One action runs at a time; the other buttons wait.

| State | Behavior |
|---|---|
| Success | After a decision the next item is selected and takes focus, and a screen reader hears "Accepted. 11 left." |
| Loading | "Loading…" in each part; "Loading the evidence…" in the selected item. On Refresh the overview keeps showing the last counts. |
| Empty | "Nothing in this queue." / "No runs yet." / "No entries yet." After the last decision focus goes to the queue's heading. An item with no run: "No run or relationship behind this item." A decided run whose fields a newer run replaced: "This run has no stored fields left." |
| Error | "Could not load the overview." (or the review queue, the Scout runs, or the audit trail) with Try again; focus stays on the part's heading. "Could not load the evidence." with Try again in the selected item; the list and the decision still work. A reload of the Scout runs that fails keeps the last list and says so, with Try again. An action that fails says so above the queue or the runs; a start refused because a run holds the lock says a run is in progress. |
| Already decided | Someone else decided the item first (or a newer enrichment run replaced it): "This item was already decided." The list reloads and shows its current status. |
| Queue action refused | The resource changed meanwhile, or the top is full: "Nothing changed: 50 resources already wait at the top." (or: is already skipped, does not wait, is neither skipped nor out of retries). The message takes focus and the list reloads. |
| Partial: bulk decision partly done | "11 done, 1 failed (already decided)." or, with several reasons, "(1 already decided, 2 not allowed in bulk)". The failed items stay picked; the list reloads. A request that fails says "The action failed." and keeps the items picked. |
| Example refused | The server's rule in words: "Not saved: query: no web addresses." A duplicate label or query: "Another example already has this label or this query." Someone changed the example first: the list reloads, and the change is made again. |
| Unknown filter in the address | Falls back to open items of every kind, first page. |
| Not an admin | A signed-in user without the role sees that an administrator account is needed, with a link to the home page. No admin endpoint is called. |

**Exit:** any link back to the public site, or the next part in the sub-navigation.

**Failure cases:** LLM provider unavailable → runs complete with `LLM_UNAVAILABLE`, original source metadata stays published; one connector failing does not stop others.

**Built by:** features [F6.1](https://github.com/silvioincalza/helper2.ai/issues/81), [F6.2](https://github.com/silvioincalza/helper2.ai/issues/82), [F6.3](https://github.com/silvioincalza/helper2.ai/issues/83), [F6.4](https://github.com/silvioincalza/helper2.ai/issues/84), [F2.1](https://github.com/silvioincalza/helper2.ai/issues/31), [F2.9](https://github.com/silvioincalza/helper2.ai/issues/39), [F3.7](https://github.com/silvioincalza/helper2.ai/issues/53); stories [S6.1.1](https://github.com/silvioincalza/helper2.ai/issues/297), [S6.1.2](https://github.com/silvioincalza/helper2.ai/issues/298), [S6.2.1](https://github.com/silvioincalza/helper2.ai/issues/299), [S6.2.2](https://github.com/silvioincalza/helper2.ai/issues/300), [S6.2.3](https://github.com/silvioincalza/helper2.ai/issues/301), [S6.2.4](https://github.com/silvioincalza/helper2.ai/issues/302), [S6.2.5](https://github.com/silvioincalza/helper2.ai/issues/303), [S6.3.1](https://github.com/silvioincalza/helper2.ai/issues/305), [S6.4.1](https://github.com/silvioincalza/helper2.ai/issues/306), [S6.4.2](https://github.com/silvioincalza/helper2.ai/issues/307), [S2.1.4](https://github.com/silvioincalza/helper2.ai/issues/185), [S2.9.1](https://github.com/silvioincalza/helper2.ai/issues/209), [S2.9.2](https://github.com/silvioincalza/helper2.ai/issues/210), [S3.7.2](https://github.com/silvioincalza/helper2.ai/issues/244).

## UJ-10 See what is new (home lists)

**Entry:** the home page, right under the box and the examples; or **News** or **Events** in the header, which open their own pages (`/news`, `/events`, UJ-13).

1. **New in the catalog**: what came into our index in the last 7 days, by the date each source states (Published, Updated), or First seen when no source states one. Tools and research only.
2. **Coming up**: AI events in the next three months, soonest first, only those whose source states the dates. Under the date it says how soon ("Today", "In 2 weeks"); an event that started before today says "on now" and "until Nov 8" (only "on now" when the source states no last day). An event whose dates an AI model read on its own page (the live web events scout, ADR 0012) says "Extracted by AI" next to its date, here, on the Events page and on its resource page; its date, kind and place are marked as AI output in the HTML.
3. On a wide screen the two lists are two tables side by side, like `ls -l`: Date · Type · Title · Source and When · Type · Event · Where. Below 920px they stack; below 768px they become one panel with two tabs, `> new in the catalog 38` and `coming up 12`, with one log row per item. `/#news` and `/#events` in an address open their tab (`/#news` opens New in the catalog); the header links go to the News and Events pages. News means only stories: the News page.
4. A row is its title, never a description (no AI-written text). The title opens the resource page (UJ-4). A date shows its year only when it is not the current year.
5. A list shows its first six rows. `$ helper2 new --all · show all 24` opens the rest in place; **show fewer** closes them. The heading shows the API's total. When the API counts more than it sends (it sends at most 60), the heading still shows the total and **show all** counts only the rows it opens. A list with more rows than it shows moves like a news ticker: one row every 4 seconds. Hover, focus or a tap holds it, and on a phone a scroll of the page. It moves again when the mouse or the focus leaves, or after the tap. Under reduced motion, or with motion off on the account page, it never moves. Coming up ends with **All events ›**, to the Events page.

**Exit:** a row's name to its resource page, the box, the examples.

| State | Behavior |
|---|---|
| Loading | Skeleton rows. |
| Success | Each list shows its first six rows under the API's total. |
| Partial | The API counts more than it sends (at most 60): the heading shows the total and **show all** counts only the rows it opens. |
| Empty | The list says there is nothing in its window ("Nothing with a stated publication date in the last 7 days."). The box above is the next step. |
| Error | An alert under the list's heading with Try again. |

**Built by:** features [F7.5](https://github.com/silvioincalza/helper2.ai/issues/91), [F7.2](https://github.com/silvioincalza/helper2.ai/issues/88); stories [S7.5.1](https://github.com/silvioincalza/helper2.ai/issues/321), [S7.2.2](https://github.com/silvioincalza/helper2.ai/issues/315), [S2.2.5](https://github.com/silvioincalza/helper2.ai/issues/190).

## UJ-11 Read the mission, the privacy notice and the terms

1. The footer links to **Mission**, **Privacy** and **Terms** on every page. Each opens a text page: the title, the date of the last change (Privacy, Terms), a short lede, the contact, then the sections, with **On this page** beside the text on a wide screen and folded under the head on a phone.
2. The numbers in the text come from the server (GET /policy): how many candidates and picks the guide uses (Mission), the daily limits (Terms) and how long each store keeps data (Privacy). They are the numbers the code applies, never typed ones. Periods read as "24 hours", "7 days", "24 months".
3. The page and its numbers load together; the page waits for them at most half a second, so it usually opens with them in place.

| State | Behavior |
|---|---|
| Loading | The numbers are late: the sentences say it in words ("a daily limit", "for a limited time"), then the numbers take their place. No wrong number, no placeholder in the text. |
| Success | The page opens with the server's numbers in place. |
| Empty | Does not apply: the pages are text. |
| Partial | Numbers the server does not give (the 00:00 UTC reset, counter lifetimes, logs, backups, usage totals) stay typed in the text. |
| Error | The same sentences in words, and one line under the head: "Some limits and periods could not be loaded, so this page states them without numbers. Reload the page to see them." |

**Built by:** features [F9.1](https://github.com/silvioincalza/helper2.ai/issues/100), [F7.3](https://github.com/silvioincalza/helper2.ai/issues/89); stories [S9.1.1](https://github.com/silvioincalza/helper2.ai/issues/334), [S9.1.2](https://github.com/silvioincalza/helper2.ai/issues/335), [S7.3.2](https://github.com/silvioincalza/helper2.ai/issues/318).

## UJ-12 Support helper2, ads and the analytics question

**Entry:** "Support helper2" in the footer; the analytics bar on the first visit; the "Sponsored" box on a search page
([ADR 0042](adr/money/0042-monetisation.md)). Each part shows only while its setting is on.

1. **Give.** The Support page says what running helper2 costs and offers the ways that are on: "Sponsor on GitHub"
   (new tab) and "Give by card" (Stripe). The page says that both go to the provider's own page, and that giving
   changes nothing on helper2.
2. Stripe sends the giver back to `/support/thanks`: "Thank you", and a link back to search. No upsell.
3. **The analytics question.** A bar at the bottom: "Can we count your visit with Google Analytics?", with "Reject" and
   "Accept" the same size. Search works before an answer. The answer is kept; "Cookie settings" in the footer asks
   again. With the keyboard the bar is the first stop after "Skip to main content", and the page keeps a focused item
   above it. On a short screen (up to 480px tall) the bar sits at the top of the page and scrolls with it.
4. **Google's question** (while AdSense is on, in place of step 3): where the EU, EEA, UK or Swiss rules apply,
   Google's consent tool asks once, for ads and analytics, on the first resource page of a visit. Home, search and the
   other pages never show it. "Privacy choices" in the footer asks again from any page
   ([ADR 0042](adr/money/0042-monetisation.md)). A resource page shows one "Sponsored" AdSense box between its sections.
5. **The ad.** A search page shows one "Sponsored" box above the results, with why it is there. While the search runs,
   it shows a progress bar; the results replace the bar the moment they come, and the ad comes with them. One search,
   one ad: a filter, a sort or the timeline keeps it, new search words show a new one. No ad next to a guide answer.

**Exit:** the provider's page, the link back to search, the box.

| State | Behavior |
|---|---|
| Loading | While the search runs, the ad box shows a progress bar. |
| Success | The Support page offers the ways that are on; after a gift, `/support/thanks`. One search shows one ad, with the results. |
| Empty: nothing configured | No Support link, no bar, no ad box: the site as before. |
| Partial | Not specified. |
| Support page, no way on | "Giving is not open yet." |
| Payment cancelled at Stripe | Stripe's page; back on the Support page, no message and no nagging. |
| No ad to show | The box keeps its height and stays empty, so the results do not move. |
| Search failed, or no source could search | No ad box: the error state is all the page shows. |
| Ad or analytics blocked by the browser | Nothing breaks; search works the same. |
| AdSense on, the visit never opens a resource page | No Google dialog, and the visit is not counted in Google Analytics. |
| Still typing: the words change before their results come | No ad for those words; the box keeps the ad it had. |

**How we know it worked:** gifts and amounts from the providers' dashboards; views of `/support` and
`/support/thanks` in Google Analytics (for visitors who accepted; while AdSense is on, only in visits that opened a
resource page); p95 time to results and CLS before and after the ad
slot. If either gets worse, the slot goes.

**Built by:** features [F8.1](https://github.com/silvioincalza/helper2.ai/issues/95), [F8.2](https://github.com/silvioincalza/helper2.ai/issues/96), [F8.3](https://github.com/silvioincalza/helper2.ai/issues/97); stories [S8.1.1](https://github.com/silvioincalza/helper2.ai/issues/328), [S8.2.1](https://github.com/silvioincalza/helper2.ai/issues/329), [S8.3.1](https://github.com/silvioincalza/helper2.ai/issues/330), [S8.3.2](https://github.com/silvioincalza/helper2.ai/issues/331).

## UJ-13 Read the AI news and plan for events

Entry: **News** and **Events** in the header (in the menu on a phone), **All events ›** under Coming up on Home, or a
shared link with its filters (`/news?site=…`, `/events?kind=HACKATHON&format=ONLINE`).

1. **News** is the last week of AI news our web search found, laid out like a front page: one lead story, six under
   it in two columns, five in **Latest** beside them (so the two columns end at about the same height), then **Earlier this week**. Headlines, site and date only; a headline opens the
   resource page, which links to the story at its source. The site chips filter the page; the choice is in the
   address, so Back and a shared link show the same stories.
2. **Events** is every event in the next year whose source states its dates, grouped by month, each month with its
   count. Chips filter by kind and by online or in person, each with its count; the choices are in the address. A row
   says its day, kind, place and how soon ("in 2 days", "on now"). Days, kind and place an AI model read carry
   "Extracted by AI" and the machine-readable mark.
3. Exit: a row opens its resource page; the header goes anywhere else.

| State | Behavior |
|---|---|
| Loading | The loading lines under the heading, announced once. |
| Success | News: one lead story, six under it, five in **Latest**, then **Earlier this week**. Events: every event with stated dates in the next year, by month. |
| Partial | Not specified. |
| Empty | "No AI news in the last week." / "No events with stated dates in the next year." |
| A filter with nothing | "No story from this site in the last week." / "No event matches these filters."; the chosen chip stays, so it can be turned off. |
| Error | "Could not load the news." / "Could not load the next events.", with **Try again**. |

**Built by:** features [F7.5](https://github.com/silvioincalza/helper2.ai/issues/91), [F2.2](https://github.com/silvioincalza/helper2.ai/issues/32); stories [S7.5.2](https://github.com/silvioincalza/helper2.ai/issues/322), [S2.2.3](https://github.com/silvioincalza/helper2.ai/issues/188), [S2.2.5](https://github.com/silvioincalza/helper2.ai/issues/190).

## Success criteria (product level)

| ID | Criterion | Verified by |
|---|---|---|
| SC-1 | One query returns aggregated results from every enabled source that responded within its timeout | API integration tests; UI component tests (Vitest + Testing Library, API mocked) for results, the partial line and empty/all-failed states. The browser journey is not automated: [S10.12.1](https://github.com/silvioincalza/helper2.ai/issues/357) |
| SC-2 | Every configured source appears in the status list with a truthful outcome | Orchestrator unit tests; UI component tests for the source status panel (every outcome in words). The browser journey is not automated: [S10.12.1](https://github.com/silvioincalza/helper2.ai/issues/357) |
| SC-3 | Asking the guide never loses query/filters | UI component tests: the URL keeps `q`, `ask` and filters; closing the answer keeps the search; back/forward restore the box; retry and replacing a running question. The browser journey is not automated: [S10.12.1](https://github.com/silvioincalza/helper2.ai/issues/357) |
| SC-4 | No invented dates/popularity; unknowns labeled | domain tests + UI tests |
| SC-5 | Every published AI-extracted fact links to evidence; unsupported → unverified/review | enrichment validator tests |
| SC-6 | Search works with cache down, LLM down, one or more sources down | resilience tests |
| SC-7 | Keyboard-only and screen-reader users can complete UJ-1…UJ-6 | axe smoke tests in jsdom (color contrast excluded), focus and live-region component tests + manual checklist ([S7.6.2](https://github.com/silvioincalza/helper2.ai/issues/324)) |
