# 0042 — Redesign: design system version 4

**Status.** Accepted. **Replaces parts of:** [0040](0040-frontend-design.md) (decisions 1, 2, 4 and 7).
**Builds on:** [0015](../guide/0015-ai-guide-evidence-gate.md), [0017](../guide/0017-guide-cost-guard.md),
[0025](0025-frontend-one-box.md), [0038](../guide/0038-ai-content-marking.md)

## Context
[0040](0040-frontend-design.md) gave the site its first full design. In November 2026 we reviewed it page by page with
one goal: everyone must understand at once what helper2.ai helps with. We drew every page again, with all its states,
the dark theme and the phone layout, and had the drawings reviewed for user journeys, accessibility and fit with the
API. The boards are in [docs/design/redesign](../../design/redesign); the rules are the design system, version 4
([docs/design/system](../../design/system/README.md)).

The code still runs version 3. A move of this size cannot land in one pull request, so this record also says how the
two live side by side.

## Decision
1. **One font.** Space Grotesk (400, 500, 600, self-hosted, SIL Open Font License) for the logo, headings, controls,
   labels, meta lines, the News and Events terminals and running text, as in [0040](0040-frontend-design.md). Code
   and commands use the system's monospace font, so nothing else downloads. The boards were drawn in Geist Mono and
   Geist; on 2 November 2026 Space Grotesk was kept. The boards are not the font reference.
2. **Home: the title, the radar, a pinned search box, two terminals.** The title says what the site is: "The metasearch
   for the AI ecosystem." Behind it a radar draws one dot per enabled source from GET /sources: ring = mode, colour =
   last check, distance = speed or freshness, size = new this week (`newThisWeek`; the Helper2 index counts the whole
   index, and the index by meaning has none, so its dot keeps the default size). The search box stays pinned under the header while
   the page scrolls; on phones the title folds away so New this week and Coming up take the screen. The two lists are
   terminal windows: a command, one line per item, Show all expands in place.
3. **Results: one column.** Picks first, then all results. No preview panel: a row opens the resource page. Filters
   open a panel with counts and an Unknown choice. Coverage ("N of M sources answered") sits under the list and opens
   to one line per source.
4. **The guide starts on its own only for a question.** Four words or more start it, as in
   [0017](../guide/0017-guide-cost-guard.md). A shorter search shows **Ask the guide** instead of a picks area. Every
   guide state has a drawing where the picks sit: working, needs one more detail, no pick, model not available, busy,
   timeout or a stalled stream, daily AI answers used up.
5. **AI text is marked per field.** Each AI-written field has its own label and its own `data-ai-generated`;
   checked quotes and buttons are never inside a marked element ([0038](../guide/0038-ai-content-marking.md)).
6. **Nothing moves on its own for more than 5 seconds.** Terminal lines print in once. The radar sweeps once and
   the cursor blinks three times; all of it ends within 5 seconds. Under reduced motion nothing moves. So no Pause
   button is needed (WCAG 2.2.2).
7. **The design asks the API for what it needs.** Where a drawing needs data the API does not have, the data becomes
   a feature, not a cut in the design: `newThisWeek` per source, `limits` on GET /me, `total` on the recent and
   upcoming responses, an Unknown count per facet. Sign-in comes in two phases: GitHub and Google first; passkey,
   email link and password after their own ADR, a threat model and the Privacy page.
8. **Version 4 tokens now, pages one by one.** `tokens.css` has the version 4 colours and names; the version 3 names
   are gone. The layout sizes (`maxw`, `box-height`, `header-height`) keep their version 3 values until the pages
   that use them move. Each page moves in its own pull request, Home first. When the last page has moved, the
   version 3 components go. No design flag: a page is either version 3 or version 4.

## Alternatives
- **Keep version 3 and polish it.** Cheaper, but it kept the problems the review found: a preview panel that is
  hard on phones, moving boards without a pause, counts typed into the page.
- **Monospace everywhere.** The strongest tech look, but slow to read on Privacy, Terms and the guide's reasons.
- **Geist Mono for the interface, Geist for reading.** Drawn on the boards. Not kept on 2 November 2026: Space
  Grotesk stays, and no new font downloads.
- **The guide on every search.** Simpler, but it spends model calls and the daily AI answers on searches that are
  only a name ([0017](../guide/0017-guide-cost-guard.md)).
- **One big pull request.** Fastest to finish, hardest to review and to roll back.

## Consequences
- Users see what the site does in one line, and the box never leaves the screen.
- The radar and the Sources page show the same data, so they cannot disagree.
- The API has the fields the drawings need: `newThisWeek` per source on GET /sources, `limits` on GET /me, and
  `total` on GET /recent and GET /events/upcoming (which also takes `days`). The Unknown count per facet is still open.
- For a while the site shows two designs. Each page is consistent within itself; the header and footer move first,
  with Home.
- The design system's component folders still show version 3 until each component is rebuilt.

Flowchart: [flow-0042-redesign](../../diagrams/flow-0042-redesign.svg). Sequence:
[sequence-0042-redesign](../../diagrams/sequence-0042-redesign.svg).
