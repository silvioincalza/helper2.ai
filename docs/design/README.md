# Helper2.ai design

Snapshot of the design work, saved next to the code. The live, editable version is a private design canvas, **Helper2.ai**, with three pages: Design system, Prototype & website, App screens.

## The redesign (November 2026)

Minimal and editorial: Geist Mono for the interface, Geist for reading, the radar around the home title, New this
week and Coming up as two `ls -l` tables (tabs on phones). Every page, its states and the dark theme are drawn, on a
private canvas.
Snapshot: [`redesign/`](redesign) (one `.dc.html` per board,
`canvas.json` lays them out). The rules are in the design system, version 4: [system/README.md](system/README.md).
The code moves to it page by page ([ADR 0042](../adr/frontend/0042-redesign-design-system-v4.md)): the header, the
footer, Home, Results (list and timeline), the resource page, Sources, Saved, the back office and the text pages
(Mission, Privacy, Terms, Not found, Sign in, Account) are built; the boards below are the reference.

### Results and the resource page (5 Nov 2026)

- One column, no preview. A row opens the resource page; Back comes back to the same place in the list.
- "All results" over `$ helper2 search · N` (`--timeline` on the timeline), dashed rules between rows.
- A row: type · host · date, the guide pick, the title, the description, "From" the sources.
- Guide picks stay where the search put them: a picked row shows "Guide pick #1", its reason (AI-written) and its
  quote highlighted in the source's words. The chips in the picks bar jump to the rows.
- Type tabs: All, the top 4, More. Filters in place on a wide screen, a drawer on a phone.
- Some sources did not answer: one quiet line under the heading, "2 of 5 sources did not answer · Which?". Try again
  only when a retry can help.
- "Best for" (All, Apps I can use, Build with AI) sits with the use case chips under the type tabs.
- The resource page: one primary button (Open), Save, Set it up only for things you install or run.
- Show more under the list adds the next page in place; the page is in the URL, so Back and a reload keep it.
- Timeline: the results with no stated date are one folded group at the end, "Date unknown · N · Show them".
- The resource page: the date line under the title (type · host · the row's date), and "Where this information
  comes from" folded, with each source, its mode and when it was read.
- No "Ask the guide about …" bar over the list: the box has the button. An empty result keeps "Ask the guide
  instead".

| Mockup | What |
|---|---|
| `TechLists.html`, `.png` | New this week and Coming up in three styles: log, tabs, `ls -l` table. Option 3 (tables) was chosen for wide screens, option 2 (tabs) for phones |
| `MobileLists.html`, `.png` | The two lists on a phone in four styles: plain list, swipe cards, tabs, date rail. Tabs were chosen |

The mockups load `Geist.woff2` and `GeistMono.woff2` from their own folder, which the repo does not hold; without
them they show the system fonts.

### Radar (5 Nov 2026)

- No line above the title. The title is one `h1` on two lines: "helper2.ai" in accent ink, then "The metasearch for the AI universe."
- Like a navigation radar: full rings around the title, and a beam that turns once every 5 s.
- Only external sources. Our own index, the search by meaning and the curated list are not on it. The legend
  counts the same sources.
- 3 to 5 sources at a time, each in its own spot. It starts with 3, one after another. Then every 1.6 s one step: a
  new random source comes while it grows, up to 5; the oldest leaves while it shrinks, down to 3.
- A dot shows only when the beam passes it: full opacity, a short pop and a glow in its colour, then it fades to
  about a fifth until the next pass. A source that leaves is not painted again and fades out within 3 s.
- Every dot is filled with its colour: orange answering, amber slow or limited, red failed.
- Only the short name under a dot. The timing and the state are in its accessible name and tooltip.
- The beam and the dots hold still while the search box has focus. Under reduced motion, or with the account setting
  off, the beam stands still and a fixed set of 4 shows at full opacity.
- Under the box: "3-5 of N sources at a time", the three colours, and a link to the Sources page.

### Sources, Saved and the back office (5 Nov 2026)

- A `$ helper2 … · N` line next to each list heading, N from the API: `sources --all`, `saved`, `review --open`,
  `scouts --runs`, `examples`, `audit`.
- Sources is one table with every source, ours included. Same short names and colours as the radar. One line says
  the radar shows 3-5 of the external ones at a time.
- Saved keeps its rows and chips. Loading, sync error and empty are the states of the Home lists.
- The review queue, the Scout runs, the budgets and the audit trail are tables. Only the look changed.

## `boards/` — the agreed design (ADR 0040)

The boards the frontend follows, saved from the design canvas, agreed on 30 October 2026. Each `.dc.html` is one board; `canvas.json`
lays them out. Their colours and fonts are the design system's; the code takes them from `tokens.css` and the
self-hosted fonts, never from the boards' hex values or Google Fonts links.

| Board | What |
|---|---|
| `Mix` | Home as agreed in October: radar hero, the box, examples, moving News and Events boards. Replaced by the redesign below |
| `ResultsPreview` | Results: type tabs, list, guide picks bar, preview panel, sources grid. Replaced by the redesign: no preview panel |
| `GuideArrival` | When the guide answers: option C (badges in place, reserved picks bar) was chosen |
| `Resource` | Resource detail |
| `States` | Loading, guide answering, empty, error, 404 |
| `Saved`, `Sources`, `Mission`, `Privacy` | The other pages |

## `system/` — the Helper2.ai design system

One system for every feature. Start with [system/README.md](system/README.md) (principles, content rules, color roles, type tags, logo, pages, breakpoints, accessibility, components).

| Path | What |
|---|---|
| `tokens.json` | Colors (light and dark), type, spacing, radius, sizes, version 4. `frontend/src/styles/tokens.css` holds the same tokens under plain names (`--font-mono`, `--font-body`, `--font-code`, `--box-height`). |
| `components/<Name>/README.md` | Guidelines per component: Header, Omnibox, Button, Pill, Badge, ResultCard, FactList, GuidePick, Banner, SourceStatus, Footer, Cover |
| `components/<Name>/preview.html` | Live preview markup (needs the artifact's generated `tokens.css`) |
| `components/bundle.css` | The `h2-` component classes |
| `assets/Logos/radar-mark.svg` | The radar mark |

Mock content in brackets, such as `[stated date]`, stands for data the app fills in from sources. Nothing in brackets is a real value.
