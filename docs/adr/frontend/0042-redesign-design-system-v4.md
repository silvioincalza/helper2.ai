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
2. **Version 4 tokens next to version 3.** `tokens.css` holds the version 4 colours (light and dark), sizes and the
   two font stacks as `--v4-*`, from `docs/design/system/tokens.json`. Only the parts that have moved use them: the
   header, the footer and Home, under the `.v4` class. A v3 page never picks one up by accident.
3. **Header and footer.** The header: the logo (mark, wordmark, "AI on your radar"), News, Events, Saved, Sign in or
   the account. Up to 720 px: the logo and a menu button; the menu holds New this week, Coming up, Saved, Sources,
   Mission, Sign in and the theme; Escape closes it. On the pages without their own box, the box sits in the header.
   The footer: the logo, one row of links (Mission, Sources, Privacy, Terms, GitHub ↗, the licence ↗, the release ↗,
   the theme switch), then one line: what is searched, no ads, who and where.
4. **Home: the title, the radar, a pinned search box, two lists.** The title says what the site is: "The metasearch
   for the AI ecosystem.", with one line under it. Behind it a radar draws one dot per enabled source from GET /sources:
   ring = mode (live inside, index and curated outside), colour = last check, distance = speed (live) or freshness
   (index), size = `newThisWeek` (the Helper2 index counts the whole index; the index by meaning has no count of its
   own, so its dot keeps the default size). Each source has a fixed slot, left or right; below 900 px only
   the rings show. The search box, its hint and the "Try:" examples stay pinned under the header while the page
   scrolls; once pinned, the hint, the examples and the radar legend fold away. On phones (720 px or less) the title
   folds away after 24 px of scroll and opens again at the top.
5. **New this week and Coming up.** On a wide screen two tables side by side, like `ls -l`: DATE · TYPE · TITLE ·
   SOURCE and WHEN · TYPE · EVENT · WHERE, in Geist Mono with dashed rules, a running event "on now" in green. Below
   768 px one panel with two tabs, `> new this week N` and `coming up N`, and log rows (date and `[type]`, the title in
   Geist, the host or how far away and where). Coming up asks GET /events/upcoming for `days=90`, so the window and
   its `total` are the server's. Six rows show; `$ helper2 news --all · show all N` opens the rest in
   place. The tabs are a real tablist (arrow keys, Home, End); the tables have column headers.
6. **Nothing moves on its own for more than 5 seconds.** The radar sweeps once (4.5 s). The first six rows print in
   once, 120 ms apart. The cursor after each list's name blinks three times (4.8 s) and stays on. The loading lines
   pulse three times. Under reduced motion nothing moves, and nothing folds with an animation. So no Pause button is
   needed (WCAG 2.2.2). A test fails on any CSS or inline animation that repeats for ever.
7. **Every number comes from data.** "One search · N AI sources" counts the enabled sources of GET /sources (and is
   not shown before the answer); the dot labels show the response time or the age of the last check; the list counts
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
    moved, the version 3 tokens, components and Space Grotesk go. No design flag: a page is either version 3 or 4.

## Alternatives
- **Fonts: `geist` (Vercel).** The upstream package, but built for `next/font` (it imports `next/font/local`) and
  its files carry every script (70 KB each). Static `@fontsource/geist`: 13 KB per weight, so three weights of two
  families cost more than one variable file each.
- **Lists as terminal windows** (the first boards). Strong look, but heavy on a phone and the window frame adds
  nothing. **Tabs on every width** (option 2): one component, but a wide screen hides half of what is new.
- **Monospace everywhere.** The strongest tech look, but slow to read on Privacy, Terms and the guide's reasons.
- **The guide on every search.** Simpler, but it spends model calls and the daily AI answers on searches that are
  only a name ([0017](../guide/0017-guide-cost-guard.md)).
- **One big pull request.** Fastest to finish, hardest to review and to roll back.

## Consequences
- Users see what the site does in one line, and the box never leaves the screen.
- The radar and the Sources page show the same data, so they cannot disagree.
- The API has the fields the drawings need: `newThisWeek` per source on GET /sources, `limits` on GET /me, and
  `total` on GET /recent and GET /events/upcoming (which also takes `days`). The Unknown count per facet is still open.
- For a while the site shows two designs and loads two font families on the pages still on version 3. The header and
  footer are already version 4 on every page.
- The initial JavaScript is at its budget (165.0 of 165 KiB gzip, from 162.9): the radar and the phone tabs load on
  demand to fit. The next page that moves must make room first.
- What is not built yet is on the [roadmap](../../roadmap.md) (section 6).

Flowchart: [flow-0042-redesign](../../diagrams/flow-0042-redesign.svg). Sequence:
[sequence-0042-redesign](../../diagrams/sequence-0042-redesign.svg).
