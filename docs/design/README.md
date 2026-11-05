# Helper2.ai design

Snapshot of the design work, saved next to the code. The live, editable version is a private design canvas, **Helper2.ai**, with three pages: Design system, Prototype & website, App screens.

## The redesign (November 2026)

Minimal and editorial: Geist Mono for the interface, Geist for reading, the radar behind the home title, New this
week and Coming up as two `ls -l` tables (tabs on phones). Every page, its states and the dark theme are drawn, on a
private canvas.
Snapshot: [`redesign/`](redesign) (one `.dc.html` per board,
`canvas.json` lays them out). The rules are in the design system, version 4: [system/README.md](system/README.md).
The code moves to it page by page ([ADR 0042](../adr/frontend/0042-redesign-design-system-v4.md)): the header, the
footer and Home are built; the other pages still follow the boards below.

| Mockup | What |
|---|---|
| `TechLists.html`, `.png` | New this week and Coming up in three styles: log, tabs, `ls -l` table. Option 3 (tables) was chosen for wide screens, option 2 (tabs) for phones |
| `MobileLists.html`, `.png` | The two lists on a phone in four styles: plain list, swipe cards, tabs, date rail. Tabs were chosen |

The two mockups load `Geist.woff2` and `GeistMono.woff2` from their own folder, which the repo does not hold; without
them they show the system fonts.

## `boards/` — the agreed design (ADR 0040)

The boards the frontend follows, saved from the design canvas, agreed on 30 October 2026. Each `.dc.html` is one board; `canvas.json`
lays them out. Their colours and fonts are the design system's; the code takes them from `tokens.css` and the
self-hosted fonts, never from the boards' hex values or Google Fonts links.

| Board | What |
|---|---|
| `Mix` | Home as agreed in October: radar hero, the box, examples, moving News and Events boards. Replaced by the redesign below |
| `ResultsPreview` | Results: type tabs, list, guide picks bar, preview panel, sources grid |
| `GuideArrival` | When the guide answers: option C (badges in place, reserved picks bar) was chosen |
| `Resource` | Resource detail |
| `States` | Loading, guide answering, empty, error, 404 |
| `Saved`, `Sources`, `Mission`, `Privacy` | The other pages |

## `system/` — the Helper2.ai design system

One system for every feature. Start with [system/README.md](system/README.md) (principles, content rules, color roles, type tags, logo, pages, breakpoints, accessibility, components).

| Path | What |
|---|---|
| `tokens.json` | Colors (light and dark), type, spacing, radius, sizes, version 4. `frontend/src/styles/tokens.css` holds version 3 and, as `--v4-*`, version 4: the header, the footer and Home use version 4, the other pages version 3 until they move. |
| `components/<Name>/README.md` | Guidelines per component: Header, Omnibox, Button, Pill, Badge, ResultCard, FactList, GuidePick, Banner, SourceStatus, Footer, Cover |
| `components/<Name>/preview.html` | Live preview markup (needs the artifact's generated `tokens.css`) |
| `components/bundle.css` | The `h2-` component classes |
| `assets/Logos/radar-mark.svg` | The radar mark |

Mock content in brackets, such as `[stated date]`, stands for data the app fills in from sources. Nothing in brackets is a real value.
