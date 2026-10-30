# Helper2.ai

The design system for every part of Helper2.ai: the search-and-ask box, results, the AI guide, the News and Events boards, resource pages, saved resources, the sources page and admin. The look is warm paper with one saffron accent, a geometric display face for headings, a sans for doing things, and a mono for names that came from a source. The reference design is the v3 canvas **helper2.ai — UI redesign proposals** (boards Home and Results — list + preview), agreed on 30 October 2026.

## Principles

1. **One entry point.** One box: the hero of the home page, in the header everywhere else. Typing searches live; one button changes its job with the text (a few words search, four or more ask the guide), and Shift+Enter always asks. Never add a second search field, a scope switch or a mode switch.
2. **Show where every fact came from.** Each result, card, fact and pick names its source. A value no source stated reads **Unknown**, in `not-stated`. Dates are the source's own; otherwise "First seen" with the date Helper2.ai first saw it.
3. **Checked versus not checked.** A guide quote the validator matched word for word sits on `quote-bg` with a **Quote checked** badge (`checked-bg` / `checked-ink`). The model's own reason is always labeled "Model's explanation (not checked)" and is never styled like the quote.
4. **State in words, not color alone.** Every status (source answered, cached, rate-limited, failed; verified, conflicting, rejected) carries a word and an icon. Color only repeats it.
5. **Editorial voice, tool behavior.** Space Grotesk (`display`) for page and section headings, feed titles and quotes. Inter (`sans`) for every control and all running text. JetBrains Mono (`mono`) for resource names and source status.
6. **Quiet surfaces, one accent.** Saffron `accent` marks the one primary action per area. Selection uses `selected` (ink), so state and action never look alike.

## Content fundamentals

- Write the way a knowledgeable colleague talks: "Typing searches every source", "No results. Try fewer words, or ask the guide in plain words."
- Buttons say what happens: **Search**, **Ask the guide**, **Save**, **Copy prompt**, **Try again**.
- Errors say what went wrong and what to do next, without apology.
- Never invent popularity, dates or facts. Mock content uses brackets: `[stated date]`.
- Metadata reads source first, then date: "GitHub · Oct 21, 2026".
- English UI; the guide accepts any language.

## Visual foundations

| Role | Token | Use |
|---|---|---|
| Page | `bg` | Everything sits on it. |
| Objects | `surface` + `border` | Cards, lists, panels. `radius-xl` for cards, `radius-lg` for lists and panels. |
| Idle chips | `surface-tint` + `accent-deep` | Filter, topic and example pills. |
| Selected | `selected` + `on-selected` | The active pill or tab. |
| Primary action | `accent` + `on-accent` | One per area: Ask the guide, Copy prompt. |
| Links | `accent-ink` | Text links and text buttons. |
| Caution | `not-stated` | Unknown values, First seen dates, cached or rate-limited sources. |
| Error | `danger` | Failed sources and requests, rejected claims, form errors. |
| Sources grid | `source-tile-bg` + `source-tile-line`, dots in `status-ok` / `status-warn` | The sources that answered a search, under the results. |
| Status pills | `pill-ok-*`, `pill-warn-*`, `pill-next-*`, `pill-later-*` | Updated or Published, First seen, the next event, later events. |

- **Spacing:** 4-based steps `space-1` to `space-7` (4, 8, 12, 16, 24, 32, 48px). Pills `space-2` apart, card parts `space-3`, page sections `space-5`, gutters `space-6`, the home hero `space-7`.
- **Controls:** at least `target-min` (44px) tall. The search box and its buttons are `box-height` (56px).
- **Focus:** a 3px `focus` outline with 2px offset on every control.
- **Motion:** `motion` (160ms) for hover and state changes, `radar-sweep` for the hero radar. A News or Events board steps up one row every 4 s (`ROW_MS` in `NewsBoard`) instead of looping, so it has no motion token. Under `prefers-reduced-motion` nothing moves.
- **Elevation:** none. Objects are set apart by `border`, never by shadow.
- **Width:** the page column stops at `content-wide` (1240px), with 32px gutters, 16px below `bp-small`.
- **Themes:** light and dark; every token has both values. The header button cycles system → light → dark: "system" follows the device setting and is the default.

## Type tags

Every resource carries one type tag. Fill and text differ in lightness, not just hue.

| Type | Fill | Text |
|---|---|---|
| Paper | `tag-paper-bg` | `accent-deep` |
| Model | `tag-model-bg` | `tag-model-ink` |
| News, Article | `tag-news-bg` | `tag-news-ink` |
| MCP server | `tag-mcp-bg` | `checked-ink` |
| Library | `tag-paper-bg` | `accent-deep` |
| Product, Agent, Prompt, Skill, Dataset, Event, Other | `tag-other-bg` | `ink-2` |

The tag text is the type name in `mono`, 11px, uppercase, 0.06em tracking, 5px by 8px padding, 6px corners.

## Logo

The mark is an ink-stroke radar (outer ring, faint inner ring, one sweep line, one filled four-point AI spark: "AI on your radar") on a 40px `accent` tile with 12px corners (`assets/Logos/radar-mark.svg`). In the header it sits 16px (`space-4`) left of the wordmark "helper2.ai", set in `display` at 32px in `ink`. Under the wordmark, 2px below, sits the tagline **AI ON YOUR RADAR**: `sans` 12px, weight 600, uppercase, 0.08em tracking, in `muted`. The radar strokes and spark follow `on-accent` (ink), on the `accent` tile in both themes. Never recolor the radar, add effects, or drop the wordmark in the header.

## Iconography

Apart from the radar mark, icons are small 14px stroke glyphs that sit beside words, never alone: check, cross, clock, pause, warn, info, bookmark, external link, refresh. Use `→` for "go further" and `↗` for an external source. Never emoji. An icon-only button (close `×`) carries an `aria-label`.

## Home page

Home only: the hero, then two boards. The results live on their own page.

1. **Hero** on `hero-bg`: radar rings, slow sweep and blips behind, the mark and wordmark, the eyebrow **AI ON YOUR RADAR**, the two-part `display` title (second half in `accent-ink`), the search box and three example Pills. The search and the examples open the results page.
2. **News** and **Events**, side by side, one column below `bp-two-col`. See NewsBoard.

No source strip on the home page. It belongs to a search.

## Results page

One list, one preview.

- **Header:** logo with tagline, the search box, theme and Saved buttons. Under it the type tabs with counts.
- **List** (left, at most `list-max`): result rows in search order; a guide pick gets a "Pick #n" badge in its row. The selected row has a `row-selected-line` outline.
- **Preview** (right): type tag and path, the title in `display`, the actions, then four parts with a mono section label each: Why it matches, Facts, Listed by, Timeline.
- **Sources grid** under everything, then the footer.
- Below `bp-split` the preview panel hides. The selected row opens an inline preview under it (proof quote, three facts, Open and Full details). Full details goes to `/resource/:id`.

## Layout and breakpoints

| Token | Width | What changes |
|---|---|---|
| `bp-two-col` | 1000px | News and Events stack. |
| `bp-split` | 860px | Results: the preview panel hides, the inline preview takes over. |
| `bp-small` | 640px | Gutter 16px. The search box takes its own row under the logo. Action buttons share the row. |
| `bp-phone` | 560px | Boards get shorter (340px) and rows get less padding. |

Layout is flex and grid with `gap`. No fixed heights except the boards.

## Accessibility

- **Moving content.** The News and Events boards scroll by themselves, one row at a time. They pause on hover, on keyboard focus and on tap (tap again to play), and a visible Pause/Play button under each board does the same (WCAG 2.2.2). Under `prefers-reduced-motion` nothing moves, there is no button, and the board is a normal scrollable list. The radar sweep stops too.
- **Targets:** every control is at least `target-min` (44px). Calendar days are 40px; they sit in a grid with 2px gaps.
- **One copy for assistive tech.** Each board row is in the list once, and the list is not a live region: a screen reader reads a normal list.
- **Headings:** one `h1` per page. On the results page it is "Results for …" (visually hidden); the preview title is an `h2`.
- **AI-written text is labelled** (EU AI Act Art. 50). The guide's reason sits next to a **Quote checked** badge or the "Model's explanation (not checked)" label, never alone. The HTML marks it machine-readable, as ADR 0038 says.
- **Colour is never alone.** Every pill has a word.

## Components

Components are plain HTML plus the classes in `components/bundle.css` (prefix `h2-`). There is no JavaScript bundle. The folders in `components/` hold a live preview for Header, Omnibox, Button, Pill, Badge, ResultCard, FactList, GuidePick, FeedRow, Banner, SourceStatus, Footer and Cover; the table below and the v3 canvas are the reference where a preview differs.

| Component | Used for |
|---|---|
| **Header** | Logo with tagline, the search box, theme and Saved buttons. Type tabs under it on the results page. 72px min height. |
| **Footer** | One row: the one claim "No ads.", then Mission, Sources, Privacy, Terms, GitHub, the licence and the release version. |
| **Omnibox** | The search box. A 999px pill, 52px (58px in the hero), search icon, input, saffron **Search** button. No keyboard-shortcut hint unless the shortcut works. |
| **TypeTabs** | All, MCP servers, Libraries, Models, Articles, each with a mono count. Active tab: ink text, 3px `accent` underline. Scrolls sideways on phones. |
| **Button** | 44px high, 999px corners, label on one line. Primary in `accent`. An external link ends with `↗`. |
| **Pill** | Example needs and filters. |
| **Badge** | Type tags, Quote checked, verification. |
| **StatusPill** | `mono` 11px uppercase pill: ok (Updated, Published), warn (First seen), next (the next event, outlined in `accent`), later (outlined in `border-strong`). |
| **ResultRow** | One row in the result list: tag, domain, Guide pick label, title in `display` 18px, the proof quote cut at two lines. A real button; selected has an `accent` outline. |
| **ResultPreview** | The right panel: title, actions (Open on source ↗, Save, Guide me through setup when the type has a setup), Why it matches, Facts, Listed by, Timeline. |
| **InlinePreview** | The phone version of ResultPreview, under the selected row: proof, three facts, Open and Full details. |
| **ProofQuote** | The source text on `proof-bg`, with the checked sentence on `proof-mark`, and a mono caption naming the source. |
| **FactList** | Two groups, Use and Build, as a `dl`. A value no source stated reads **Not stated** in `not-stated`, italic. |
| **GuidePick** | One of the guide's picks with its checked quote. |
| **NewsBoard** | News or Events on the home page. 2px `ink` rule on top, `display` heading, a short subtitle. Each row: date in mono `accent-ink`, type tag, source or place, a StatusPill on the right; the name under it in `display`, two lines at most, linking to the resource page. The rows move up one row every 4 s, back to the top at the end, with a Pause/Play button under the board. |
| **SourcesGrid** | Under the results: one mono line "fan-out · N sources · N hits → N after merge", then a tile per source with a status dot, the name and the time or cache age. |
| **Banner** | Notices, partial results, empty and error states. |

### In the frontend

The shared React components are in `frontend/src/components/ui/`, the results page's parts in `frontend/src/components/`
(ADR 0040). Where the code name differs:

| Design system | Code |
|---|---|
| Badge (type tag) | `TypeTag` |
| ResultRow | `ResultList` (the rows), `SelectableRow` (the row button and its selected outline) |
| ResultPreview, InlinePreview | `ResultPreview`, `InlinePreview` |
| FactList | `FactList` in `CardFacts` |
| SourcesGrid | `SourcesSummary` (the fan-out line, the web searches left), `SourceTiles` (the tiles) |
| Guide picks bar | `PicksBar` (the box), `GuidePicks` (the guide's answer in it) |

`StatusPill` and `ProofQuote` keep their names.

## Guide picks bar (results page)

The bar sits above the result list from the first render. While the guide works it says "Guide is reading N candidates… picks will appear here". When the answer arrives, the same box shows the picks as chips: #1, #2, #3. It is labelled AI-written.
The list never moves. Each pick gets a "Pick #n" badge and its reason in its own row.
No pick: the bar shrinks to one line, "No pick: no result had a sentence that proves it".
Keep the bar's height reserved, so nothing jumps when the answer arrives.

## Selected result in the URL

The URL keeps the search and the selected result: `?q=…&id=…`. It works on `/` and `/search`.
Change it with replaceState, so Back leaves the search and does not step through rows.
If the id is not in the results, select the first row. On phones the selected row opens the inline preview, with
Full details to `/resource/:id`.
