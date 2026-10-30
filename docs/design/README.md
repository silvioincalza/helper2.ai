# Helper2.ai design

Snapshot of the design work, saved next to the code. The live, editable version is one private Claude artifact, the design canvas **Helper2.ai**: https://claude.ai/artifact/87cNjechmtwvAm5snQeCtv. It has three pages: Design system, Prototype & website, App screens.

## `system/` — the Helper2.ai design system

One system for every feature. Start with [system/README.md](system/README.md) (principles, content rules, color roles, type tags, logo, pages, breakpoints, accessibility, components).

| Path | What |
|---|---|
| `tokens.json` | Colors (light and dark), type, spacing, radius, sizes. The frontend's `frontend/src/styles/tokens.css` mirrors it. |
| `components/<Name>/preview.html` | Live preview markup (needs the artifact's generated `tokens.css`) |
| `components/bundle.css` | The `h2-` component classes |
| `assets/Logos/radar-mark.svg` | The radar mark |

## `canvas/` — design canvas boards

Each `.dc.html` is one board; `canvas.json` lays them out. They render inside the Design artifact, not as standalone pages.

| Board | What |
|---|---|
| `DS-Brand`, `DS-Colors`, `DS-Type`, `DS-Components` | Design system page: brand book, every color in light and dark, type scale with spacing and radius, all 11 components |
| `One-Page` | Clickable prototype: one page, one box (search + guide + What's new) |
| `Website` | One-page marketing website |
| `S-Header` | Shared header used by every screen |
| `S-Home` … `S-Admin` | Mockups of every app screen: Home, Search results, Guide answer, Resource page, Saved, Sources, Admin |
| `S-Phone-Home` | Home at phone width (390 px) |

Mock content in brackets, such as `[stated date]`, stands for data the app fills in from sources. Nothing in brackets is a real value.
