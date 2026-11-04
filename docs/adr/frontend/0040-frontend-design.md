# 0040 — Frontend design: home page, results with preview, guide picks bar, design system

**Status.** Accepted. Decisions 1, 2, 4 and 7 are replaced by [0042](0042-redesign-design-system-v4.md) as each page moves to it. **Builds on:** [0025](0025-frontend-one-box.md),
[0015](../guide/0015-ai-guide-evidence-gate.md), [0038](../guide/0038-ai-content-marking.md), [0039](0039-frontend-recent-answers-cache.md)

## Context
The box, the URL state and the guide stream are in [0025](0025-frontend-one-box.md). This record decides the pages
around them. Three things matter for a user:
- The guide answer arrives after about 15 seconds. Results are ready much sooner, and the user starts reading them.
  Anything that moves the list at that moment makes them lose their place.
- Checking a result must not cost a page load and a Back. Comparing three results must stay on one page.
- On a phone, the home feeds and the results must not compete for the same narrow column.

We drew the design as clickable prototypes (Home, Results, Resource, States, Saved, Sources, Mission, Privacy)
and chose between options for each part.

## Decision
1. **Same palette, one font, one design system.** Paper, indigo ink, one saffron accent. One font, Space Grotesk
   (400, 500, 600), for text and titles; code and commands in the system's monospace font, so nothing else downloads.
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
6. **Guide picks bar, picks first in one update.** The guide runs only for a question: four words or more, or
   Shift+Enter. A name search ("pgvector") gets no picks bar and no model call
   ([0017](../guide/0017-guide-cost-guard.md)). For a question, a box above the list is there from the first render:
   "Guide is reading N candidates… picks will appear here". Its height is reserved, and so are the lines that come
   with the answer ("web searches left" and "How the guide decided"), so the top of the list does not move when the
   answer arrives over SSE (`POST /api/v1/guide/stream`, [0015](../guide/0015-ai-guide-evidence-gate.md)). The box
   shows the picks as chips (#1, #2, #3), labelled AI-written ([0038](../guide/0038-ai-content-marking.md)); the chips
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
9. **Footer says only "No ads."** The footer makes no claim about tracking, so it stays true whatever the cookie
   policy becomes.
10. **One design, released by tag.** The code holds one design and no design flag. Prod deploys only a release tag
    ([0028](../operations/0028-ci-cd.md)), so `main` and staging run a change before a release takes it to users.

## Consequences
- The user reads results at once. When the guide answers, the best answer is the first row and already in the
  preview; the top of the list stays where it was, but the rows under it change order once.
- Checking a result is one click, not a page load and a Back.
- A shared link opens the same search and the same result.
- Search ranking and the guide's opinion stay visibly separate: "Guide picks" is the guide, "More results" is
  ranking.
- A newcomer reads what the site does before scrolling; nothing moves, so there is nothing to pause.

## Alternatives considered
- **Look: minimal search page, editorial news front page, or developer console** — each drawn on its own. Minimal
  was fast but plain, editorial needs fresh content every day, the console was too dense for beginners. Home and
  Results take parts of all three.
- **Results: answer first** (the guide's answer as a block above the list) — good when the guide is sure, but the
  page waits 15 seconds for its most important part.
- **Results: grouped by type** (MCP servers, libraries, models, papers) — good for broad searches, empty groups for
  narrow ones.
- **Guide answer: badges only** (the list keeps the search order) — nothing moves, but pick #1 can sit at row 5,
  under the fold, and the preview opens on search result #1.
- **Guide answer: the picks as cards in the fold** — a second list of the same results, out of sight.
- **Results only on `/`** — keeps one route, but home and results share a URL and a page component.
- **A design flag per environment** (on in one image, off in another) — a build constant needs one image per
  environment, and staging would not test the image prod gets. Release-only prod deploys make it unneeded.

## Code
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
  with no query; `/?q=` shows results with a canonical link to `/search`; there is no `/guide` route (the site was never public, so no old links exist).
- `frontend/src/components/results/Results.tsx` — the results page: the box, the picks bar (questions only), type
  tabs, one row with the "Filters" toggle (sort, list or timeline, the filter panel) and the detected use cases,
  search notices, the list of `SelectableRow`s with the guide's picks first, the preview beside the list from 861 px
  and inside it below, the pager, and the sources at the bottom. Every state says what happened and offers a next
  step; after a page change, focus goes to the result count. `results.css`. `frontend/src/lib/selection.ts` — the
  selected result (`id` in the URL, else pick #1, else the first), the picks by resource id and the list with the
  picks first. `frontend/src/hooks/useMediaQuery.ts` — beside the list or inside it.
- `frontend/src/components/guide/GuidePicks.tsx` — the picks bar's content: chips, the no-pick next steps, and the
  fold "How the guide decided" (`GuideAnswer`: steps, "Understood as", the intent's answer).
- `frontend/src/components/results/Resource.tsx`, `ResultFacts.tsx` — the resource page and the facts, "Listed by"
  (with mode and stale-cache marks) shared with the preview; `frontend/src/pages/ResourcePage.tsx` loads it once.
- `frontend/src/pages/NotFoundPage.tsx` — the 404 page.
- `frontend/src/components/saved/Saved.tsx` (with sign-in when signed out, ADR 0035), `components/sources/Sources.tsx`,
  `pages/AccountPage.tsx` — Saved, Sources and your account.
- `frontend/src/components/TextPage.tsx`, `pages/MissionPage.tsx`, `PrivacyPage.tsx`, `TermsPage.tsx` — the text
  pages with "On this page".
- `frontend/src/pages/AdminPage.tsx`, `pages/admin.css` — the admin back office ([0023](../edge/0023-identity-and-admin.md))
  in the design: the review queue as rows like the saved rows, each status as a `StatusPill`, the Scout runs as a
  table.
- `frontend/src/components/Layout.tsx` — Sources and Mission in the menu, "No ads." in the footer. Up to 720 px the
  menu folds behind a menu button (☰, `aria-expanded`): the logo, the theme and the button share one row, and the
  pages and sign-in open in a panel below; Escape or a new page closes it. From 721 px the menu is the header row
  (`styles/app.css`: tighter gaps up to 1024 px, the tagline hides up to 900 px).

## Diagrams
[flow](../../diagrams/flow-0040-frontend-design.svg) · [sequence](../../diagrams/sequence-0040-frontend-design.svg)

## Roadmap
[Frontend](../../roadmap.md#6-frontend): consent banner
and Privacy update before any tracking cookie; a release to take the design to prod.
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
