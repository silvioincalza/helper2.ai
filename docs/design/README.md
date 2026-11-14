# Helper2.ai design

Snapshot of the design work, saved next to the code. The live, editable version is a private design canvas, **Helper2.ai**, with three pages: Design system, Prototype & website, App screens.

## The redesign (November 2026)

Minimal and editorial: Geist Mono for the interface, Geist for reading, the radar around the home title, New this
week and Coming up as two `ls -l` tables (tabs on phones). Every page, its states and the dark theme are drawn, on a
private canvas.
Snapshot: [`redesign/`](redesign) (one `.dc.html` per board,
`canvas.json` lays them out). The rules are in the design system: [system/README.md](system/README.md).
The code moves to it page by page ([ADR 0040](../adr/frontend/0040-design-system.md)): the header, the
footer, Home, Results (list and timeline), the resource page, Sources, Saved, the back office and the text pages
(Mission, Privacy, Terms, Not found, Sign in, Account) are built; the boards below are the reference.

### Results and the resource page (5 Nov 2026)

- One column, no preview. A row opens the resource page; Back comes back to the same place in the list.
- "Results" over `$ helper2 search · N` (`--timeline` on the timeline), dashed rules between rows.
- A row: type · host · date, the guide pick, the title with Save on its right, the description, "From" the sources.
  The host is a link to the resource itself (new tab). The title link covers the row; the host link and Save sit above
  it. Each source once: the catalog and the catalog searched by meaning are one name. The description is plain text,
  with no Markdown code fences or backticks.
- Guide picks stay where the search put them: a picked row shows "Guide pick #1", its reason (AI-written) and its
  quote highlighted in the source's words. The chips in the picks bar jump to the rows.
- Type tabs: All, the top 4, More. Filters in place on a wide screen, a drawer on a phone.
- Some sources did not answer: one quiet line under the heading, "2 of 5 sources did not answer · Which?". Try again
  only when a retry can help. On a phone the line is "2 sources did not answer · Which? · Try again", on one line.
- On a phone (up to 520px) the first result starts higher: List / Timeline, Filters and the sort are one row. The
  heading "Results" and the words "Sort by" are hidden there, not removed: a screen reader reads both. The count is on
  the All tab. When filters are on, or up to 359px, the sort takes the row below.
- While a new search runs, the old results stay at full contrast under the "Searching the listed sources…" line.
  Nothing on this page is faded to say "busy".
- "Best for" (All, Apps I can use, Build with AI) sits with the use case chips under the type tabs. Up to 720px each
  row is one line that scrolls sideways. The side with more chips fades out; no scrollbar shows.
- The resource page: one primary button (Open), Save, Set it up only for things you install or run. Under Set it up, one
  quiet line says how many AI setups are left today.
- Show more under the list adds the next page in place; the page is in the URL, so Back and a reload keep it.
- Timeline: the results with no stated date are one folded group at the end, "Date unknown · N · Show them".
- The resource page: the date line under the title (type · host · the row's date), and "Where this information
  comes from" folded, with each source, its mode and when it was read, and the proof: fields with origin, facts
  with evidence, source records.
- The resource page shows what is known first: At a glance has only the facts with a value, then one line,
  "Not stated by any source: …". No section for a missing AI summary.
- No "Ask the guide about …" bar over the list: the box has the button. An empty result keeps "Ask the guide
  instead".

| Mockup | What |
|---|---|
| `TechLists.html`, `.png` | New this week and Coming up in three styles: log, tabs, `ls -l` table. Option 3 (tables) was chosen for wide screens, option 2 (tabs) for phones |
| `MobileLists.html`, `.png` | The two lists on a phone in four styles: plain list, swipe cards, tabs, date rail. Tabs were chosen |

The mockups load `Geist.woff2` and `GeistMono.woff2` from their own folder, which the repo does not hold; without
them they show the system fonts.

### Results v5 (7 Nov 2026)

- Easier to read: more space in a row (28px above and below, 8px between lines), text up to about 68 characters a line.
- With a mouse, the controls are smaller: buttons, tabs, the view switch and the sort 36px (`control-sm`), chips 32px
  (`chip-sm`). On a touch screen they stay 44px. Buttons, tabs and chips on this page are in Geist; Geist Mono stays for
  the meta lines, counts, `$ helper2` lines and key hints. Search and Ask the guide in the box are in Geist too.
- The guide's picks are a small terminal: `$ helper2 guide --picks N`, each pick with its reason (AI-written), and the
  model that chose them. On a phone it is the same list, one pick under the other, each with its reason: a pick that
  is not in the result list has its reason nowhere else.
- In a picked row the reason comes first, then the checked quote in a light box, with a highlighter line under the
  checked words.
- One line under the box with the sources: "9 of 9 sources answered · 3 live, slowest 244 ms · 6 from the cache ·
  Details". Not shown when some sources did not answer: the coverage line says it.
- The search words are in bold in the description (never in the title, AI text or a quote).
- Keys, while the focus is in the results: j and k move, Enter opens, / goes to the box, g goes to the Ask the guide
  button (it asks nothing, so no AI answer is spent on one key), ? lists the
  keys. Only in the results, so a one-letter key never fires by accident (WCAG 2.1.4).

### News and Events pages (7 Nov 2026)

- The header's News and Events open their own pages, `/news` and `/events` (before, they jumped to the Home lists).
- News: a newspaper front page. The date line, "AI news" over `$ helper2 news --7d · N`, site chips, one lead
  headline, six under it in two columns, a Latest column of five, then the rest of the week in three columns. Headlines, site and date only:
  no excerpt until the web-search provider confirms in writing that we may show one. Nothing on it is AI-written.
- Events: the next year, grouped by month. A date column, the kind in accent ink, the title, where, and a pill
  ("On now", "Today", "In 2 days"). Kind and format chips, only with the values the events have. A row opens the
  resource page.
- On Home, New in the catalog and Coming up move like a news ticker (principle 7).

### Radar (5 Nov 2026)

- No line above the title. The title is one `h1` on two lines: "helper2.ai" in accent ink, then "The metasearch for the AI universe."
- Like a navigation radar: full rings around the title, and a beam that turns once every 5 s.
- Only external sources. Our own index, the search by meaning and the curated list are not on it. The legend
  counts the same sources.
- 5 to 10 sources at a time, each in its own spot. It starts with 5, one after another. Then every 1.6 s one step: the
  next source of the queue comes while it grows, up to 10; the oldest leaves while it shrinks, down to 5. The queue
  holds every source, shuffled once; a source that leaves goes to the back, so every source of a group is on the
  radar once before any of them comes back.
- A dot shows only when the beam passes it: full opacity, a short pop and a glow in its colour, then it fades to
  about a fifth until the next pass. A source that leaves is not painted again and fades out within 3 s.
- Every dot is filled with its colour: orange answering, amber slow or limited, red failed.
- Only the short name under a dot. The timing and the state are in its accessible name and tooltip.
- The beam and the dots hold still while the mouse is over the radar or the focus is on a dot. Under reduced motion,
  or with the account setting off, the beam stands still and a fixed set of 7 shows at full opacity.
- The dots come after the search box in the page: Tab reaches the box first.
- Under the box: "5-10 of N sources at a time", the three colours, and a link to the Sources page.

### Sources, Saved and the back office (5 Nov 2026)

- A `$ helper2 … · N` line next to each list heading, N from the API: `sources --all`, `saved`, `review --open`,
  `scouts --runs`, `examples`, `audit`.
- Sources is one table with every source, ours included. Same short names and colours as the radar. One line says
  the radar shows 5-10 of the external ones at a time.
- Saved keeps its rows and chips. Loading, sync error and empty are the states of the Home lists.
- The review queue, the Scout runs, the budgets and the audit trail are tables. Only the look changed.

## `system/` — the Helper2.ai design system

One system for every feature. Start with [system/README.md](system/README.md) (principles, content rules, color roles, type tags, logo, pages, breakpoints, accessibility, components).

| Path | What |
|---|---|
| `tokens.json` | The one source of the tokens: colors (light and dark), type and the type scale, spacing, radius, sizes, shadows, layers, breakpoints. `frontend/src/styles/tokens.css` writes them under plain names (`--font-mono`, `--font-body`, `--font-code`, `--box-height`), `frontend/src/lib/breakpoints.ts` the breakpoints; `frontend/src/styles/tokens.test.ts` keeps them in step. |
| `assets/Logos/radar-mark.svg` | The radar mark |

Mock content in brackets, such as `[stated date]`, stands for data the app fills in from sources. Nothing in brackets is a real value.
