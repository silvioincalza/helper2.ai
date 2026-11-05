# Helper2.ai

The design system for every part of Helper2.ai: the search box, results, the AI guide, New this week and Coming up, resource pages, saved resources, sign-in, the sources page and admin. Version 4, from the November 2026 redesign ([ADR 0042](../../adr/frontend/0042-redesign-design-system-v4.md)): minimal and editorial, warm paper with one saffron accent, one tech font for the interface and a calm font for reading. The reference is the redesign canvas, saved in [../redesign](../redesign). Every page, its states and the dark theme are drawn there.

`frontend/src/styles/tokens.css` has the version 4 colours and names. The header, the footer and Home run the version 4 layout, with its own sizes and fonts (`--v4-*`); the other pages still follow the version 3 layout and Space Grotesk, and move page by page.

## Principles

1. **One entry point.** One search box: in the middle of the home page, in the header everywhere else. A few words search. A question of 4 words or more also starts the guide. Short searches show **Ask the guide**. Never add a second search field, a scope switch or a mode switch.
2. **Show where every fact came from.** Each result, fact and pick names its source. A value no source stated is **not stated**, in `status-warn-ink`, grouped in one line. Dates are the source's own; otherwise "First seen" with the date Helper2.ai first saw it.
3. **Checked versus not checked.** A guide quote the validator matched word for word sits on `quote-bg` under "Checked word for word in the source". The model's own reason carries its own "AI-written reason" label and is never styled like the quote.
4. **State in words, not color alone.** Every status (answered, slow, rate limited, timed out, failed, sources disagree) has a word and an icon: ✓ ! × ○ …. Color only repeats it.
5. **One tech font, one reading font.** Geist Mono for the logo, headings, controls, labels, meta lines and the two home lists. Geist for running text: paragraphs, list items, quotes, descriptions.
6. **Quiet surfaces, one accent.** Saffron `accent` marks the one primary action per area. Selection uses `selected` (ink), so state and action never look alike.
7. **Nothing moves on its own for more than 5 seconds, with one exception.** No CSS animation repeats, no line prints in, no cursor blinks. The exception: the home radar, like a navigation radar. It holds still while you type in the box, stands still under reduced motion, and a signed-in user can turn it off.

## Content fundamentals

- Write the way a knowledgeable colleague talks: "A few words search. A question of 4 words or more can also ask the guide for picks."
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

- **Spacing:** 4-based steps `space-1` to `space-7` (4, 8, 12, 16, 24, 32, 48px).
- **Controls:** at least `target-min` (44px) tall, also chips, toggles and the `$ … --all` buttons. The home search box is `box-height` (60px).
- **Focus:** a 3px `focus` outline with 2px offset on every control. `scroll-padding-top` keeps a focused item below the sticky header and the pinned search box.
- **Elevation:** three shadows only: `shadow-raised` (not used on Home any more), `shadow-pinned` (the pinned search box), `shadow-overlay` (dialogs, the account panel, the phone menu). Everything else is set apart by `border`.
- **Width:** the page column stops at `content-wide` (1040px); one-column pages at `reading-max` (808px). Gutters 24px, 16px on phones.
- **Themes:** light and dark; every token has both values. The footer button switches light ↔ dark; light is the default.

## Type tags

Type is a word, not a coloured badge. In lists and on resource pages it is the first word of the meta line, in `ink-2`, weight 600. In the home lists it is the TYPE column (`model`) or `[model]` in a phone log row, in `accent-ink`. The type names are the API's: Product, Model, Agent, Prompt, Skill, MCP server, Library, Dataset, Article, Paper, News, Event, Course, Other. The `tag-*` color tokens stay for the current code until the redesign replaces it.

## Logo

The mark is an ink-stroke radar (outer ring, faint inner ring, one sweep line, one filled four-point AI spark) on a 40px `accent` tile with 12px corners (`assets/Logos/radar-mark.svg`). It sits 14px left of the wordmark "helper2.ai" in Geist Mono 24px, weight 500. Under it, 4px below, the tagline **AI ON YOUR RADAR**: Geist Mono 11px, weight 600, uppercase, 0.1em tracking, in `muted`. Below 420px the tagline hides and the wordmark is 20px. Never recolor the radar, add effects, or drop the wordmark in the header.

## Iconography

Apart from the radar mark, icons are small stroke glyphs beside words, never alone: check, cross, warn, clock, external link, menu, close. Use `→` for "go further" and `↗` for an external source. Never emoji. An icon-only button (menu, close) carries an `aria-label`.

## Home page

1. **Hero**, centred, with no line above it: the title, one `h1` on two lines: "helper2.ai" in `accent-ink` (same size and weight), then "The metasearch for the AI universe."; one line on what it does. Around it the radar (see Radar).
2. **Pinned search box**, full width up to 820px: box, Search, one hint line, the "Try:" chips, the radar legend ("3-5 of N sources at a time", the three colours, a link to the Sources page). It sticks under the header while the page scrolls; once the title is gone, the hints and legend fold away and it gets `shadow-pinned`.
3. **New this week** and **Coming up**: two tables side by side, like `ls -l`, stacked below 920px; tabs below 768px. See HomeLists.
4. **What helper2 does for you**: a quiet strip of three steps.

On phones (below 720px) the title and text fold away after 24px of scroll; the search box stays pinned; the lists scroll under it. Back at the top, everything opens again. News and Events move from the header into the menu.

## Results page

One column, `reading-max`.

- **Header:** logo, the search box with the query, Saved, Sign in. News and Events are not in this header.
- **A question (4 words or more):** "Recommended for you", then up to 3 PickCards, then "How the guide decided" (a disclosure: search terms, candidates, removed suggestions, models).
- **A few words:** an **Ask the guide** bar above the list instead of picks.
- **Guide states**, drawn where the picks sit: working (streamed steps), needs one more detail, no pick, AI model not available (top 3 by search score, no AI label because no AI wrote it), busy (503), timeout or stalled stream (504, 45 seconds), daily AI answers used up.
- **All results:** count, Filters (opens a panel: Type, Price, Licence, Updated, each with counts and an Unknown bucket), Sort (Best match, Newest, Recently updated), List or Timeline. A partial-results notice when some sources did not answer. Rows link to the resource page. Guide picks carry "Guide pick #n" in the meta line.
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

3 to 5 sources show at a time. It starts with 3, one after another (0.6 s apart). Then every 1.6 s one step: a new
random source comes while it grows, up to 5; the oldest leaves while it shrinks, down to 3. A dot is painted only when
the beam passes it: full opacity, a pop (scale 1.35, back in 0.25 s) and a glow in its colour (0.6 s), then it fades
to 0.22 until the next pass. A new source shows the first time the beam passes it. A source that leaves is not painted
again and fades out within 3 s. Beam and dots run on one clock (`requestAnimationFrame`), which stops while the search
box has focus. Under reduced motion, or with the account setting off, the beam stands still and a fixed random set of
4 shows at full opacity.

Each dot links to the Sources page; its accessible name and tooltip say the state ("GitHub, live, answered in 410 ms,
14 new this week"). A dot that is not on the radar is hidden from screen readers and the keyboard. The legend under
the box says "3-5 of N sources at a time", names the three colours next to a filled swatch, and links to the Sources
page, which shows every source as a table. Up to 900px only the rings and the beam show.

## Layout and breakpoints

| Width | What changes |
|---|---|
| 920px | The two tables stack. |
| 900px | Four-column steps become two; two-column grids become one. |
| 768px | The two tables become one panel with two tabs and log rows. |
| 720px | Gutter 16px. The header sticks with logo, Saved and Sign in only; News and Events move to the menu. The header search takes its own row. The home title folds on scroll. |
| 560px | Steps and tables become cards. The timeline puts the month above its items. |
| 420px | The tagline hides; the wordmark is 20px; the account button shows only its avatar. |

Layout is flex and grid with `gap`. Side menus wrap above the content on phones and are never sticky.

## Accessibility

- **Moving content.** No CSS animation repeats: the loading lines pulse 3 times, nothing prints in, no cursor blinks. A test in the frontend fails on any CSS or inline animation that repeats for ever. One exception: the home radar keeps turning (one JavaScript clock, see Radar). It holds still while the search box has focus; signed-in users can turn it off; under `prefers-reduced-motion` the beam stands still and 4 dots stay. Anonymous visitors have no Pause button yet: a known gap against WCAG 2.2.2, on the roadmap. Under `prefers-reduced-motion` nothing moves and nothing folds with animation.
- **Targets:** every control is at least 44px, also chips, toggles, tabs, Remove and admin buttons.
- **Contrast:** text 4.5:1, control borders and focus 3:1. Saffron `accent` text on `bg` is 3:1, so small saffron text uses `accent-ink` (5.5:1).
- **Headings:** one `h1` per page.
- **AI-written text is labelled** (EU AI Act Art. 50): each AI field has its own label ("AI-written", "AI-written reason", "AI summary") and its own `data-ai-generated="true"` and `data-ai-model`. Checked quotes and buttons are never inside a marked element.
- **Patterns:** the account panel is a disclosure (`aria-expanded`), not a menu. Sign-in tabs have tab panels. List / Timeline are links with `aria-current`. Show all expands the list in place (`aria-expanded`, `aria-controls`). Dialogs start focus on the safe action.
- **Colour is never alone.** Every status has a word and an icon.

## Components

The redesign boards in [../redesign](../redesign) are the reference. The component folders below still show version 3.

| Component | Used for |
|---|---|
| **Header** | Logo with tagline, News, Events, Saved, Sign in or the account button. With a search box on Results and Timeline. Sticky. |
| **AccountPanel** | Under the account button: signed in with, searches left today, Your account, Sign out. A disclosure. |
| **AdminHeader** | Ink band: "helper2.ai admin", VPN note, who is signed in. No public links. |
| **Footer** | Logo, Mission, Sources, Privacy, Terms, GitHub, licence, the theme switch, the coverage line. |
| **SearchBox** | Box and Search button. Pinned on Home; in the header elsewhere. |
| **PinnedSearchBar** | The home box with hint, chips and legend; sticks under the header; compact once scrolled. |
| **Radar** | See Radar. |
| **HomeLists** | New this week, Coming up: one component, `TermList`. A `$ helper2 news · N` line (N = `total` from the API), `ls -l` columns, dashed rules, titles in Geist, the rest in Geist Mono. Wide: two tables (DATE · TYPE · TITLE · SOURCE, WHEN · TYPE · EVENT · WHERE), "on now" in `status-ok-ink`, `$ helper2 news --all · show all N` expands in place. Below 768px: a tablist `> new this week N | coming up N` (real tab buttons, 44px) with log rows. No terminal windows, no blinking cursor, no lines that print in. Rows open the resource page. Loading, empty and error (with Retry) are StatePanels. |
| **ListPages** | Sources, Saved and the back office: the heading with `$ helper2 … · N` (N from the API), tables in Geist Mono with UPPERCASE column headers and dashed rules, names that are titles in Geist, a status as a coloured word with a glyph (✓ ! × ○) in the radar's colours. A wide table scrolls in its own box on a phone. Loading, empty and error as in HomeLists. |
| **PickCard** | Rank, name, AI-written reason with its label and check note, the checked quote, Open ↗, Details, Save. |
| **AskGuideBar** | On short searches: "Want a recommendation?" and **Ask the guide about "…"**. |
| **GuideSteps** | The streamed steps while the guide works. |
| **ResultRow** | Meta line, title, one-line description, "From source". The whole row is a link. |
| **FiltersPanel** | Checkbox groups with counts and an Unknown bucket; Show N results; Remove all filters. |
| **CoverageDetails** | "This search: N of M sources answered" and one line per source. |
| **FactList** | Known facts as a `dl`; one line for what no source stated; a callout when sources disagree. |
| **ProofQuote** | Source text with the checked sentence on `mark`. |
| **ReportForm** | Opens in place on the resource page: reason, optional text, Send report. |
| **Callout** | Notice, warning, error, success: a 4px left line, a title, one sentence, one action. |
| **ConfirmDialog** | Delete account. Focus starts on Cancel. |
| **PhoneMenu** | Full-width menu under the header on phones. |
| **Chip** | Example needs, filters, saved-by-type. 44px. |
| **Button** | Primary, ghost, ink, danger, text. 44px; an external link ends with `↗`. |
| **AiLabel** | "AI-written", "AI-written reason", "AI summary". Geist Mono 12px, uppercase, outlined. |

### In the frontend

The shared parts are React components in `frontend/src/components/ui/` (ADR 0040). Where the code name differs:

| Design system | Code today |
|---|---|
| HomeLists (New this week, Coming up) | `TermList`, used by `HomeLists` (tables) and `HomeTabs` (phone), fed by `homeFeeds.ts` (`components/home/`) |
| Radar | `HomeRadar` (`components/home/`) |
| ListPages | `TermCommand` (from `TermList`), the `v4-table`, `ScrollBox` and `components/ui/term.css`; `AdminHead` in the back office |
| PickCard | `PicksBar` and the pick rows |
| CoverageDetails | `SourceTiles` |
| Callout (error, empty, 404) | `StatePanel`, with `RetryButton` for Try again |

New components are added with the page that first needs them.

## API features the design needs

The first three are in the API (`newThisWeek`, `limits`, `total`); Home uses them. The rest are drawn in the
redesign and not in the API yet. They are on the roadmap (section 6).

| Feature | Where | API change |
|---|---|---|
| New this week per source | Radar dot size, Sources table | `newThisWeek` on `SourceInfo` in GET /sources |
| Searches left today | Account, account panel | `limits` (web searches and AI answers left, reset time) on GET /me |
| Totals for Show all | Home lists | `total` on the recent and upcoming responses |
| Sign-in with Google | Sign in | a second OAuth2 provider (phase 1) |
| Passkey, email link, password | Sign in, sign-in states | sign-in ADR, threat model, Privacy page (phase 2) |
| Unknown bucket and Updated filter | Filters panel | `facets` already has counts; add an `unknown` count per facet and an updated-date facet |
| Per-source outcome of a search | Coverage details | already in `SourceStatus` per search; the page needs to show it |
