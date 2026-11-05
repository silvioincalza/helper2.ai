# 0042 — Redesign: design system version 4

**Status.** Accepted. Built for the header, the footer and Home; the other pages follow (decision 8).
**Replaces parts of:** [0040](0040-frontend-design.md): decisions 1 and 2 now; 4 and 7 when Results moves.
**Builds on:** [0015](../guide/0015-ai-guide-evidence-gate.md), [0017](../guide/0017-guide-cost-guard.md),
[0025](0025-frontend-one-box.md), [0038](../guide/0038-ai-content-marking.md)

## Context
[0040](0040-frontend-design.md) gave the site its first full design. In November 2026 we reviewed it page by page with
one goal: everyone must understand at once what helper2.ai helps with. We drew every page again, with all its states,
the dark theme and the phone layout, and had the drawings reviewed for user journeys, accessibility and fit with the
API. The boards are in [docs/design/redesign](../../design/redesign); the rules are the design system, version 4
([docs/design/system](../../design/system/README.md)). For New this week and Coming up we drew three more options
(`TechLists`, `MobileLists` in the same folder) and chose the tables on wide screens and the tabs on phones.

A move of this size cannot land in one pull request. The header, the footer and Home move first; this record says what
they do now and how version 3 and 4 live side by side until the other pages move.

## Decision
1. **Two fonts with clear jobs.** Geist Mono for the logo, headings, controls, labels, meta lines and the two lists:
   the tech look. Geist for running text (paragraphs, list titles, the guide's reasons), because long text in a
   monospace font reads slowly. Both are the variable fonts from `@fontsource-variable/geist` and
   `@fontsource-variable/geist-mono` (SIL Open Font License), bundled by Vite and served from our own origin: no
   third-party request, and the CSP stays `font-src 'self'`. One file per script covers every weight; an English page
   loads only the Latin files (29 KB and 23 KB), and the build preloads those two. The pages still on version 3 keep
   Space Grotesk (400, 500, 600) until they move.
2. **One set of version 4 tokens.** `tokens.css` holds the version 4 colours (light and dark), shadows and sizes
   under their own names, from `docs/design/system/tokens.json`; the version 3 names are gone. Only what the moved
   parts do differently carries `--v4-`: the box height (60 px) and the two font stacks. The header is version 4 on
   every page, so `header-height` is its 74 px. The pages still on version 3 keep `maxw`, `box-height` and Space
   Grotesk until they move.
3. **Header and footer.** The header: the logo (mark, wordmark, "AI on your radar"), News, Events, Saved, Sign in or
   the account. Up to 720 px: the logo and a menu button; the menu holds New this week, Coming up, Saved, Sources,
   Mission, Sign in and the theme; Escape closes it. On the pages without their own box, the box sits in the header.
   The footer: the logo, one row of links (Mission, Sources, Privacy, Terms, GitHub ↗, the licence ↗, the release ↗,
   the theme switch), then one line: what is searched, no ads, who and where.
4. **Home: the title, the radar, a pinned search box, two lists.** No line above it. The title is one `h1` on two lines: "helper2.ai"
   (accent ink, same size and weight), then "The metasearch for the AI universe.", with one line under it. One
   heading names the site, for search engines and screen readers. Around it sits a radar that works like a navigation
   radar (PPI): full rings around the title, and a beam (a sweep wedge) that turns once every 5 s.
   - **Which sources.** Only external sources (`external` on GET /sources): Helper2's own index, its search by meaning
     and its curated list are left out. Live sources sit on the two inner rings, the Scout collectors on the two
     outer ones. Each source has its own fixed spot from the order GET /sources lists it in, left and right of the
     title, never under the title or the box, and never touching another name (`radarLayout.ts`). A dot never slides.
   - **3 to 5 at a time.** It starts with 3 random sources, 0.6 s apart. Then every 1.6 s one step: while it grows, a
     new random source that is not on the radar comes, up to 5; then the oldest leaves, one at a time, down to 3; then
     it grows again (`radarRotation.ts`).
   - **Painted by the beam.** A dot shows only when the beam passes its angle: full opacity, a short pop (scale 1.35,
     back to 1 in 0.25 s) and a glow in its own colour (gone in 0.6 s); then it fades to 0.22 until the next pass. A new
     source shows the first time the beam passes it. A source that leaves is not painted again and fades to 0 within
     3 s (`radarClock.ts`). The beam and the dots run on one clock, a `requestAnimationFrame` loop that sets the beam's
     angle and each dot's opacity, pop and glow from the time since the beam last passed it. So they cannot drift apart.
   - **What a dot says.** Under each dot only its short name (`shortName`). Every dot is filled with its colour: orange
     (accent) answering, amber slow or limited, red failed (grey when skipped or not checked yet). The colours are 3:1 or
     more on the page in both themes (a test checks it). Size = `newThisWeek`; null keeps the default size. Colour is
     never the only key: each dot is a link to the Sources page whose accessible name and tooltip say the state
     ("GitHub, live, answered in 410 ms, 14 new this week"; "Awesome, from our index, last check failed"). A dot that
     is not on the radar is hidden from screen readers and the keyboard.
   - **The legend** under the box: "3-5 of N sources at a time" (N from GET /sources), a filled swatch for answering,
     slow or limited and failed, and a link to the Sources page.
   - **When it holds still.** The clock stops while the search box has focus: the beam and the dots hold still. Under
     reduced motion, or for a signed-in user who turned "Radar: move between sources" off on the account page
     (`preferences.radarMotion` on GET /me, saved with PATCH /me/preferences, ADR
     [0023](../edge/0023-identity-and-admin.md)), there is no clock: the beam stands still and a fixed random set of 4
     shows at full opacity. Up to 900 px only the rings and the beam show.

   The search box, its hint, the "Try:" examples and the radar legend stay pinned under the header while the page
   scrolls; once pinned, the hint, the examples and the legend fold away. On phones (720 px or less) the title and
   the radar fold away after 24 px of scroll and open again at the top.
5. **New this week and Coming up: one list component.** Both lists are one `TermList`, in a terminal style without
   terminal windows: a `$ helper2 news · N` line (N = `total` from the API), `ls -l` columns with dashed rules, the
   titles in Geist and the rest in Geist Mono. On a wide screen two tables side by side: DATE · TYPE · TITLE · SOURCE
   and WHEN · TYPE · EVENT · WHERE, a running event "on now" in green. Below 768 px one panel with two tabs,
   `> new this week N | coming up N`, and log rows (date and `[type]`, the title, the host or how far away and
   where). Coming up asks GET /events/upcoming for `days=90`, so the window and its `total` are the server's. Six rows
   show; `$ helper2 news --all · show all N` opens the rest in place. Loading, empty and error (with Retry) are the
   same in both. The tabs are real buttons in a tablist (`role="tab"`, `aria-selected`, arrow keys, Home, End), 44 px
   tall; the tables have column headers. No blinking cursor, no lines that print in.
6. **Nothing moves on its own for more than 5 seconds, with one exception.** No CSS animation repeats: the loading
   lines pulse three times and stop. Under reduced motion nothing moves, and nothing folds with an animation. A test
   fails on any CSS or inline animation that repeats for ever. The exception is the radar (decision 4). It moves by a
   JavaScript clock, not a CSS animation; it holds still while the search box has focus, stands still under reduced
   motion, and a signed-in user can turn it off.
7. **Every number comes from data.** The radar legend's "3-5 of N sources at a time" counts the enabled external
   sources of GET /sources, the same sources the radar shows (13 on staging; not shown before the answer); the dot names say the response time
   or the age of the last check; the list counts
   are `total` on GET /recent and GET /events/upcoming (never fewer than the rows sent); the hint's "4 words" is the
   constant the box uses to tell a question from a search. One small reader (`lib/v4Fields.ts`) takes `newThisWeek`,
   `total` and `limits` and says what the page does when a value is null: a dot of the default size, the rows as the
   count, no quota line.
8. **The guide starts on its own only for a question, and the quota is in sight.** Four words or more start it, as in
   [0017](../guide/0017-guide-cost-guard.md). Where the guide answers, the visitor sees today's AI answers ("2 of 3 AI
   answers left today"; at none, when they come back and that search still works), from `limits.guideQuestions` on
   GET /me. When the guide has no model the field is null, nothing is spent, and the line is not shown.
9. **Accessibility.** Every control is at least 44 px. The focus ring (3 px ink outline) never looks like the
   selected line (saffron underline in the header, an ink folder tab in the tabs). The Search button is never
   disabled: with nothing to send it puts the cursor in the box. Key hints (`/`, `↵`) show only where there is a
   keyboard. Text and controls meet AA in light and dark (muted 7.5:1 and 8:1, links 5.5:1 and 8.7:1, control borders
   3.3:1 and 3.9:1). The axe test runs on Home in both themes, on a wide screen and on a phone with the menu open.
10. **Version 3 and 4 side by side, page by page.** Each page moves in its own pull request. When the last page has
    moved, the version 3 components, sizes and Space Grotesk go, and the `--v4-` sizes and fonts lose their prefix.
    No design flag: a page is either version 3 or 4.

## Alternatives
- **Fonts: `geist` (Vercel).** The upstream package, but built for `next/font` (it imports `next/font/local`) and
  its files carry every script (70 KB each). Static `@fontsource/geist`: 13 KB per weight, so three weights of two
  families cost more than one variable file each.
- **Lists as terminal windows** (the first boards). Strong look, but heavy on a phone and the window frame adds
  nothing. **Tabs on every width** (option 2): one component, but a wide screen hides half of what is new.
- **Monospace everywhere.** The strongest tech look, but slow to read on Privacy, Terms and the guide's reasons.
- **One font, Space Grotesk, everywhere.** Kept on 2 November 2026: no new font downloads. The header, the footer
  and Home are built in Geist Mono and Geist, as the boards draw them. Which one stays for every page is open on the
  [roadmap](../../roadmap.md).
- **The guide on every search.** Simpler, but it spends model calls and the daily AI answers on searches that are
  only a name ([0017](../guide/0017-guide-cost-guard.md)).
- **One big pull request.** Fastest to finish, hardest to review and to roll back.

## Consequences
- Users see what the site does in one line, and the box never leaves the screen.
- The radar and the Sources page show the same data, so they cannot disagree.
- WCAG 2.2.2 asks for a way to pause moving content for every user. Signed-in users can turn the radar off; anonymous
  visitors have only reduced motion and the hold while the search box has focus. The radar never ends on its own and
  guests have no switch. This is a known limit, on the [roadmap](../../roadmap.md).
- The radar shows 3 to 5 sources at a time, so a visitor sees every source only over time. The legend links to the
  Sources page, which lists them all.
- The radar's clock draws on every animation frame while the page is open and the box has no focus. It writes only
  CSS variables and one transform, and React renders only when a source comes or goes; the browser stops the frames
  on a hidden tab.
- The API has the fields the drawings need: `newThisWeek` per source on GET /sources, `limits` on GET /me, and
  `total` on GET /recent and GET /events/upcoming (which also takes `days`). The Unknown count per facet is still open.
- For a while the site shows two designs and loads two font families on the pages still on version 3. The header and
  footer are already version 4 on every page.
- The initial JavaScript is 165.9 KiB gzip, over the 165 KiB budget, with the examples from the pool on Home: the
  radar, the phone tabs, the Sources page and the account page already load on demand. Making room is on the
  [roadmap](../../roadmap.md).
- What is not built yet is on the [roadmap](../../roadmap.md) (section 6).

Flowchart: [flow-0042-redesign](../../diagrams/flow-0042-redesign.svg). Sequence:
[sequence-0042-redesign](../../diagrams/sequence-0042-redesign.svg).
