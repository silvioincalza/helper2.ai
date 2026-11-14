# Helper2.ai

The design system for every part of Helper2.ai: the search box, results, the AI guide, New in the catalog and Coming up, resource pages, saved resources, sign-in, the sources page and admin. From the November 2026 redesign ([ADR 0040](../../adr/frontend/0040-design-system.md)): minimal and editorial, warm paper with one saffron accent, one tech font for the interface and a calm font for reading. The reference is the redesign canvas, saved in [../redesign](../redesign). Every page, its states and the dark theme are drawn there.

[`tokens.json`](tokens.json) is the one source of the tokens. `frontend/src/styles/tokens.css` writes them as CSS variables under plain names (`--font-mono` Geist Mono, `--font-body` Geist, `--font-code` the system monospace for code, `--box-height`), and `frontend/src/lib/breakpoints.ts` the breakpoints. A test (`frontend/src/styles/tokens.test.ts`) fails when they differ, when a colour is written by its hex value anywhere else, or when a media query uses a width that is not a breakpoint. In the public CSS it also fails on a font size, a `z-index` or a corner radius written as a number, and on `rgba()`. The list pages (Sources, Saved, the back office, Results) use the terminal style. The text pages stay prose: Geist for headings and reading text, Geist Mono only for small interface labels, no terminal look (`features/pages/text.css`). On every page, Geist for what a person reads or presses, Geist Mono for machine facts (principle 5).

## Principles

1. **One entry point.** One search box: in the middle of the home page, in the header everywhere else. Two buttons, always: **Search** (primary, Enter) and **Ask the guide** (outline, Shift+Enter; its icon and its words, **Ask** up to 720px). The guide runs only when the user asks for it. Never add a second search field, a scope switch or a mode switch.
2. **Show where every fact came from.** Each result, fact and pick names its source. A value no source stated is **not stated**, in `status-warn-ink`, grouped in one line. Dates are the source's own; otherwise "First seen" with the date Helper2.ai first saw it.
3. **Checked versus not checked.** A guide quote the validator matched word for word sits on `quote-bg` under "Checked word for word in the source". The model's own reason carries its own "AI-written reason" label and is never styled like the quote.
4. **State in words, not color alone.** Every status (answered, slow, rate limited, timed out, failed, sources disagree) has a word and an icon: ✓ ! × ○ …. Color only repeats it.
5. **One tech font, one reading font.** Geist Mono for machine facts: the logo, meta lines, counts, `$ helper2` commands, key hints and table cells (the two home lists, the list pages). Geist for everything a person reads or presses: running text, headings, titles, buttons, chips, tabs, links and fields.
6. **Quiet surfaces, one accent.** Saffron `accent` marks the one primary action per area. Selection uses `selected` (ink), so state and action never look alike.
7. **Nothing moves on its own for more than 5 seconds, with two exceptions.** No CSS animation repeats, no line prints in, no cursor blinks. The first exception: the home radar, like a navigation radar. It holds still while the mouse is over it or the focus is on one of its dots, stands still under reduced motion, and a signed-in user can turn it off. The second: New in the catalog and Coming up move like a news ticker, one row every 4 seconds. Hover, focus or a tap holds them; on a phone, so does a scroll of the page; reduced motion and the radar's switch stop them.

## Content fundamentals

- Write the way a knowledgeable colleague talks: "Enter searches. Shift+Enter asks the guide for picks with proof."
- Buttons say what happens: **Search**, **Ask the guide**, **Save**, **Remove**, **Send report**, **Try again**.
- Errors say what went wrong and what to do next, without apology.
- Never invent popularity, dates, limits or facts. Mock content uses brackets: `[stated date]`, `[Numbers set in the limits ADR]`.
- Every count comes from data: "10 of 12 sources answered", never a number typed into the page.
- Metadata reads type first, then source, then date: "Library · github.com · updated Oct 2026".
- Proper names keep their real case, also in the lists.
- English UI; the guide accepts any language.

## Visual foundations

| Role | Token | Use |
|---|---|---|
| Page | `bg` | Everything sits on it. |
| Objects | `surface` + `border` | Cards, panels, callouts, the sign-in card. |
| Controls | `border-strong` | Inputs, selects, ghost buttons, chips, toggles. 3:1 on `bg`. |
| Selected | `selected` + `on-selected` | The active chip or view (List, Timeline). |
| Primary action | `accent` + `on-accent` | One per area: Search, Open on source ↗, Send report. |
| Links | `accent-ink` | Text links, dates, ranks, "helper2.ai" in the home title. Saffron `accent` is never used for small text on `bg` (3:1 only). |
| Checked | `quote-bg`, `status-ok-ink` | Checked quotes, answered sources. |
| Caution | `status-warn-ink` | Not stated, sources disagree, slow, rate limited, timed out. |
| Error | `danger` | Failed sources and requests, form errors, delete. |
| Radar | `ring`, `accent`, `status-warn-ink`, `danger` | The radar around the home title: rings, beam, filled dots. |
| Terminal panel | `term-bg`, `term-bar`, `term-text`, `term-muted`, `term-line`, `term-control` | The guide's picks. Dark in both themes, so nothing on it uses `ink`, `surface` or `border-strong`: text is `term-text`, lines are `term-line`, a button is `btn-on-term`. |

- **Type:** every font size is a step of the type scale below, in rem. See Type scale.
- **Spacing:** 4-based steps `space-1` to `space-7` (4, 8, 12, 16, 24, 32, 48px). A padding, margin or gap that equals a step is written with the token. Values between the steps (1, 2, 3, 6, 10, 14px) are still numbers.
- **Controls:** at least `target-min` (44px) tall, also chips, toggles and the `$ … --all` buttons. The home search box is `box-height` (60px).
- **Focus:** a 3px `focus` outline with 2px offset on every control. The ring keeps the corner radius of its control: no rule changes a radius on focus. In a box that clips (`overflow: hidden`), the ring is drawn inside (`outline-offset: -3px`, List and Timeline), in `on-selected` on the selected item, or the box leaves room for it (the home examples, the chip rows on the results page: padding with the same negative margin). `scroll-padding-top` and `scroll-padding-bottom` on `html` keep a focused item, and a link target, clear of what sticks: they read `--sticky-top` (the header, the header search row, the pinned home box) and `--sticky-bottom` (the consent bar), which `useStickyEdge` measures. A new sticky or fixed bar registers there.
- **Radius:** `radius-xs` 4px (key hints, the highlight in a quote, loading lines, the focus shape of a title link), `radius-sm` 8px, `radius-md` 10px (buttons, fields), `radius-lg` 14px (the search box, lists), `radius-xl` 18px (cards), `radius-pill` (pills, tags, thin bars). A circle is `50%`. `radius-mark` (12px) is the logo tile only. A test fails on a corner written as a number.
- **Elevation:** three shadows only: `shadow-pinned` (the pinned search box), `shadow-overlay` (dialogs, the filters drawer, the account panel, the phone menu, the More types list), `shadow-bar` (the consent bar, the shadow goes up). Everything else is set apart by `border`. Each shadow has a light and a dark value: ink on paper, black on the dark theme. Ink is lighter than the dark page, so an ink shadow cannot be seen there.
- **Scrim:** `scrim` dims the page behind a dialog (the filters drawer): ink at 40% on paper, black at 60% on the dark theme. No other colour with an alpha is written outside `tokens.css`; a test fails on `rgba()`.
- **Weights:** 400, 500, 600, 700. Headings are 600.
- **Layers:** every `z-index` is a `--z-*` token, named by what it is. See Layers.
- **Width:** the page column is 1120px wide with its gutters: `content-wide` (1088px) plus 16px on each side, on every page, the header and the footer included. One-column pages stop at `reading-max` (808px).
- **Themes:** light and dark; every token has both values. The footer button switches light ↔ dark; light is the default.

## Type scale

Every font size in the public CSS is a `--text-*` token. A test fails on a size written as a number (px or rem). The steps are in rem, so text follows the font size the user set in the browser (WCAG 1.4.4). A step is named by its size in px at the default 16px. Nothing is smaller than `text-12`.

| Token | Size | Use |
|---|---|---|
| `text-12` | 12px | The floor. Eyebrow labels, AI labels, column heads, key hints, counts in chips, the tagline, status pills. |
| `text-13` | 13px | Meta lines, the home and list tables, command lines, chips with a mouse. |
| `text-14` | 14px | Small running text, buttons with a mouse, filter rows, notes. |
| `text-15` | 15px | Buttons, fields, facts, footer links, row descriptions. |
| `text-16` | 16px | Body text. |
| `text-17` | 17px | Reading text on text pages, quotes, result titles, the search field. |
| `text-18` | 18px | Ledes of the standalone pages, the phone menu links, the wordmark up to 420px. |
| `text-19` | 19px | The lede of a text page, saved and timeline titles. |
| `text-20` | 20px | The wordmark, list headings, second stories. |
| `text-22` | 22px | Card headings: sign in, account, dialog. |
| `text-24` | 24px | Section headings (`h2`). |
| `text-28` | 28px | The results heading. |

Titles grow with the window. Each is one `clamp()`:

| Token | Size | Use |
|---|---|---|
| `text-hero` | 30px to 52px | The home title. |
| `text-title` | 28px to 44px | The `h1` of a text page or a list page. |
| `text-title-lg` | 28px to 48px | The `h1` of Sign in, Not found and the error page. |
| `text-title-plain` | 32px to 48px | An `h1` no page style sets (Support). |
| `text-title-resource` | 28.8px to 41.6px | The resource title. |
| `text-title-state` | 30.4px to 41.6px | A StatePanel that is the `h1` of its page. |
| `text-title-story` | 28px to 40px | The lead story on News. |

A size relative to its parent stays relative: `code` is 0.85em of the text around it, and never under `text-12`. A line height written with a font size is a ratio or in rem, never px, so it grows with the text.

Three text styles repeat. Use the class; where a rule needs a stronger selector, use the token (`font: var(--type-eyebrow)`).

| Style | Class | Token | Look |
|---|---|---|---|
| Eyebrow | `.eyebrow` | `type-eyebrow` | Geist Mono 600, `text-12`, uppercase, 0.08em letter spacing, `muted`. The small label above a group. |
| Meta line | `.meta-line` | `type-meta` | Geist Mono 400, `text-13`, `muted`. Source, date, type, count. |
| AI label | `.ai-label` (`AiLabel`) | | Geist Mono 500, `text-12`, outlined. See AiLabel in Components. |

A new size is a new step in `tokens.json` first. The scale is the sizes the screens used when it was written, rounded to the nearest step; it is not a modular scale.

## Layers

What covers what, the lowest first. Every `z-index` in the public CSS is one of these tokens; a test fails on a number.

| Token | Value | What |
|---|---|---|
| `z-raised` | 1 | A link or a button inside a row whose title link covers the whole row: the host and Save in a result row. |
| `z-list-columns` | 1 | The column names of a home list while the list scrolls under them. |
| `z-list-head` | 2 | The head of a home list, and the two tabs on a phone. |
| `z-popover` | 5 | A small list that opens under its button: More types on the results page. |
| `z-pinned` | 10 | The pinned home search box. |
| `z-header` | 20 | The sticky header. |
| `z-menu` | 30 | A menu that opens over the page. |
| `z-consent` | 50 | The consent bar. |
| `z-skip-link` | 100 | Skip to main content, when it has the focus. |

A dialog (the filters drawer, Delete account) is a `<dialog>`: the browser puts it in its top layer, above all of these, so it has no `z-index`. A new sticky or fixed part takes a token from this list, or adds one in its place in the order.

## Type tags

Type is a word, not a coloured badge. In lists and on resource pages it is the first word of the meta line, in `ink-2`, weight 600. In the home lists it is the TYPE column (`model`) or `[model]` in a phone log row, in `accent-ink`. The type names are the API's: Product, Model, Agent, Prompt, Skill, MCP server, Library, Dataset, Article, Paper, News, Event, Course, Other.

## Logo

The mark is an ink-stroke radar (outer ring, faint inner ring, one sweep line, one filled four-point AI spark) on a 40px `accent` tile with 12px corners (`assets/Logos/radar-mark.svg`). It sits 14px left of the wordmark "helper2.ai" in Geist Mono 20px, weight 600. Under it, 4px below, the tagline **AI ON YOUR RADAR**: Geist Mono 12px, weight 600, uppercase, 0.1em tracking, in `muted`. Below 420px the tagline hides and the wordmark is 18px. Never recolor the radar, add effects, or drop the wordmark in the header.

## Iconography

Apart from the radar mark, icons are small stroke glyphs beside words, never alone (the spark on Ask the guide sits beside its words on every width): check, cross, warn, clock, external link, menu, close. Use `→` for "go further" and `↗` for an external source. Never emoji. An icon-only button (menu, close) carries an `aria-label`.

## Home page

1. **Hero**, centred, with no line above it: the title, one `h1` on two lines: "helper2.ai" in `accent-ink` (same size and weight), then "The metasearch for the AI universe."; one line on what it does. Around it the radar (see Radar).
2. **Pinned search box**, full width up to 820px: box, Search, Ask the guide, one hint line (the keys with a mouse; "Search, or tap Ask for picks with proof." on a touch screen), the "Try:" chips, the radar legend ("5-10 of N sources at a time", the three colours, a link to the Sources page). It sticks under the header while the page scrolls; once the title is gone, the hints and legend fold away and it gets `shadow-pinned`.
3. **New in the catalog** and **Coming up**: two tables side by side, like `ls -l`, stacked below 920px; tabs below 768px. See HomeLists.
4. **What helper2 does for you**: a quiet strip of three steps.

On phones (below 720px) the title and text fold away after 24px of scroll; the search box stays pinned; the lists scroll under it. Back at the top, everything opens again. News and Events move from the header into the menu.

## Results page

One column, `reading-max`.

- **Header:** logo, the search box with the query, Saved, Sign in. News and Events are not in this header.
- **The guide was asked:** "Recommended for you", then up to 3 PickCards, then "How the guide decided" (a disclosure: search terms, candidates, removed suggestions, models). A comparison table ("X vs Y") or a "What it is" explanation shows open between the picks and the disclosure, never inside it. Up to 520px the table is a stack of cards, one per aspect; it stays a table for a screen reader.
- **The guide was not asked:** no picks; **Ask the guide** stays next to Search in the box.
- **Guide states**, drawn where the picks sit: working (streamed steps), needs one more detail, no pick, AI model not available (top 3 by search score, no AI label because no AI wrote it), busy (503), timeout or stalled stream (504, 45 seconds), daily AI answers used up.
- **All results:** count, Filters (opens a panel: Type, Price, License, Updated, each with counts and a Not stated bucket), Sort (Best match, Newest, Recently updated), List or Timeline. A partial-results notice when some sources did not answer. Rows link to the resource page. Guide picks carry "Guide pick #n" in the meta line.
- **Coverage** under the list: "This search: N of M sources answered · cached 14 min ago", opening to one line per source with its outcome.
- The URL keeps the search and the filters: `?q=…&type=…`. Back leaves the search.

## Radar

Like a navigation radar. Full rings around the home title, and a beam (a sweep wedge) that turns once every 5 s.
Only external sources from GET /sources (`external`), each in a fixed spot from the order the API lists them, never
under the title or the box, never touching another name. Under each dot only its `shortName`.

| Encodes | How |
|---|---|
| Ring | The two inner rings: live sources. The two outer rings: the Scout collectors. |
| Last check | Every dot is filled. `accent` (orange): answering. `status-warn-ink` (amber): slow or limited. `danger` (red): failed. Each is 3:1 or more on `bg`. |
| Size | New this week from that source (`newThisWeek` on GET /sources; null keeps the default size). |

5 to 10 sources show at a time. It starts with 5, one after another (0.6 s apart). Then every 1.6 s one step: the
next source of the queue comes while it grows, up to 10; the oldest leaves while it shrinks, down to 5. The queue holds
every source, shuffled once; a source that leaves goes to the back, so every source of a group (live sources, collectors,
discovery categories, which take turns) is on the radar once before any of them comes back. A dot is painted only when
the beam passes it: full opacity, a pop (scale 1.35, back in 0.25 s) and a glow in its colour (0.6 s), then it fades
to 0.22 until the next pass. A new source shows the first time the beam passes it. A source that leaves is not painted
again and fades out within 3 s. Beam and dots run on one clock (`requestAnimationFrame`), which stops while the mouse
is over the radar or the focus is on a dot. A dot under the focus or the mouse shows at full opacity. Under reduced
motion, or with the account setting off, the beam stands still and a fixed random set of 7 shows at full opacity.

Each dot links to the Sources page; its accessible name and tooltip say the state ("GitHub, live, answered in 410 ms,
14 new this week"). A dot that is not on the radar is hidden from screen readers and the keyboard. The dots come after the search box and the
examples in the page, so Tab reaches the box first; they are drawn over the title area. The legend under
the box says "5-10 of N sources at a time", names the three colours next to a filled swatch, and links to the Sources
page, which shows every source as a table. Up to 920px only the rings and the beam show.

## Layout and breakpoints

Seven widths, in `tokens.json` (`breakpoints`) and nowhere else. A media query is "up to W" (`max-width: W`), "above W"
(`min-width: W + 1px`) or "from W" (`min-width: W`). One height, `bp-short` (480px, `heights` in `tokens.json`): up to
it (`max-height: H`) nothing sticks. The header, the pinned home box, the home list heads and the consent bar scroll
with the page, so on a phone on its side, or at 400% zoom, they cannot cover half the screen (WCAG 1.4.10).

| Width | Token | What changes |
|---|---|---|
| 1100px | `bp-desk` | From here the back office review page shows the item and its evidence side by side. |
| 920px | `bp-wide` | The two home tables stack; the radar shows only its rings and beam; the News front page takes one column; four-column steps become two. |
| 860px | `bp-split` | Above it, text pages show their contents beside the text. |
| 768px | `bp-tablet` | The two home tables become one panel with two tabs and log rows; the proof strip takes two columns. |
| 720px | `bp-small` | Gutter 16px. The header sticks with logo, Saved and Sign in only; News and Events move to the menu. The header search takes its own row. The home title folds on scroll. The results filters open as a drawer. Ask the guide says **Ask**. Steps and facts take one column. |
| 520px | `bp-phone` | Steps and tables become cards: the guide's comparison is one card per aspect, with each tool and its answer under the other. Saved rows and the timeline put the month above their items. The search box takes the full row; Search and Ask share the row under it, equal in width. The results put List / Timeline, Filters and the sort in one row, without the heading and the "Sort by" words; the sources warning takes its short text. |
| 420px | `bp-tiny` | The tagline hides; the wordmark is 18px; the account button shows only its avatar. |
| 359px | `bp-narrow` | The results sort takes its own row; Which? and Try again go under the sources warning. |

Layout is flex and grid with `gap`. Side menus wrap above the content on phones and are never sticky.

## Accessibility

- **Moving content.** No CSS animation repeats: the loading lines pulse 3 times, nothing prints in, no cursor blinks. A test in the frontend fails on any CSS or inline animation that repeats for ever. Two exceptions, both on one JavaScript clock: the home radar keeps turning (see Radar), and New in the catalog and Coming up move like a news ticker (principle 7). Neither has a pause button: stopping motion is a setting on the account page. The radar also holds still while the mouse is over it or the focus is on a dot; the ticker holds on hover, focus and a tap. Signed-in users can turn the radar off; under `prefers-reduced-motion` the beam stands still and 7 dots stay. Under `prefers-reduced-motion` nothing moves and nothing folds with animation.
- **Targets:** on a touch screen every control is at least 44px, also chips, toggles, tabs, Remove and admin buttons. With a mouse buttons are 36px and chips 32px (`control-sm`, `chip-sm`), above WCAG 2.2's 24px (2.5.8).
- **Contrast:** text 4.5:1, control borders and focus 3:1. Saffron `accent` text on `bg` is 3:1, so small saffron text uses `accent-ink` (5.5:1).
- **Headings:** one `h1` per page.
- **AI-written text is labelled** (EU AI Act Art. 50): each AI field has its own label ("AI-written", "AI-written reason", "AI summary") and its own `data-ai-generated="true"` and `data-ai-model`. Checked quotes and buttons are never inside a marked element.
- **Patterns:** the account panel is a disclosure (`aria-expanded`), not a menu. Sign-in tabs have tab panels. List / Timeline are links with `aria-current`. Show all expands the list in place (`aria-expanded`, `aria-controls`). Dialogs start focus on the safe action.
- **Colour is never alone.** Every status has a word and an icon.

## Components

The redesign boards in [../redesign](../redesign) and the design canvas "helper2.ai: ui v4" are the reference. The code is in `frontend/src/features/` (one folder per feature), `frontend/src/ui/` (the shared kit) and `frontend/src/shell/` (header, footer, errors).

| Component | Used for |
|---|---|
| **Header** | Logo with tagline, News, Events, Saved, Sign in or the account button. With a search box on Results and Timeline. Sticky, except on a screen up to `bp-short` tall. |
| **AccountPanel** | Under the account button: signed in with, searches left today, Your account, Sign out. A disclosure. |
| **AdminHeader** | Ink band: "helper2.ai admin", VPN note, who is signed in. No public links. |
| **Footer** | Logo, Mission, Sources, Privacy, Terms, GitHub, license, the theme switch, the coverage line. |
| **SearchBox** | Box, **Search** (primary, ↵) and **Ask the guide** (outline, ⇧↵; the icon with the words, never the icon alone). Up to 720px it says **Ask**; its accessible name stays "Ask the guide". Up to 520px the box takes the full row and the two buttons share the row under it. No word count decides anything. Pinned on Home; in the header elsewhere. |
| **PinnedSearchBar** | The home box with hint, chips and legend; sticks under the header; compact once scrolled. |
| **Radar** | See Radar. |
| **HomeLists** | New in the catalog, Coming up: one component, `TermList`. A `$ helper2 new · N` line (N = `total` from the API), `ls -l` columns, dashed rules, titles and tabs in Geist, the rest in Geist Mono. Wide: two tables (DATE · TYPE · TITLE · SOURCE, WHEN · TYPE · EVENT · WHERE), "on now" in `status-ok-ink`, `$ helper2 new --all · show all N` expands in place; Coming up also links to the Events page ("All events ›"). Below 768px: a tablist `> new in the catalog N | coming up N` (real tab buttons, 44px) with log rows. No terminal windows, no blinking cursor, no lines that print in. More rows than it shows: the list moves like a news ticker (principle 7), with Pause and Play above it and "Paused" beside them while it stands. Rows open the resource page. While a list scrolls by, its head, column names (wide) or tabs (phone) stay under the pinned box until the list ends. Loading, empty and error (with Retry) are StatePanels. |
| **ListPages** | Sources, Saved and the back office: the heading in Geist with `$ helper2 … · N` (N from the API), tables in Geist Mono with UPPERCASE column headers and dashed rules, names that are titles in Geist, a status as a coloured word with a glyph (✓ ! × ○) in the radar's colours. A wide table scrolls in its own box on a phone. Loading, empty and error as in HomeLists. |
| **PickCard** | Rank, name, AI-written reason with its label and check note, the checked quote, Open ↗, Details, Save. |
| **GuideSteps** | The streamed steps while the guide works. |
| **ResultRow** | Meta line, title, one-line description as plain text, "From source" with each source once. The whole row is a link. |
| **FiltersPanel** | Checkbox groups with counts and a Not stated bucket; Show N results; Remove all filters. |
| **CoverageDetails** | "This search: N of M sources answered" and one line per source. |
| **FactList** | Known facts as a `dl`, each once; one line for what no source stated ("Not stated by any source: pricing, platforms", in `not-stated`); sources that disagree say so in the row. The proof (origin, evidence, source records) is in the fold "Where this information comes from". |
| **ProofQuote** | Source text with the checked sentence on `mark`. |
| **ReportForm** | Opens in place on the resource page: reason, optional field, the text (marked "required"), Send report. |
| **Callout** | Notice, warning, error, success: a 4px left line, a title, one sentence, one action. |
| **ConfirmDialog** | Delete account. Focus starts on Cancel. |
| **PhoneMenu** | Full-width menu under the header on phones. |
| **Chip** | Example needs, filters, saved-by-type, use cases. A Geist pill with the control border, 44px on a touch screen, 32px (`chip-sm`) on the results page with a mouse; the picked one is a `selected` fill. |
| **ChipRow** | A labelled group of chips on the results page (Best for, Narrow by use case). Up to 720px it is one line that scrolls sideways without a scrollbar: the side with more chips fades out (a mask, so it works in both themes), and the chip under the focus scrolls into view, clear of the fade. |
| **Button** | Secondary (outline on `surface`, like Ask the guide), primary (`accent` fill, one per area), ink (sign in), ghost (outline, no fill), on-term (on a terminal panel: `term-text`, a `term-control` border, `term-bar` on hover), danger (`danger` outline; filled to confirm), icon, text (an underlined word). Geist, `radius-md`, 44px on a touch screen, 36px (`control-sm`) on the results page with a mouse; hover draws a line or ring of 3:1 or more. An external link ends with `↗`. |
| **Field** | Inputs, selects, text areas: Geist on `surface`, `border-strong`, `radius-md`, 44px. Checkboxes in `ink`. |
| **AiLabel** | One look for every AI label: "AI-written", "AI summary", "Extracted by AI", "Read by AI", "Translated by AI", and "AI draft" on a home example a model drafted and a person checked. Geist Mono 12px, outlined, words and a border, never colour alone. On a terminal panel it takes the panel's text colours. |

### In the frontend

The shared parts are React components in `frontend/src/ui/` (ADR 0040). Where the code name differs:

| Design system | Code today |
|---|---|
| HomeLists (New in the catalog, Coming up) | `TermList`, used by `HomeLists` (tables) and `HomeTabs` (phone), fed by `homeFeeds.ts` (`features/home/`) |
| Radar | `HomeRadar` (`features/home/`) |
| ListPages | `TermCommand` (from `TermList`), the `h2-table`, `ScrollBox` and `ui/term.css`; `AdminHead` in the back office |
| PickCard | `PicksBar` (the list of picks, each with its reason) and the picked rows: the reason and the quote are in the row, in search order |
| ResultRow | `ResultRow` (`features/search/`): the title's link covers the row |
| FiltersPanel | `FiltersPanel` (in place, or a drawer up to 720px) around `FilterFacets` |
| CoverageDetails | `SourceTiles` |
| Callout (error, empty) | `StatePanel`, with `RetryButton` for Try again |
| Button, Chip, Field | `.btn` with `.btn-primary`, `.btn-ink`, `.btn-ghost`, `.btn-on-term`, `.btn-danger`, `.btn-danger-fill`, `.btn-icon`; `.btn-text`; `.chip` in `.chips`; the plain form elements. One definition each, in `styles/app.css`, on every page |
| AiLabel | `AiLabel` (`ui/`), styled once by `.ai-label` (`styles/app.css`) |
| Badges | `.badge` (`styles/app.css`), `StatusPill` (`ui/`) |
| Text pages (Mission, Privacy, Terms, Not found, Sign in, Account) | `TextPage`, `SignInPage`, `AccountPage`, `NotFoundPage`, styled by `.h2-prose` in `features/pages/text.css` |

New components are added with the page that first needs them.

A style sheet declares each top-level selector once: what an element looks like is in one block, not in two that override each other. A test (`frontend/src/styles/oneRule.test.ts`) fails on a second block and on an empty at-rule.

## API features the design needs

The first three are in the API (`newThisWeek`, `limits`, `total`); Home uses them. The rest are drawn in the
redesign and not in the API yet. They are in the backlog:
[S5.6.1](https://github.com/silvioincalza/helper2.ai/issues/294) and
[S7.2.3](https://github.com/silvioincalza/helper2.ai/issues/316).

| Feature | Where | API change |
|---|---|---|
| New this week per source | Radar dot size, Sources table | `newThisWeek` on `SourceInfo` in GET /sources |
| Searches left today | Account (built), account panel | `limits` (web searches, AI answers and AI setups left, reset time) on GET /me |
| Totals for Show all | Home lists | `total` on the recent and upcoming responses |
| Sign-in with Google | Sign in | a second OAuth2 provider (phase 1) |
| Passkey, email link, password | Sign in, sign-in states | sign-in ADR, threat model, Privacy page (phase 2) |
| Not stated bucket and Updated filter | Filters panel | `facets` already has counts; add an `unknown` count per facet and an updated-date facet |
| Per-source outcome of a search | Coverage details | already in `SourceStatus` per search; the page needs to show it |
