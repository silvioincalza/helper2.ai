# 0028 — Design system

**Status.** Accepted. Merges old 0040, 0042 (texts merged in PR 12 of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0040"></a>

## Part: was ADR 0040 — Frontend design: home page, results with preview, guide picks bar, design system

**Status.** Accepted. Superseded in part by [the part that was ADR 0042](#part-0042) (decisions 1, 2, 4 and 7: the look, the fonts, Results and the preview). **Builds on:** [0027](0027-frontend-one-box.md),
[0016](../guide/0016-guide-pipeline.md), [0018](../guide/0018-ai-content-marking-and-research-trail.md), [0027](0027-frontend-one-box.md)

### Context
The box, the URL state and the guide stream are in [0027](0027-frontend-one-box.md). This record decides the pages
around them. Three things matter for a user:
- The guide answer arrives after several seconds. Results are ready much sooner, and the user starts reading them.
  Anything that moves the list at that moment makes them lose their place.
- Checking a result must not cost a page load and a Back. Comparing three results must stay on one page.
- On a phone, the home feeds and the results must not compete for the same narrow column.

We drew the design as clickable prototypes (Home, Results, Resource, States, Saved, Sources, Mission, Privacy)
and chose between options for each part.

### Decision
1. **Same palette, one font, one design system.** Paper, indigo ink, one saffron accent. The fonts are Geist and
   Geist Mono ([the part that was ADR 0042](#part-0042), decision 1); code and commands in the system's monospace
   font.
   `docs/design/system/tokens.json` holds the base tokens, the semantic tokens (type tags, status
   pills, proof quote, sources tile, selected row) and the breakpoints 560, 640, 860 and 1000 px.
2. **Home is only the home page, and says what the site is for.** One headline ("Find the right AI tool for your
   task."), one line on what it searches and how many sources (from the registry, never a fixed number), the one
   box and four example needs for every kind of user. Right under them, so they show at first sight with the box,
   **New this week** (last 7 days) and **Coming up** (next 3 months) as still lists. **What helper2 does** follows
   as a quiet strip of three steps. Each row: date, type tag, source or place, status pill
   (Updated, Published, First seen, In 13 days…), name, and for News the source's own line (never an AI-written
   one). A list shows six rows; **Show all** opens the rest in place. Nothing on the page moves on its own. The
   header links to the two lists (`/#news`, `/#events`); Sources, Mission and the theme are in the footer.
3. **Results on `/search`.** The box and the examples open `/search?q=…`. `/?q=…` shows the same page and declares
   `/search` as the canonical URL, so search engines see one page.
4. **List and preview.** From 861 px: the result list on the left, a preview of the selected result on the right
   (actions, then what the result is, Use and Build facts, Listed by, Timeline). For a guide pick, "Why the guide
   picked it" comes first: the reason, labelled AI-written, with a line that says if a second model call checked it;
   then the proof quote with the checked sentence highlighted (the source's words, never marked AI) and the user's
   requirements, confirmed or not. For any other result, "About" and its own description: no judgement. Up to
   860 px: the preview opens inside the list, under the selected row, with a link to `/resource/:id`.
5. **The selected result is in the URL.** `?q=…&id=…`, changed with `history.replaceState`: a shared link opens the
   same result, and Back leaves the search instead of stepping through rows. Without an `id`, the guide's pick #1 is
   selected, else the first row; an `id` the user chose (a click, a shared link, a reload) always wins, so the answer
   never takes the preview away from a result the user opened. An `id` that is not in the results counts as none.
   The `id` is untrusted input: it only selects among results already on the page.
6. **Guide picks bar, picks first in one update.** The guide runs only when the user asks it: the "Ask the guide"
   button next to Search, or Shift+Enter. Enter always searches. A name search ("pgvector") gets no picks bar and no model call
   ([0017](../guide/0017-guide-cost-guard-and-answer-cache.md)). For a question, a box above the list is there from the first render:
   "Guide is reading N candidates… picks will appear here". Its height is reserved, and so are the lines that come
   with the answer ("web searches left" and "How the guide decided"), so the top of the list does not move when the
   answer arrives over SSE (`POST /api/v1/guide/stream`, [0016](../guide/0016-guide-pipeline.md)). The box
   shows the picks as chips (#1, #2, #3), labelled AI-written ([0018](../guide/0018-ai-content-marking-and-research-trail.md)); the chips
   stay on one line, cut a long title with "…", and scroll sideways when they do not fit. In the same update the
   picks on this page move to a "Guide picks" group at the top of the list, in the guide's order, each with a
   "Pick #n" badge and its reason; "More results" follows in search order, and no result shows twice. Focus stays on
   the row that had it, and one polite status line says the picks are now at the top. Tapping a chip selects that
   row; a pick that is not on the page links to its own page.
   No pick: the box shrinks to one short line that says why (today's AI answers are used, the model is not
   available, the catalog had nothing, the subject was not found, or "Nothing clearly fits your need."), and the list
   keeps the search order. When the model ran, the next steps sit in the box: "Ask without “…”" for each requirement
   the guide read (the same structured removal as ✕ in "Understood as") and "Rephrase", which puts the cursor in the
   box.
   "How the guide decided" is a fold under the box with a chevron. It holds only the decision: the steps (planned,
   found, chose, checked), "Understood as" with ✕ to ask again without a part, the comparison, explanation or prompt
   an intent asks for, and the models. The picks are never repeated there, and the no-pick line is not either.
7. **Sources at the bottom.** The fan-out summary and one tile per source (status, response time or cache age) sit
   under the results, not in the header.
8. **States are designed, not left to chance.** Loading (skeleton rows), guide answering, no results, some sources
   failed, all sources failed, 404. Each has a next step. One component draws them all (`StatePanel`): a heading
   that says what happened, why under it, the next step last. An error is an alert. Try again keeps focus on a
   heading that stays on the page, never on the page body.
9. **Footer says only "No ads."** Replaced by [0030](0030-monetisation.md): the footer says "No tracking ads."
10. **One design, released by tag.** The code holds one design and no design flag. Prod deploys only a release tag
    ([0033](../operations/0033-ci-cd-and-supply-chain.md)), so `main` and staging run a change before a release takes it to users.

### Consequences
- The user reads results at once. When the guide answers, the best answer is the first row and already in the
  preview; the top of the list stays where it was, but the rows under it change order once.
- Checking a result is one click, not a page load and a Back.
- A shared link opens the same search and the same result.
- Search ranking and the guide's opinion stay visibly separate: "Guide picks" is the guide, "More results" is
  ranking.
- A newcomer reads what the site does before scrolling; nothing moves, so there is nothing to pause.

### Alternatives considered
- **Look: minimal search page, editorial news front page, or developer console** — each drawn on its own. Minimal
  was fast but plain, editorial needs fresh content every day, the console was too dense for beginners. Home and
  Results take parts of all three.
- **Results: answer first** (the guide's answer as a block above the list) — good when the guide is sure, but the
  page waits several seconds for its most important part.
- **Results: grouped by type** (MCP servers, libraries, models, papers) — good for broad searches, empty groups for
  narrow ones.
- **Guide answer: badges only** (the list keeps the search order) — nothing moves, but pick #1 can sit at row 5,
  under the fold, and the preview opens on search result #1.
- **Guide answer: the picks as cards in the fold** — a second list of the same results, out of sight.
- **Results only on `/`** — keeps one route, but home and results share a URL and a page component.
- **A design flag per environment** (on in one image, off in another) — a build constant needs one image per
  environment, and staging would not test the image prod gets. Release-only prod deploys make it unneeded.

### Code
- `frontend/src/styles/tokens.css` — the semantic tokens of `tokens.json`, each an alias of its base token.
- `frontend/src/components/ui/` — `TypeTag`, `StatusPill`, `ProofQuote`, `SourceTiles`, `SelectableRow` (the
  selected-row outline), `PicksBar` (reserved height, waiting line, no-pick line, AI-written picks slot),
  `StatePanel` and `RetryButton` (every error, empty and 404 state), `CopyButton` and the type tabs; `ui.css`.
- `frontend/src/hooks/useRetry.ts` — Try again moves focus to a heading, so it never falls to the page body.
- `frontend/src/hooks/useGuideStream.ts` — the guide stream for the question and refinement in the URL.
- `frontend/src/pages/Home.tsx` — the home page: headline, the one box, the examples, what helper2 does, the two
  lists, and the scroll to `#news` or `#events`.
- `frontend/src/components/home/` — `HomeBoard` (a still list, six rows, Show all), `NewsBoard` (`GET /recent`) and `EventsBoard`
  (`GET /events/upcoming`); `home.css`. `frontend/src/lib/countdown.ts` — Today, Tomorrow, In N days, weeks or
  months.
- `frontend/src/lib/searchState.ts` — `RESULTS_PATH` is `/search`; `resourcePath(id)` builds every link to a
  resource page with the id encoded. `OneBox` sends results there and searches live only
  on that page.
- `frontend/src/pages/HomePage.tsx`, `routes.tsx` — `/` empty is the home page; `/search` shows results and goes home
  with no query; `/?q=` shows results too; there is no `/guide` route. Both result pages carry
  `<meta name="robots" content="noindex, follow">`; so do `/saved` and `/account`, and the not-found pages say
  `noindex` (`components/Robots.tsx`).
- `frontend/src/components/results/Results.tsx` — the results page: the box, the picks bar (questions only), type
  tabs, one row with the "Filters" toggle (sort, list or timeline, the filter panel), **Best for** (All, Apps I can
  use, Build with AI: the audience `path`, ranking only, `results/AudienceChoice.tsx`; it is the only audience
  control, the filter panel has no audience facet) and the detected use cases,
  search notices, the list of `SelectableRow`s with the guide's picks first (each title a link to its resource page;
  from 861 px a plain click selects it), the preview beside the list from 861 px, on a phone a row opens the resource
  page, the pager, and the sources at the bottom. Every state says what happened and offers a next
  step; after a page change, focus goes to the result count. `results.css`. `frontend/src/lib/selection.ts` — the
  selected result (`id` in the URL, else pick #1, else the first), the picks by resource id and the list with the
  picks first. `frontend/src/hooks/useMediaQuery.ts` — beside the list or inside it.
- `frontend/src/components/guide/GuidePicks.tsx` — the picks bar's content: chips, the no-pick next steps, and the
  fold "How the guide decided" (`GuideAnswer`: steps, "Understood as", the intent's answer).
- `frontend/src/components/results/Resource.tsx`, `ResultFacts.tsx` — the resource page and the facts, "Listed by"
  (with mode and stale-cache marks) shared with the preview; `frontend/src/pages/ResourcePage.tsx` loads it once.
- `frontend/src/pages/NotFoundPage.tsx` — the 404 page.
- `frontend/src/components/saved/Saved.tsx` (with sign-in when signed out, ADR 0024), `components/sources/Sources.tsx`,
  `pages/AccountPage.tsx` — Saved, Sources and your account.
- `frontend/src/components/TextPage.tsx`, `pages/MissionPage.tsx`, `PrivacyPage.tsx`, `TermsPage.tsx` — the text
  pages with "On this page".
- `frontend/src/pages/admin/`, `pages/admin/admin.css` — the admin back office ([0024](../edge/0024-identity-and-admin-host.md))
  in the design: the review queue as rows like the saved rows, each status as a `StatusPill`, the Scout runs as a
  table.
- `frontend/src/components/Layout.tsx` — News, Events and Saved in the menu; Sources, Mission, the theme and "No ads."
  in the footer. Up to 720 px the menu folds behind a menu button (☰, `aria-expanded`): the logo and the button share
  one row, and the pages and sign-in open in a panel below; Escape or a new page closes it. From 721 px the menu is
  the header row
  (`styles/app.css`: tighter gaps up to 1024 px, the tagline hides up to 900 px).

### Diagrams
[flow](../../diagrams/flow-0028-frontend-design.svg) · [sequence](../../diagrams/sequence-0028-frontend-design.svg)

### Roadmap
[Frontend](../../roadmap.md#6-frontend): consent banner
and Privacy update before any tracking cookie; a release to take the design to prod.
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).

<a id="part-0042"></a>

## Part: was ADR 0042 — Redesign: design system version 4

**Status.** Accepted. Built for every page: the header, the footer, Home, Results (list and timeline), the resource
page, Sources, Saved, the back office and the text pages (Mission, Privacy, Terms, Not found, Sign in, Account).
**Replaces parts of:** [the part that was ADR 0040](#part-0040): decisions 1, 2, 4 and 7.
**Builds on:** [0016](../guide/0016-guide-pipeline.md), [0017](../guide/0017-guide-cost-guard-and-answer-cache.md),
[0027](0027-frontend-one-box.md), [0018](../guide/0018-ai-content-marking-and-research-trail.md)

### Context
[the part that was ADR 0040](#part-0040) gave the site its first full design. In November 2026 we reviewed it page by page with
one goal: everyone must understand at once what helper2.ai helps with. We drew every page again, with all its states,
the dark theme and the phone layout, and had the drawings reviewed for user journeys, accessibility and fit with the
API. The boards are in [docs/design/redesign](../../design/redesign); the rules are the design system, version 4
([docs/design/system](../../design/system/README.md)). For New this week and Coming up we drew three more options
(`TechLists`, `MobileLists` in the same folder) and chose the tables on wide screens and the tabs on phones.

A move of this size cannot land in one pull request. The header, the footer and Home moved first, then the list
pages, Results and the resource page, and the text pages; this record says what every page does now.

### Decision
1. **Two fonts with clear jobs.** Geist Mono for the logo, headings, controls, labels, meta lines and the two lists:
   the tech look. Geist for running text (paragraphs, list titles, the guide's reasons), because long text in a
   monospace font reads slowly. Both are the variable fonts from `@fontsource-variable/geist` and
   `@fontsource-variable/geist-mono` (SIL Open Font License), bundled by Vite and served from our own origin: no
   third-party request, and the CSP stays `font-src 'self'`. One file per script covers every weight; an English page
   loads only the Latin files (29 KB and 23 KB), and the build preloads those two. These are the fonts of the whole
   site (5 November 2026). Code and key hints use the system monospace: nothing to download.
2. **One set of version 4 tokens.** `tokens.css` holds the version 4 colours (light and dark), shadows, sizes and
   fonts under plain names, from `docs/design/system/tokens.json`: `--font-mono` (Geist Mono), `--font-body` (Geist),
   `--font-code` (the system monospace), `--box-height` (60 px, 50 px on a phone), `--header-height` (74 px, 65 px on
   a phone). Every page sits in the header's column (`.v4-wrap`); Home spans the full width.
3. **Header and footer.** The header: the logo (mark, wordmark, "AI on your radar"), News, Events, Saved, Sign in or
   the account. Up to 720 px: the logo and a menu button; the menu holds New this week, Coming up, Saved, Sources,
   Mission, Sign in and the theme; Escape closes it. On the pages without their own box, the box sits in the header.
   The footer: the logo, one row of links (Mission, Sources, Privacy, Terms, GitHub ↗, the licence ↗, the release ↗,
   the theme switch), then one line: what is searched, no ads, who and where.
4. **Home: the title, the radar, a pinned search box, two lists.** No line above it. The title is one `h1` on two
   lines: "helper2.ai" (accent ink, same size and weight), then "The metasearch for the AI universe.", with one line
   under it. One heading names the site, for search engines and screen readers. Around it sits a radar that works like
   a navigation radar (PPI): full rings around the title, and a beam (a sweep wedge) that turns once every 5 s.
   - **Which sources.** Only external sources (`external` on GET /sources): Helper2's own index, its search by meaning
     and its curated list are left out. Live sources sit on the two inner rings, the Scout collectors on the two outer
     ones. Each source has its own fixed spot from the order GET /sources lists it in, left and right of the title,
     never under the title or the box, and never touching another name (`radarLayout.ts`). A dot never slides.
   - **5 to 10 at a time, every source in turn.** It starts with 5 sources, 0.6 s apart. Then every 1.6 s one step:
     while it grows, the next source of the queue comes, up to 10; then the oldest leaves, one at a time, down to 5;
     then it grows again. The queue holds every source, shuffled once when the radar starts; a source that leaves
     goes to the back, so every source of a group is on the radar once before any of them comes back
     (`radarRotation.ts`).
   - **Painted by the beam.** A dot shows only when the beam passes its angle: full opacity, a short pop (scale 1.35,
     back to 1 in 0.25 s) and a glow in its own colour (gone in 0.6 s); then it fades to 0.22 until the next pass. A
     new source shows the first time the beam passes it. A source that leaves is not painted again and fades to 0
     within 3 s (`radarClock.ts`). The beam and the dots run on one clock, a `requestAnimationFrame` loop that sets
     the beam's angle and each dot's opacity, pop and glow from the time since the beam last passed it. So they cannot
     drift apart.
   - **What a dot says.** Under each dot only its short name (`shortName`). Every dot is filled with its colour:
     orange (accent) answering, amber slow or limited, red failed (grey when skipped or not checked yet). The colours
     are 3:1 or more on the page in both themes (a test checks it). Size = `newThisWeek`; null keeps the default size.
     Colour is never the only key: each dot is a link to the Sources page whose accessible name and tooltip say the
     state ("GitHub, live, answered in 410 ms, 14 new this week"; "Awesome, from our index, last check failed"). A dot
     that is not on the radar is hidden from screen readers and the keyboard.
   - **The legend** under the box: "5-10 of N sources at a time" (N from GET /sources), a filled swatch for answering,
     slow or limited and failed, and a link to the Sources page.
   - **When it holds still.** The clock stops while the search box has focus: the beam and the dots hold still. Under
     reduced motion, or for a signed-in user who turned "Radar: keep moving" off on the account page
     (`preferences.radarMotion` on GET /me, saved with PATCH /me/preferences, ADR
     [0024](../edge/0024-identity-and-admin-host.md)), there is no clock: the beam stands still and a fixed random set of 7
     shows at full opacity. Up to 900 px only the rings and the beam show.

   The search box, its hint, the "Try:" examples and the radar legend stay pinned under the header while the page
   scrolls; once pinned, the hint, the examples and the legend fold away. On phones (720 px or less) the title and the
   radar fold away after 24 px of scroll and open again at the top.
5. **New this week and Coming up: one list component.** Both lists are one `TermList`, in a terminal style without
   terminal windows: a `$ helper2 news · N` line (N = `total` from the API), `ls -l` columns with dashed rules, the
   titles in Geist and the rest in Geist Mono. On a wide screen two tables side by side: DATE · TYPE · TITLE · SOURCE
   and WHEN · TYPE · EVENT · WHERE, a running event "on now" in green. Below 768 px one panel with two tabs, `> new
   this week N | coming up N`, and log rows (date and `[type]`, the title, the host or how far away and where). Coming
   up asks GET /events/upcoming for `days=90`, so the window and its `total` are the server's. While a list scrolls by,
   its head (and on a wide screen its column names, on a phone the tabs) stays under the pinned search box until the
   list ends; Home measures the box's height for that. Six rows show; `$
   helper2 news --all · show all N` opens the rest in place. Loading, empty and error (with Retry) are the same in
   both. The tabs are real buttons in a tablist (`role="tab"`, `aria-selected`, arrow keys, Home, End), 44 px tall;
   the tables have column headers. No blinking cursor, no lines that print in.
6. **Sources, Saved and the back office in the terminal style.** Each list page has a `$ helper2 … · N` line next to
   its heading, with N from the API (`TermCommand` from `TermList`): `$ helper2 sources --all · N` (every source on
   GET /sources), `$ helper2 saved · N` (the saved list), `$ helper2 review --open · N` (`total` of the queue; the
   flag follows the status filter), `$ helper2 scouts --runs · N`, `$ helper2 examples · N`, `$ helper2 audit · N`;
   the overview has `$ helper2 status`, without a count. Tables use the `v4-table` of `TermList`: Geist Mono,
   UPPERCASE column headers, dashed rules, names that are titles in Geist. A wide table scrolls inside its own box
   (`ScrollBox`, a named region a keyboard can reach); the page never scrolls sideways.
   - **Sources** is one table: SOURCE · LAST CHECK · MODE · COVERS · WHEN · NEW THIS WEEK · TERMS. It uses the radar's
     short names (the full name under it when it differs) and the radar's colours and grouping (`toneOf`): answering
     orange, slow or limited amber (timed out, rate limited), failed red, skipped, not checked or disabled grey. Each
     state is a word with a glyph (✓ ! × ○); the colour only repeats it. The table lists every source, ours included:
     the mode column says "ours, not on the radar" when `external` is false. One line says how many the radar shows:
     "The Home radar shows 5-10 of these N external sources at a time". The pause line and the metered source's web
     credits sit under the name, the response time under the check time, the attribution under Terms. The mode chips
     and the refresh on return stay as they were.
   - **Saved** keeps its rows (type, host, saved date in Geist Mono, the title in Geist, the original link, Remove)
     and its type chips. Loading, the sync error (with Try again) and the empty list are `ListState`, the states of
     `TermList`; the empty list also links back to search. Signed in, the page shows the loading lines until the
     account list arrives (`loading` on the bookmarks context), so it never says "Nothing saved yet" too early.
   - **Back office:** the review queue is a table (KIND · RESOURCE · WHAT TO CHECK · OPENED · DECISION; the note and
     Accept, Reject in the last column), Scout runs, the budgets and the audit trail are tables, the examples stay
     dashed rows. A status is a coloured word with a glyph, not a pill. Every action stays as it was: Run now per
     source, Run all now, the review decisions with their note, the examples and their drafts. The back office is
     still loaded on demand and only over the VPN.
   - Saved loads on demand, like Sources and Account: few visits start there.
7. **Results: one column, no preview, in the terminal style.** Results load on demand (`React.lazy` in `HomePage`);
   the page's `h1`, the box and a skeleton of the list's shape are in the first download, so nothing mounts twice or
   moves while the chunk loads. The skeleton pulses three times and stops.
   - **The heading.** "Results" over `$ helper2 search · N` (`--timeline` on the timeline; N = `total`), List / Timeline
     next to it (links with `aria-current`: the view is in the URL), then Filters ("· 2 on") and the sort. Under it the
     type tabs: All, the four types with most results (two on a phone), More with the rest; a type chosen under More
     shows as a tab. On a phone the heading keeps only the count (the command line is decoration) and shares a line with
     List / Timeline, Filters and the sort the next one, and the Best for and use case chips are one line each, scrolled
     sideways, so the first result is in sight. Filters open in place on a wide screen and as a drawer (a modal
     `dialog`) up to 720 px, with Close and "Show N results"; Escape and Close give focus back to Filters.
   - **Rows.** Dashed rules between rows. A row: type · host · date (updated, else created, else "first seen" in the
     not-stated colour), the guide pick, the title, the description, "From" the sources. The title's link covers the
     row and opens the resource page; nothing is selected or opens in place, on any width. The resource page's **Back
     to results** goes back in history, so the list keeps its place; opened directly it is a plain link.
   - **Show more.** Under the list (and the timeline) instead of pages: it adds the next page in place, moves focus to
     its first row and says "20 more results" once, politely. `page` in the URL is the last page shown, so Back and a
     reload show the same rows. Each page is its own GET /search through the recent-answers cache (`morePages.tsx`),
     so Back needs no request. On Back the pages in that cache are read during render, so their rows are there before
     the scroll position comes back, and the browser does not cut it to a shorter list. Up to page 50, as the API
     allows.
   - **No guide suggestion over the list.** The box has the Ask the guide button; an empty result keeps "Ask the guide
     instead".
   - **Guide picks merged into the list.** The rows keep the search order, so nothing moves when the answer comes and
     nothing is announced. A picked row shows "Guide pick #1", the reason (labelled "AI-written", marked
     `data-ai-generated`) and its quote, highlighted where it stands in the source's own words, with "Checked word for
     word in the source". On an AI-written description there is no quote. The chips in the picks bar jump to the rows
     in the list. On the timeline, and for a pick not on the first page, a chip opens the resource page instead. The
     row and the chip pass only what that page shows of the pick (rank, reason, quote, checks) on in the history
     entry's state, so the resource page says **Why the guide picked it**, whether a second model call checked it, and
     the requirements checked against the source. Nothing of it leaves the browser; it goes with the tab, and the
     Privacy page says so.
   - **Some sources did not answer:** one quiet line under the heading, no banner and no live region: "2 of 5 sources
     did not answer · Which?" (`CoverageLine`). Which? lists them and why, in words. Try again only when a retry can
     help (an error, a timeout, busy); never while a source is paused or its circuit is open.
   - **Best for** (Anyone, Apps I can use, Build with AI) sits with the use case chips under the type tabs. It sets the
     audience `path`, which changes what ranks first and never hides a result.
   - **The timeline:** the month beside a line of dots, each item its creation date, type, host, title and
     description. The results with no stated date are one group at the end, folded: "Date unknown · N · Show them" (a
     button with `aria-expanded`).
8. **The resource page: one column.** v4 fonts and tokens. The title, under it type · host · the same date as the row
   (`ResultDate`), one primary button (Open on host ↗), Save, and Set it up (outlined) only for what a user installs
   or runs. Then the pick, From the source, At a glance, the dates, "Where this information comes from" (folded: each
   source, its mode and when it was read) and the detail sections; its tables in Geist Mono with capital headers and
   dashed rules.
9. **Text pages stay prose.** Mission, Privacy, Terms, Not found, Account and the sign-in page have no terminal look:
   Geist for headings and reading text, Geist Mono only for small interface labels (On this page, the date, the 404
   label, buttons), a column of about 68 characters with a line height of 1.65, lined up with the header (`text.css`,
   `.h2-prose`). They load on demand, like Sources and Account.
   - **Mission** has its sections, then how the guide answers (three numbered steps) and the rules, as prose; "On this
     page" links to all of them.
   - **Not found** keeps the real 404 (the server answers 404 and writes the head): a `404` label, the title, one line
     that points to the box in the header, and a link to the home page. No second search box.
   - **Account:** who is signed in, with the AI answers and web searches left today and their reset time (`limits` on
     GET /me; a null count is not shown), the radar setting "Radar: keep moving" (on by default; off keeps the Home
     radar still, as reduced motion does), and Delete my account in a card, with the confirmation under it (focus on
     Cancel).
10. **The sign-in page** (`/signin`), in the text style of decision 9: why to sign in on the left, the ways to sign in
    in a card on the right, one button per provider in `loginProviders` on GET /me ("Continue with GitHub"). A
    provider that is not configured is not shown; with none, the card says sign-in is not offered here. "Sign in" in
    the header opens this page and carries the current page, so the visitor comes back to it (ADR
    [0024](../edge/0024-identity-and-admin-host.md)). A failed sign-in lands on `?signin=failed`; the alert links back to this
    page. The Saved page and the admin page keep their direct provider links.
11. **The guide by choice: two buttons, and the quota is in sight.** The box has both buttons, always, for any length
    of text: Search (primary, saffron) and Ask the guide (outline, quieter). Enter searches; Shift+Enter or Ask the
    guide searches and asks ([0017](../guide/0017-guide-cost-guard-and-answer-cache.md)). No word count decides anything. The hint
    under the box says so: "Enter searches. For picks with a quote that proves them, ask the guide (⇧↵)." Each button
    shows its key, ↵ and ⇧↵, on Home and in the header on a desktop; key hints stay hidden on touch devices and on a
    narrow box. Up to 520 px Ask the guide is a 44 px icon button (an inline stroke icon) with its name and a tooltip.
    Neither button is ever disabled: with nothing to send, either puts the cursor in the box. Once there are words in
    the box, one quiet line under it says today's AI answers ("2 of 3 AI answers left today"; at none, when they come
    back and that search still works), from `limits.guideQuestions` on GET /me, read again after each answer
    (`GuideAnswersLeft`, UJ-2). When the guide has no model the field is null, nothing is spent, and the line is not
    shown.
12. **Nothing moves on its own for more than 5 seconds, with two exceptions.** No CSS animation repeats: the loading
    lines pulse three times and stop. Under reduced motion nothing moves, and nothing folds with an animation. A test
    fails on any CSS or inline animation that repeats for ever. The exception is the radar (decision 4). It moves by a
    JavaScript clock, not a CSS animation; it holds still while the search box has focus, stands still under reduced
    motion, and a signed-in user can turn it off. The second exception is the home lists, New this week and Coming
    up, as a news ticker (v5, `components/home/ticker.ts`): when a list has more rows than it shows, one row leaves at
    the top every 5 seconds and the next comes in at the bottom, with a 700 ms slide. It holds still while the pointer
    is over it or the focus is in it, after a tap on it (a second tap starts it again), while it is off screen or the
    tab is hidden, under reduced motion and when the radar's motion switch is off. "Show all" opens the whole list,
    still. There is no Play/Pause button, by choice: hover, focus and tap are the pause (WCAG 2.2.2). The trade-off:
    a visitor has to find the tap; a keyboard user pauses it by moving into it.
13. **Every number comes from data.** The radar legend's "5-10 of N sources at a time" counts the enabled external
    sources of GET /sources, the same sources the radar shows (13 on staging; not shown before the answer); the dot
    names say the response time or the age of the last check; the list counts are `total` on GET /recent and GET
    /events/upcoming (never fewer than the rows sent). One small reader (`lib/v4Fields.ts`) takes `newThisWeek` and
    `total` and says what the page does when a value is null: a dot of the default size, the rows as the count.
    The Mission, Privacy and Terms pages take their numbers from GET /policy
    ([0029](0029-site-pages-and-policy.md)): the guide's candidates and picks, the daily limits and the
    retention periods. A locale sentence with numbers has two forms: `text` with `{{name}}` variables, and
    `withoutNumbers`, the same sentence in words ("a daily limit", "for a limited time"). The page and the numbers
    load side by side, read once per session (`hooks/usePolicy.ts`, its own chunk); the page waits for them at most
    500 ms, so it usually opens with them in place and nothing moves. Late, it shows the words and then the numbers;
    on an error, the words and one line under the head that says so. Never a typed fallback number. Periods read in
    whole hours, days or months (`lib/duration.ts`), never shorter than the server keeps. What the API does not
    have stays typed: the 00:00 UTC reset, the counter lifetimes, logs, backups and usage totals.
14. **Accessibility, and one set of controls.** The shared controls have one definition each, in `app.css` (badges,
    the state panel and the status pill in `components/ui/ui.css`), and look the same on every page: Geist, the v4
    radius and borders. Geist Mono is only for machine facts: the logo, meta lines, counts, `$ helper2` commands, key
    hints and table cells. Buttons: secondary (`.btn`, an outline on `surface`, like Ask the guide), primary
    (`.btn-primary`, the saffron fill), ink (`.btn-ink`, the sign-in button), ghost (`.btn-ghost`, the outline with no
    fill), danger (`.btn-danger`, a red outline; `.btn-danger-fill` to confirm), icon (`.btn-icon`) and text
    (`.btn-text`, an underlined word). Chips (`.chip`) are pills, the picked one an ink fill. Fields are Geist on
    `surface` with the control border; checkboxes are ink. Hover always draws a line or a ring of 3:1 or more; a
    disabled button is quiet, not faded. A contrast test checks every state in both themes
    (`controlsContrast.test.ts`). Every control is at least 44 px on a touch screen; on the results page with a mouse
    (pointer fine) buttons and tabs are 36 px (`control-sm`) and chips 32 px (`chip-sm`), above WCAG 2.2's 24 px. The focus ring (3 px ink outline) never looks like the
    selected line (saffron underline in the header, an ink folder tab in the tabs). The Search button is never
    disabled: with nothing to send it puts the cursor in the box. Key hints (`/`, `↵`) show only where there is a
    keyboard. Text and controls meet AA in light and dark (muted 7.5:1 and 8:1, links 5.5:1 and 8.7:1, control borders
    3.3:1 and 3.9:1). The axe test runs in both themes, on a wide screen and on a phone, on Home (with the menu open),
    Results, the resource page, the list pages (Saved, Sources, the back office) and the text pages.
15. **One design, page by page, then version 3 removed.** Each page moved in its own pull request; no design flag.
    When the last one had moved, the version 3 font, its layout sizes (`maxw`, the 56 px box), its
    unused components and the `--v4-` prefix on the tokens went.

### Alternatives
- **Fonts: `geist` (Vercel).** The upstream package, but built for `next/font` (it imports `next/font/local`) and
  its files carry every script (70 KB each). Static `@fontsource/geist`: 13 KB per weight, so three weights of two
  families cost more than one variable file each.
- **Lists as terminal windows** (the first boards). Strong look, but heavy on a phone and the window frame adds
  nothing. **Tabs on every width** (option 2): one component, but a wide screen hides half of what is new.
- **Monospace everywhere.** The strongest tech look, but slow to read on Privacy, Terms and the guide's reasons.
- **One font, Space Grotesk, everywhere.** No new font download. Chosen on 2 November 2026, dropped on 5 November:
  the boards and the moved parts are drawn in Geist and Geist Mono, and the two variable files cost 52 KB on an
  English page.
- **Sign in straight from the header** (main before v4): one click less with one provider, but the visitor never
  sees why to sign in, and a second provider needs a menu. The page is one click more and says it.
- **The guide on every search.** Simpler, but it spends model calls and the daily AI answers on searches that are
  only a name ([0017](../guide/0017-guide-cost-guard-and-answer-cache.md)).
- **The guide on its own from four words** (the four-word rule). No extra key, but a long name spends an AI answer
  and often gets "no pick".
- **One big pull request.** Fastest to finish, hardest to review and to roll back.
- **Results: keep the preview beside the list** ([the part that was ADR 0040](#part-0040), 4). Fast to compare, but it splits a
  phone and a desktop into two behaviours, and the picks moving to the top shifted the list under the reader.
- **Results: load the whole page on demand, the box included.** A smaller first download, but the box and the `h1`
  would mount twice and focus would be lost on the way from Home.

### Consequences
- Users see what the site does in one line, and the box never leaves the screen.
- The radar and the Sources page show the same data, so they cannot disagree.
- WCAG 2.2.2 asks for a way to pause moving content for every user. Signed-in users can turn the radar off; anonymous
  visitors have only reduced motion and the hold while the search box has focus. The radar never ends on its own and
  guests have no switch. This is a known limit, on the [roadmap](../../roadmap.md).
- The radar shows 5 to 10 sources at a time, so a visitor sees every source only over time: with 47 sources, a
  category comes back after about 5 minutes, a live source after about 1. The legend links to the Sources page, which
  lists them all.
- The radar's clock draws on every animation frame while the page is open and the box has no focus. It writes only
  CSS variables and one transform, and React renders only when a source comes or goes; the browser stops the frames
  on a hidden tab.
- The API has the fields the drawings need: `newThisWeek` per source on GET /sources, `limits` on GET /me, and
  `total` on GET /recent and GET /events/upcoming (which also takes `days`). The Unknown count per facet is still open.
- The initial JavaScript is 158.2 of 250 KiB gzip ([0027](0027-frontend-one-box.md)): Results load on demand, like
  the radar, the phone tabs, Sources, Saved, the back office and the text pages. A first visit to /search waits for
  that chunk behind the skeleton.
- Results show one screen of rows and one place per result; comparing two results means opening both. A reload
  with `page=5` asks for six pages at once. A shared link
  with the old `id` parameter shows the list without selecting anything.
- What is not built yet is on the [roadmap](../../roadmap.md) (section 6).

Flowchart: [flow-0028-redesign](../../diagrams/flow-0028-redesign.svg). Sequence:
[sequence-0028-redesign](../../diagrams/sequence-0028-redesign.svg).
