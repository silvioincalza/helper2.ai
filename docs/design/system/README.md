# Helper2.ai

The design system for every part of Helper2.ai: the search-and-ask box, results, the AI guide, What's new, resource pages, saved resources, the sources page and admin. The look is warm paper with one saffron accent, a geometric display face for headings, a sans for doing things, and a mono for names that came from a source. The reference design is the "Prototype · One page, one box" board.

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
| Status band | `console` + `status-ok` / `status-warn` | Source status bar; the only dark band in the light theme. |

- **Spacing:** 4-based steps `space-1` to `space-7` (4, 8, 12, 16, 24, 32, 48px). Pills `space-2` apart, card parts `space-3`, page sections `space-5`, gutters `space-6`, the home hero `space-7`.
- **Controls:** at least `target-min` (44px) tall. The search box and its buttons are `box-height` (56px).
- **Focus:** a 3px `focus` outline with 2px offset on every control.
- **Elevation:** none. Objects are set apart by `border`, never by shadow.
- **Width:** the page column stops at `content-max`. On phones the header stops being sticky, the nav takes its own row and the box's two buttons share a row.
- **Themes:** light and dark; every token has both values. The header button cycles system → light → dark: "system" follows the device setting and is the default.

## Type tags

Every resource carries one type tag. Fill and text differ in lightness, not just hue.

| Type | Fill | Text |
|---|---|---|
| Paper | `tag-paper-bg` | `accent-deep` |
| Model | `tag-model-bg` | `tag-model-ink` |
| News, Article | `tag-news-bg` | `tag-news-ink` |
| MCP server | `tag-mcp-bg` | `checked-ink` |
| Product, Agent, Prompt, Skill, Library, Dataset, Event, Other | `surface-tint` | `ink-2` |

## Logo

The mark is an ink-stroke radar (outer ring, faint inner ring, one sweep line, one filled four-point AI spark: "AI on your radar") on a 40px `accent` tile with 12px corners (`assets/Logos/radar-mark.svg`). In the header it sits 16px (`space-4`) left of the wordmark "helper2.ai", set in `display` at 32px in `ink`. Under the wordmark, 2px below, sits the tagline **AI ON YOUR RADAR**: `sans` 12px, weight 600, uppercase, 0.08em tracking, in `muted`. The radar strokes and spark follow `on-accent` (ink), on the `accent` tile in both themes. Never recolor the radar, add effects, or drop the wordmark in the header.

## Iconography

Apart from the radar mark, icons are small 14px stroke glyphs that sit beside words, never alone: check, cross, clock, pause, warn, info, bookmark, external link, refresh. Use `→` for "go further" and `↗` for an external source. Never emoji. An icon-only button (close `×`) carries an `aria-label`.

## Home page

Minimal by design: the hero, then one list.

1. **Hero:** radar rings behind, the eyebrow **AI ON YOUR RADAR**, the two-part `display` title (second half in `accent`), the lede, the Omnibox, one `muted` hint line, three example Pills with short labels, and one link line "Searches N disclosed sources →".
2. **What's new:** at most 5 FeedRows with a topic select.

How the guide answers and the "Never guessed" rules live on the Mission page, not the home page. Once the box has a query, the hero compacts and the guide panel and results take its place.

## Components

Components are plain HTML plus the classes in `components/bundle.css` (prefix `h2-`). There is no JavaScript bundle. Each folder has guidelines and a live preview.

| Component | Used for |
|---|---|
| **Header** | Logo, nav and the one box on every page. |
| **Footer** | Two centered rows on every page: coverage disclaimer, copyright, Mission, Privacy, GitHub and license links. |
| **Omnibox** | The search-and-ask box. |
| **Button** | Primary, secondary, text and icon-only buttons. |
| **Pill** | Result type filters and example needs. |
| **Badge** | Type tags, source mode, outcome, verification, freshness, Quote checked. |
| **ResultCard** | A search result, a saved resource, the head of a resource page. |
| **FactList** | Facts and dates with their source, Unknown when no source stated one. |
| **GuidePick** | One of the guide's picks with its checked quote. |
| **FeedRow** | One item in What's new on the home page. |
| **Banner** | Notices, partial results, empty and error states. |
| **SourceStatus** | The status band of live sources on every search. |
