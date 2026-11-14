# 0040 — Design system

**Depends on:** [0031](../guide/0031-guide-pipeline.md), [0032](../guide/0032-guide-cost-guard-and-answer-cache.md), [0033](../guide/0033-ai-content-marking-and-research-trail.md), [0036](../identity/0036-identity-and-admin-host.md), [0039](0039-frontend-one-box.md), [0041](0041-site-pages-and-policy.md), [0042](../money/0042-monetisation.md).

## Context

The box, the URL state and the guide stream are in [0039](0039-frontend-one-box.md). This record decides the pages
around them and the rules every page follows. What matters for a user:
- Everyone must understand at once what helper2.ai helps with.
- The guide answer arrives after several seconds. Results are ready much sooner, and the user starts reading them.
  Anything that moves the list at that moment makes them lose their place.
- On a phone, the home lists and the results must not compete for the same narrow column.
- The site must read well: long text in a monospace font reads slowly, and the tech look must not cost that.

We drew every page, with all its states, the dark theme and the phone layout, and had the drawings reviewed for user
journeys, accessibility and fit with the API. The boards are in [docs/design/redesign](../../design/redesign); the
rules are the design system ([docs/design/system](../../design/system/README.md)). For New in the catalog and Coming up we
drew three options (`TechLists`, `MobileLists` in the same folder). A last canvas ("fonts, home ticker, News and
Events") set the font rule for every page, the ticker's hold (no Pause button) and the News and Events pages.

## Decision

1. **One palette, one token source.** Paper, indigo ink, one saffron accent. `docs/design/system/tokens.json`
   (version 1) is the one source of the tokens: the colours in light and dark, the semantic aliases (status pills,
   proof quote, source tiles), the type scale, spacing, radius, sizes, shadows, layers, motion and the breakpoints. `tokens.css` writes them as
   CSS variables under plain names (`--bg`, `--ink`, `--accent`, `--font-mono`, `--box-height`, `--header-height`);
   `lib/breakpoints.ts` writes the breakpoints for the JavaScript queries. A test (`styles/tokens.test.ts`) fails when
   the three differ, when a colour is written by its hex value outside `tokens.css`, or when a media query uses a
   width that is not a breakpoint. In the public CSS it also fails on a font size, a `z-index` or a corner radius
   written as a number, and on `rgba()`: each is a token. Two more tests fail on a class no code writes (`styles/classesUsed.test.ts`) and
   on a translation key no code reads (`i18n/keysUsed.test.ts`).
2. **Seven breakpoints.** 420, 520, 720, 768, 860, 920 and 1100 px. A media query is "up to W" (`max-width: W`),
   "above W" (`min-width: W + 1px`) or "from W" (`min-width: W`). What changes at each is in the design system README.
3. **Two fonts with clear jobs.** Geist Mono shows what a machine says: the logo, meta lines, counts, dates, types,
   `$ helper2` commands, key hints and table cells. Geist is for what a person reads or presses: running text, titles,
   headings, buttons, chips, tabs, links and fields; a name in a table is a title, so it is Geist too. Both are the
   variable fonts from `@fontsource-variable/geist` and `@fontsource-variable/geist-mono` (SIL Open Font License),
   bundled by Vite and served from our own origin: no third-party request, and the CSP stays `font-src 'self'`. One
   file per script covers every weight; an English page loads only the Latin files (29 KB and 23 KB), and the build
   preloads those two. Code uses the system monospace: nothing to download.
4. **One class prefix.** Every component class is `h2-<block>__<part>--<state>`; the page scope is `.h2-app`, the page
   column `.h2-wrap`. The small shared kit in `app.css` keeps plain names (`.btn*`, `.chip*`, `.field*`,
   `.sr-only`, `.skip-link`). `.h2-prerender` has no frontend code: the server writes it ([0041](0041-site-pages-and-policy.md)).
5. **Header and footer** (`shell/Layout.tsx`, `styles/shell.css`). The header: the logo (mark, wordmark in Geist
   Mono 600 20 px, "AI on your radar"), News, Events, Saved, Sign in or the account, the links in Geist 500 14 px.
   Up to 720 px: the logo and a menu button; the menu holds New this week, Coming up, Saved, Sources, Mission, Sign
   in and the theme; Escape or a new page closes it. On the pages without their own box, the box sits in the header.
   The footer: the logo, one row of links (Mission, Sources, Privacy, Terms, GitHub ↗, the licence ↗, the release ↗,
   the theme switch), then one line: what is searched, who and where. It makes no claim about ads ([0042](../money/0042-monetisation.md)).
6. **Home: the title, the radar, a pinned search box, two lists.** The title is one `h1` on two lines: "helper2.ai",
   then "The metasearch for the AI universe.", with one line under it. Behind it rises a radar that works like a
   navigation radar: a half dome from the bottom middle of the title block, range rings, dotted bearing lines every
   30°, ticks every 5° on the outer ring of the arc it shows, a glowing origin, and a sweep that turns once every 5 s: a bright leading
   line with a soft halo and a trail that fades over 56°.
   The dome's radius is half the width or one and a half times the height, whichever is smaller (`radarLayout.ts`).
   Up to 720 px it is a quarter dome in the top right corner of the title block instead, 60 % of the width, behind
   the text: no extra height, so the box stays high. There the dots avoid each line of text, not the whole block, so
   they sit in the free space at the end of the short lines.
   - **Which sources.** Only external sources (`external` on GET /sources). Live sources sit on the two inner rings,
     the Scout collectors on the two outer ones. The first dot takes its own spot from its id; each next one takes
     the free spot on its rings farthest from the dots already there, so the names spread over the dome, never under
     the title or the box, never touching another name (`radarLayout.ts`). A dot never slides.
   - **5 to 10 at a time, every source in turn; 2 to 3 up to 720 px.** It starts with 5 sources (2 on a phone),
     0.6 s apart. Then every 1.6 s one step: while it grows, the next source of the queue comes, up to 10 (3); then
     the oldest leaves, one at a time, down to 5 (2).
     The queue holds every source, shuffled once; a source that leaves goes to the back (`radarRotation.ts`).
   - **Painted by the beam.** A dot shows when the beam passes its angle: full opacity, a short pop, a glow in its
     colour and a sonar ring that grows to four times its size and fades within 1.4 s; then an afterglow fades it to
     0.4 over about 2.4 s, so a dot stays readable on a phone. A source that leaves
     fades to 0 within 3 s. The beam and the dots run on one `requestAnimationFrame` clock (`radarClock.ts`), so
     they cannot drift apart.
   - **What a dot says.** Its short name, shown next to it above 720 px; on a phone the dot has no visible name. Its colour: orange answering, amber slow or limited, red failed, grey
     skipped or not checked; 3:1 or more in both themes (a test checks it). Its size: `newThisWeek`, scaled with
     the dome (60 % on a phone, full from a 480 px radius). Colour is never the only
     key: each dot is a link to the Sources page whose accessible name and tooltip say the state. A dot that
     is not on the radar is hidden from screen readers and the keyboard. The dots are after the search box in the
     page (a portal), so Tab reaches the box first.
   - **The legend** under the box: "5-10 of N sources at a time", a swatch per colour, a link to the Sources page.
   - **When it holds still.** While the mouse is over the radar or the focus is on a dot, so a focused dot cannot
     leave; it runs again when they leave. There is no button, and typing in the box does not stop it. Under reduced
     motion, or for a signed-in user who turned "Home page motion: radar and lists" off on the account page
     (`preferences.radarMotion`, [0036](../identity/0036-identity-and-admin-host.md)),
     there is no clock: the beam stands still and a fixed random set of 7 (3 on a phone) shows. Up to 920 px
     the legend is hidden; the dome and its dots show on every width.

   The box, its hint, the "Try:" examples and the legend stay pinned under the header while the page scrolls; once
   pinned, the hint, the examples and the legend fold away. Up to 720 px the title and the radar fold away after
   24 px of scroll and open again at the top. **What helper2 does** follows the lists as a quiet strip of three steps.
7. **New in the catalog and Coming up: one list component** (`TermList`). A terminal style without terminal windows: a
   `$ helper2 new · N` line (N = `total` from the API), `ls -l` columns with dashed rules. Above 768 px two tables
   side by side: DATE · TYPE · TITLE · SOURCE and WHEN · TYPE · EVENT · WHERE, a running event "on now". Up to 768 px
   one panel with two tabs, `> new in the catalog N | coming up N` (real tabs: `role="tab"`, arrow keys, Home, End), and
   log rows. New in the catalog is tools and research from our own index (`GET /recent`, the last 7 days); Coming up asks
   `GET /events/upcoming?days=90`. Six rows show; `$ helper2 new --all · show all N` opens the rest in place, and
   Coming up ends with **All events ›**, to the Events page. While a list scrolls by, its head stays under the pinned
   box until the list ends. Loading, empty and error (with Retry) are the same in both.
8. **The lists move like a news ticker** (`features/home/ticker.ts`). When a list has more rows than it shows, one
   row leaves at the top every 4 s and the next comes in at the bottom, with a 700 ms slide, so each row stands for
   more than 3 s. It holds while the pointer is over it, the focus is in it, after a tap (a second tap starts it
   again), while it is off screen or the tab is hidden, and on a phone while the page scrolls (it moves again a second
   after). There is no Pause button; it moves again when the mouse leaves, the focus leaves or the tap ends. Under
   reduced motion, or with the radar's switch off, it never moves.
9. **Nothing else moves on its own for more than 5 seconds.** No CSS animation repeats: the loading lines pulse three
   times and stop. Under reduced motion nothing moves, and nothing folds with an animation. A test fails on any CSS or
   inline animation that repeats for ever. The radar (6) and the ticker (8) are the two exceptions; both move by a
   JavaScript clock, not a CSS animation.
10. **Results on `/search`, one column, in the terminal style.** The box and the examples open `/search?q=…`; `/?q=…`
    shows the same page. Both say `noindex, follow`. The results view loads on demand (`HomeOrResultsPage`); the
    page's `h1`, the box and a skeleton of the list's shape are in the first download, so nothing mounts twice or
    moves while the chunk loads.
    - **The heading.** "Results" over `$ helper2 search · N`, List / Timeline (links with `aria-current`: the view is
      in the URL), then Filters ("· 2 on") and the sort. Under it the type tabs: All, the four types with most results
      (two on a phone), More with the rest. Filters open in place on a wide screen and as a drawer (a modal `dialog`)
      up to 720 px, with Close and "Show N results" ("Searching…" while a filter change loads).
    - **Rows.** A row: type · host · date (updated, else created, else "first seen" in the not-stated colour), the
      guide pick, the title, the description, "From" the sources. The title's link opens the resource page; nothing
      opens in place. **Back to results** goes back in history, so the list keeps its place.
    - **Show more** adds the next page in place, moves focus to its first row and says "20 more results" once. `page`
      in the URL is the last page shown, so Back and a reload show the same rows. Up to page 50.
    - **The guide's picks bar.** The guide runs only when the user asks (Ask the guide, or Shift+Enter; Enter
      searches). For a question the bar is there from the first render, its height reserved, so nothing moves when the
      answer arrives over SSE ([0031](../guide/0031-guide-pipeline.md)). The picks are chips (#1, #2, #3), labelled
      AI-written ([0033](../guide/0033-ai-content-marking-and-research-trail.md)). The rows keep the search order: a
      picked row shows "Guide pick #1", the reason (labelled "AI-written", marked `data-ai-generated`) and its quote,
      highlighted where it stands in the source's own words. A chip jumps to its row; on the timeline, or for a pick
      not on the page, it opens the resource page and passes what that page shows of the pick in the history state.
      No pick: one short line that says why, with "Ask without “…”" per requirement and "Rephrase". "How the guide
      decided" is a fold under the bar with the steps, "Understood as" (✕ asks again without a part) and the models.
    - **Some sources did not answer:** one quiet line under the heading: "2 of 5 sources did not answer · Which?"
      (`CoverageLine`); Try again only when a retry can help. The source tiles sit under the results.
    - **Best for** (Anyone, Apps I can use, Build with AI) sets the audience `path`: it changes what ranks first and
      never hides a result.
    - **The timeline:** the month beside a line of dots; the results with no stated date are one folded group at the
      end ("Date unknown · N · Show them").
11. **The resource page: one column.** The title, under it type · host · the same date as the row (`ResultDate`), one
    primary button (Open on host ↗), Save, and Set it up only for what a user installs or runs. Then the pick, From
    the source, At a glance, the dates, "Where this information comes from" (folded) and the detail sections, its
    tables in Geist Mono with capital headers and dashed rules. A result row passes its title, type and link in the
    history state, so a resource opened from the results shows the title, Open and Save at once while its details load.
    A pick chip does the same when its result is among the results the page holds (on the timeline); a pick that is
    not among them shows the loading line until the details arrive.
12. **Sources, Saved and the back office in the terminal style.** Each list page has a `$ helper2 … · N` line next to
    its heading, with N from the API (`TermCommand`). Tables are `h2-table`: Geist Mono, UPPERCASE headers, dashed
    rules, names that are titles in Geist. A wide table scrolls inside its own box (`ScrollBox`, a named region a
    keyboard can reach); the page never scrolls sideways. A status is a coloured word with a glyph (✓ ! × ○); the
    colour only repeats it. Sources is one table of every source, ours included, in the radar's colours. Saved keeps
    its rows and type chips; signed in, it shows the loading lines until the account list arrives. The back office's
    queue, Scout runs, budgets and audit trail are tables.
13. **News and Events.** **AI news** (`/news`) is laid out like a front page: one lead story, two under it, Latest
    beside them, then the rest of the week. Headlines, site and date only: no excerpt and nothing AI-written. A filter
    by site (`?site=`). **Events** (`/events`) is every event in the next year whose source states its dates, grouped
    by month with a count per month; chips by kind and by online or in person, each with its count (`?kind=`,
    `?format=`). Days, kind and place an AI model read carry "Extracted by AI" and the machine-readable mark. A row on
    either page opens the resource page.
14. **Text pages stay prose.** Mission, Privacy, Terms, Not found, Account and the sign-in page have no terminal look:
    Geist for headings and text, Geist Mono only for small labels, a column of about 68 characters, line height 1.65
    (`features/pages/text.css`). Not found keeps the real 404 (the server answers 404): a `404` label, the title, one
    line that points to the box in the header, and a link home. Account shows who is signed in, the AI answers and
    web searches left today, "Home page motion: radar and lists", and Delete my account with its confirmation (focus on Cancel).
    The sign-in page (`/signin`) says why to sign in and has one button per provider in `loginProviders` on GET /me;
    "Sign in" in the header opens it and carries the current page ([0036](../identity/0036-identity-and-admin-host.md)).
15. **The guide by choice, and the quota in sight.** The box has both buttons, always: Search (primary, saffron) and
    Ask the guide (outline). No word count decides anything ([0032](../guide/0032-guide-cost-guard-and-answer-cache.md)).
    The hint under the box says so. Once there are words in the box, one quiet line says today's AI answers ("2 of 3
    AI answers left today"), from `limits.guideQuestions` on GET /me (`GuideAnswersLeft`); when the guide has no model
    the line is not shown. "Set it up" on a resource page has the same line for today's AI setups, from
    `limits.setupGuides` (`SetupGuidesLeft`).
16. **Every number comes from data.** The radar legend counts the enabled external sources of GET /sources, the same
    sources the radar shows; the list counts are `total` on GET /recent and GET /events/upcoming, never fewer than the
    rows sent (`lib/sourceCounts.ts` says what the page does when a value is null). The Mission, Privacy and Terms
    pages take their numbers from GET /policy ([0041](0041-site-pages-and-policy.md)). A sentence with numbers has
    two forms: with `{{name}}` variables, and the same sentence in words. The page waits for the numbers at most
    500 ms; late, it shows the words and then the numbers; on an error, the words and one line that says so. Never a
    typed fallback number.
17. **States are designed.** Loading (skeleton rows), guide answering, no results, some sources failed, all sources
    failed, 404. One component draws them all (`StatePanel`): a heading that says what happened, why under it, the
    next step last. An error is an alert. Try again keeps focus on a heading that stays on the page.
18. **Accessibility, and one set of controls.** The shared controls have one definition each, in `app.css`, and look
    the same on every page. Buttons: secondary (`.btn`), primary (`.btn-primary`), ink, ghost, danger, icon, text.
    Chips (`.chip`) are pills, the picked one an ink fill. Hover always draws a line or a ring of 3:1 or more; a
    disabled button is quiet, not faded. A contrast test checks every state in both themes. On a touch screen every
    control is at least 44 px; with a mouse (pointer fine) buttons are 36 px (`control-sm`) and chips 32 px
    (`chip-sm`), above WCAG 2.2's 24 px. The focus ring (3 px ink outline) never looks like the selected line. Key
    hints (`/`, `↵`) show only where there is a keyboard. Text and controls meet AA in light and dark. The axe test
    runs in both themes, on a wide screen and on a phone, on Home (with the menu open), Results, the resource page,
    the list pages and the text pages.
19. **One design, released by tag.** The code holds one design and no design flag. Prod deploys only a release tag
    ([0044](../operations/0044-ci-cd-and-supply-chain.md)), so `main` and staging run a change before a release takes
    it to users.

### Code

- `docs/design/system/tokens.json`, `frontend/src/styles/tokens.css`, `frontend/src/lib/breakpoints.ts`,
  `frontend/src/styles/tokens.test.ts` — the token source, its CSS and the breakpoints, kept in step.
- `frontend/src/styles/app.css` (the shared controls), `shell.css` (header, footer, page column),
  `frontend/src/styles/classesUsed.test.ts`, `frontend/src/i18n/keysUsed.test.ts`, `controlsContrast.test.ts`,
  `motion.test.ts`.
- `frontend/src/main.tsx` — the fonts. `frontend/vite.config.ts` — the two Latin files preloaded.
- `frontend/src/shell/` — `Layout` (header, footer, the menu), `RouteError`, `NotFoundPage`, `Robots`.
- `frontend/src/ui/` — `StatePanel`, `StatusPill`, `ProofQuote`, `SourceTiles`, `PicksBar`, `ScrollBox`, `Badges`,
  `Logo`, `term.css`, `ui.css`, `listing.css`.
- `frontend/src/features/home/` — `Home`, `HomeRadar`, `radarLayout.ts`, `radarRotation.ts`, `radarClock.ts`,
  `useRadarMotion.ts`, `RadarLegend`, `TermList`, `ticker.ts`, `HomeLists`, `HomeTabs`, `homeFeeds.ts`, `home.css`.
- `frontend/src/features/search/` — `HomeOrResultsPage`, `Results`, `ResultRow`, `ResultsSkeleton`, `TypeTabs`,
  `FiltersPanel`, `FilterFacets`, `CoverageLine`, `AudienceChoice`, `ResultsTimeline`, `morePages.tsx`,
  `results.css`.
- `frontend/src/features/guide/` — `GuidePicks`, `GuideAnswer`, `GuideAnswersLeft`.
- `frontend/src/features/resource/` — `ResourcePage`, `Resource`, `ResultFacts`, `DateTimeline`, `resource.css`.
- `frontend/src/features/news/NewsPage.tsx`, `frontend/src/features/events/EventsPage.tsx`.
- `frontend/src/features/sources/`, `features/saved/`, `features/account/`, `features/pages/`, `frontend/src/admin/`.
- `frontend/src/hooks/useRetry.ts`, `usePolicy.ts`, `frontend/src/lib/sourceCounts.ts`, `duration.ts`.

## Alternatives considered

- **Look: minimal search page, editorial news front page, or developer console.** Each drawn on its own. Minimal was
  fast but plain, editorial needs fresh content every day, the console was too dense for beginners. Home and Results
  take parts of all three; News takes the front page.
- **Results: answer first** (the guide's answer as a block above the list). Good when the guide is sure, but the page
  waits several seconds for its most important part.
- **Results: the picks move to the top, a preview beside the list** (the first design). Fast to compare, but the list
  shifted under the reader when the answer came, and a phone and a desktop behaved differently.
- **Tokens generated from `tokens.json`** (a script or Style Dictionary). One file less to keep, but a build step, and
  the dark block and the aliases make a generator less obvious than a test.
- **Fonts: `geist` (Vercel).** Built for `next/font`, and its files carry every script (70 KB each). Static
  `@fontsource/geist`: three weights of two families cost more than one variable file each.
- **Monospace everywhere.** The strongest tech look, but slow to read on Privacy, Terms and the guide's reasons.
- **One font, Space Grotesk, everywhere.** No new font download, but the boards are drawn in Geist and Geist Mono.
- **Lists as terminal windows.** Strong look, but heavy on a phone. **Tabs on every width:** a wide screen hides half
  of what is new.
- **A Pause and Play button on the ticker** (and on the radar). It gives WCAG 2.2.2 a mechanism everyone sees, but it
  adds a control and a "Paused" text to a quiet page. Not chosen: hover, focus and a tap are the hold. The cost is
  in the Consequences.
- **A sideways crawl.** The titles are long; a row that slides up and stands can be read.
- **Sign in straight from the header.** One click less with one provider, but the visitor never sees why.
- **The guide on every search, or from four words.** Spends model calls and the daily AI answers on searches that
  are only a name.
- **A design flag per environment.** A build constant needs one image per environment, and staging would not test the
  image prod gets.

## Consequences

- Users see what the site does in one line, and the box never leaves the screen.
- The user reads results at once; when the guide answers, nothing in the list moves.
- The radar and the Sources page show the same data, so they cannot disagree. A visitor sees every source only over
  time (5 to 10 at a time); the legend links to the Sources page, which lists them all.
- A design token, a breakpoint, a class or a translation key cannot drift or rot unseen: a test fails first.
- With a mouse the pages are denser; on a touch screen nothing gets smaller.
- **Accessibility risk, WCAG 2.2.2.** Neither the ticker nor the radar has a pause button: stopping motion is a
  setting on the account page only (the owner's decision). Both stop on hover and on focus (the ticker also
  on a tap), and reduced motion stops both. WCAG 2.2.2 asks for a pause mechanism for motion longer than 5 s, so a
  touch user of the radar who is not signed in has none. Review before an accessibility audit (see Backlog).
- The radar's clock draws on every animation frame while the page is open and the mouse is not over it. It writes only
  CSS variables and one transform; the browser stops the frames on a hidden tab.
- Results show one place per result; comparing two results means opening both.

### Known limits

- The picks bar takes about 110 px also when the guide has nothing to say, and shows no step-by-step progress; there is no button to close the guide's answer. Two result layouts to build and test.
- **Accessibility risk (WCAG 2.2.2).** The ticker and the radar have no pause button: stopping motion is a setting on the account page only. Both stop on hover and on focus (the ticker also on a tap). WCAG 2.2.2 asks for a pause mechanism for motion longer than 5 s, so a touch user of the radar who is not signed in has none. Review it before an accessibility audit.

### Open points

- News has no topics and no excerpts; Events has no past events and no calendar.

## Diagrams

Home, the ticker, a search and the guide: [flow](../../diagrams/flow-0040-design-system.svg) · [sequence](../../diagrams/sequence-0040-design-system.svg)

## Backlog

- Features: [F7.2](https://github.com/silvioincalza/helper2.ai/issues/88) Design system.
- Open issues that change this decision:
  - [#316](https://github.com/silvioincalza/helper2.ai/issues/316) S7.2.3 The open points of design system version 1 closed
  - [#325](https://github.com/silvioincalza/helper2.ai/issues/325) S7.6.3 The WCAG 2.2.2 risk of the home lists decided in writing
