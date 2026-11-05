# Helper2.ai

The design system for every part of Helper2.ai: the search box, results, the AI guide, New this week and Coming up, resource pages, saved resources, sign-in, the sources page and admin. Version 4, from the November 2026 redesign ([ADR 0042](../../adr/frontend/0042-redesign-design-system-v4.md)): minimal and editorial, warm paper with one saffron accent, one tech font for the whole interface. The reference is the redesign canvas, saved in [../redesign](../redesign). Every page, its states and the dark theme are drawn there. The boards are drawn in Geist; the font is Space Grotesk (see principle 5).

`frontend/src/styles/tokens.css` has the version 4 colours and names. The page layouts still follow version 3; they move to version 4 page by page when the redesign is built.

## Principles

1. **One entry point.** One search box: in the middle of the home page, in the header everywhere else. A few words search. A question of 4 words or more also starts the guide. Short searches show **Ask the guide**. Never add a second search field, a scope switch or a mode switch.
2. **Show where every fact came from.** Each result, fact and pick names its source. A value no source stated is **not stated**, in `status-warn-ink`, grouped in one line. Dates are the source's own; otherwise "First seen" with the date Helper2.ai first saw it.
3. **Checked versus not checked.** A guide quote the validator matched word for word sits on `quote-bg` under "Checked word for word in the source". The model's own reason carries its own "AI-written reason" label and is never styled like the quote.
4. **State in words, not color alone.** Every status (answered, slow, rate limited, timed out, failed, sources disagree) has a word and an icon: ✓ ! × ○ …. Color only repeats it.
5. **One font.** Space Grotesk (400, 500, 600, self-hosted) for everything: the logo, headings, controls, labels, meta lines, the terminals and running text. Code and commands use the system's monospace font, so nothing else downloads. Decided on 2 November 2026; the boards still show Geist Mono and Geist, so take the font from here, not from the boards.
6. **Quiet surfaces, one accent.** Saffron `accent` marks the one primary action per area. Selection uses `selected` (ink), so state and action never look alike.
7. **Nothing moves on its own for more than 5 seconds.** Lines print in once. The radar sweeps once and the cursor blinks three times. No auto-rotating lists, so no Pause button is needed.

## Content fundamentals

- Write the way a knowledgeable colleague talks: "A few words search. A question of 4 words or more also gets the guide's picks."
- Buttons say what happens: **Search**, **Ask the guide**, **Save**, **Remove**, **Send report**, **Try again**.
- Errors say what went wrong and what to do next, without apology.
- Never invent popularity, dates, limits or facts. Mock content uses brackets: `[stated date]`, `[Numbers set in the limits ADR]`.
- Every count comes from data: "10 of 12 sources answered", never a number typed into the page.
- Metadata reads type first, then source, then date: "Library · github.com · updated Oct 2026".
- Proper names keep their real case, also in the terminals.
- English UI; the guide accepts any language.

## Visual foundations

| Role | Token | Use |
|---|---|---|
| Page | `bg` | Everything sits on it. |
| Objects | `surface` + `border` | Cards, panels, callouts, the sign-in card. |
| Controls | `border-strong` | Inputs, selects, ghost buttons, chips, toggles. 3:1 on `bg`. |
| Selected | `selected` + `on-selected` | The active chip or view (List, Timeline). |
| Primary action | `accent` + `on-accent` | One per area: Search, Open on source ↗, Send report. |
| Links | `accent-ink` | Text links, dates, ranks, the eyebrow. Saffron `accent` is never used for small text on `bg` (3:1 only). |
| Checked | `quote-bg`, `status-ok-ink` | Checked quotes, answered sources. |
| Caution | `status-warn-ink` | Not stated, sources disagree, slow, rate limited, timed out. |
| Error | `danger` | Failed sources and requests, form errors, delete. |
| Terminals | `term-*` | New this week and Coming up. Navy in light, near-black with an outline in dark. |
| Radar | `ring`, `accent`, `status-*` | The decorative radar behind the home title. |

- **Spacing:** 4-based steps `space-1` to `space-7` (4, 8, 12, 16, 24, 32, 48px).
- **Controls:** at least `target-min` (44px) tall, also chips, toggles and buttons inside terminals. The home search box is `box-height` (60px).
- **Focus:** a 3px `focus` outline with 2px offset on every control. `scroll-padding-top` keeps a focused item below the sticky header and the pinned search box.
- **Elevation:** three shadows only: `shadow-raised` (terminals), `shadow-pinned` (the pinned search box), `shadow-overlay` (dialogs, the account panel, the phone menu). Everything else is set apart by `border`.
- **Width:** the page column stops at `content-wide` (1040px); one-column pages at `reading-max` (808px). Gutters 24px, 16px on phones.
- **Themes:** light and dark; every token has both values. The footer button switches light ↔ dark; light is the default.

## Type tags

Type is a word, not a coloured badge. In lists and on resource pages it is the first word of the meta line, in `ink-2`, weight 600. In terminals it is `[model]` in `term-tag`. The type names are the API's: Product, Model, Agent, Prompt, Skill, MCP server, Library, Dataset, Article, Paper, News, Event, Course, Other. The `tag-*` color tokens stay for the current code until the redesign replaces it.

## Logo

The mark is an ink-stroke radar (outer ring, faint inner ring, one sweep line, one filled four-point AI spark) on a 40px `accent` tile with 12px corners (`assets/Logos/radar-mark.svg`). It sits 14px left of the wordmark "helper2.ai" in Space Grotesk 24px, weight 500. Under it, 4px below, the tagline **AI ON YOUR RADAR**: Space Grotesk 11px, weight 600, uppercase, 0.1em tracking, in `muted`. Below 420px the tagline hides and the wordmark is 20px. Never recolor the radar, add effects, or drop the wordmark in the header.

## Iconography

Apart from the radar mark, icons are small stroke glyphs beside words, never alone: check, cross, warn, clock, external link, menu, close. Use `→` for "go further" and `↗` for an external source. Never emoji. An icon-only button (menu, close) carries an `aria-label`.

## Home page

1. **Hero**, centred: the eyebrow "One search · N AI sources" (N from GET /sources), the title "The metasearch for the AI ecosystem.", one line on what it does. Behind it the radar (see Radar).
2. **Pinned search box**, full width up to 820px: box, Search, one hint line, example chips, the radar legend. It sticks under the header while the page scrolls; once the title is gone, the hints and legend fold away and it gets `shadow-pinned`.
3. **New this week** and **Coming up**: two terminals side by side, stacked below 920px. See TerminalList.
4. **What helper2 does for you**: a quiet strip of three steps.

On phones (below 720px) the title and text fold away after 24px of scroll; the search box stays pinned; the terminals scroll under it. Back at the top, everything opens again. News and Events move from the header into the menu.

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

Behind the home title, hidden from screen readers. One dot per enabled source from GET /sources:

| Encodes | How |
|---|---|
| Mode | Inner band: live sources. Outer band: index and curated. |
| Last check | Filled saffron: live, answered. Hollow saffron: index. Amber: slow, rate limited, timed out. Red: failed. Grey: curated, not checked. |
| Distance in the band | Live: faster answer = closer. Index: fresher check = closer. |
| Size | New this week from that source (proposed API field `newThisWeek`). |

Each dot has a fixed slot left or right of the text and moves only inside its band. The label under it says the name and the response time or age, in `accent-ink` or the status color. Below 900px the dots and legend hide; the rings stay. The Sources page shows the same data as a table.

## Layout and breakpoints

| Width | What changes |
|---|---|
| 920px | The two terminals stack. |
| 900px | Radar dots and legend hide. Four-column steps become two; two-column grids become one. |
| 720px | Gutter 16px. The header sticks with logo, Saved and Sign in only; News and Events move to the menu. The header search takes its own row. The home title folds on scroll. |
| 560px | Steps and tables become cards. Terminal rows put the name under the date and tag. The timeline puts the month above its items. |
| 420px | The tagline hides; the wordmark is 20px; the account button shows only its avatar. |

Layout is flex and grid with `gap`. Side menus wrap above the content on phones and are never sticky.

## Accessibility

- **Moving content.** All automatic motion ends within 5 seconds of the page opening: terminal lines print in once (about 1 second), the radar sweeps once (4.5 seconds) and each dot lights up once as it passes, the cursor blinks 3 times (4.8 seconds) and stays on. So WCAG 2.2.2 needs no Pause button. Under `prefers-reduced-motion` nothing moves and nothing folds with animation. Dots that move when GET /sources changes are updates, not animation loops.
- **Targets:** every control is at least 44px, also chips, toggles, Remove, admin buttons and terminal buttons.
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
| **TerminalList** | New this week, Coming up: title bar with three dots, a `$` command, rows (date, `[tag]`, name, source or place), a blinking cursor, and a `$ … --all · show all N` button that expands in place. Rows open the resource page. Loading, empty and error states print as terminal lines. |
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
| **AiLabel** | "AI-written", "AI-written reason", "AI summary". Space Grotesk 12px, uppercase, outlined. |

### In the frontend

The shared parts are React components in `frontend/src/components/ui/` (ADR 0040). Where the code name differs:

| Design system | Code today |
|---|---|
| TerminalList (New this week, Coming up) | `HomeBoard`, filled by `NewsBoard` and `EventsBoard` (`components/home/`) |
| PickCard | `PicksBar` and the pick rows |
| CoverageDetails | `SourceTiles` |
| Callout (error, empty, 404) | `StatePanel`, with `RetryButton` for Try again |

New components are added with the page that first needs them.

## API features the design needs

These are drawn in the redesign and are not in the API yet. They are on the roadmap (section 6).

| Feature | Where | API change |
|---|---|---|
| New this week per source | Radar dot size, Sources table | `newThisWeek` on `SourceInfo` in GET /sources |
| Searches left today | Account, account panel | `limits` (web searches and AI answers left, reset time) on GET /me |
| Totals for Show all | Home terminals | `total` on the recent and upcoming responses |
| Sign-in with Google | Sign in | a second OAuth2 provider (phase 1) |
| Passkey, email link, password | Sign in, sign-in states | sign-in ADR, threat model, Privacy page (phase 2) |
| Unknown bucket and Updated filter | Filters panel | `facets` already has counts; add an `unknown` count per facet and an updated-date facet |
| Per-source outcome of a search | Coverage details | already in `SourceStatus` per search; the page needs to show it |
