# Screens and states: Search the AI Ecosystem

Screen notes for UC-001: what the page shows, step by step and in each state. The behaviour is in the use case.

## Search once (one box)

1. User lands on the home page: one headline says what the site is for, the search box sits under it, and the New in the catalog and Coming up lists show at first sight; what helper2.ai does follows below them. With the keyboard, Tab goes from the header to the search box, then to the examples, the radar dots and the lists. A focused item is never under the header or the pinned box.
2. There is one box and no scope to choose: the same search covers tools, models, MCP servers, libraries, papers and news.
   Under the box, **Try:** shows four examples, picked at random on each visit from a pool: two for people who use AI
   ("Take meeting notes"), two for people who build with it ("Vector database"). An example searches its words with
   its audience path (`path=USE_AI` or `BUILD_WITH_AI`), which changes the ranking only. It does not ask the guide, so
   it spends no daily AI answer. The results page shows that path as chosen under **Best for**. New words in the box
   drop the path. Every example in the pool has a clearly fitting
   result in the top 3. The pool comes from the server (`GET /home/examples`); when it fails, takes more than 800 ms,
   or has fewer than two examples for a path, the page uses its built-in list. While it waits, the row keeps its
   height and shows nothing to click, so the page does not jump. An example a model drafted and a person checked has
   an "AI draft" label; its tooltip says "Drafted with AI, checked by a person".
3. Types a name ("ollama"), keywords ("mcp postgres"), an intent ("I want to summarise PDFs"), or a use case ("traduzione").
4. On the home page, Enter (or the button) opens the results on `/search`; focus moves to the results. There, results follow typing when it pauses (or on Enter). The first search shows skeleton rows and one "Searching the listed sources…" line; a later search keeps the previous results on screen, at full contrast, under the same "Searching the listed sources…" line until the new ones arrive. When a search is done, the result count ("38 results") is said once, politely, to a screen reader; a search with no result says its own message instead.
5. Results arrive in one response, once every source has answered or hit its timeout. They load on demand: until the results view arrives, skeleton rows of the list's shape hold its place under the box, so nothing moves. One column, no preview: the heading says **Results** over `$ helper2 search · 104` (the total from the API), with Filters, the sort and List / Timeline next to it, then the type tabs (All, the four types with most results, More). On a phone (up to 520px) List / Timeline, Filters and the sort share one row: the heading and the words "Sort by" are not shown, a screen reader still gets both, and the count is on the All tab. Each row shows the type, host and date ("updated Oct 2026", or "first seen" when no source states one), the title, the description and the sources that list it. A row names each source once: our catalog searched by words and by meaning is one name, "Helper2.ai catalog"; the tiles under the list keep the two apart. The description is plain text: Markdown code fences and backticks from a source are not shown. Nothing is selected, also on a phone. The title opens the resource page; a ctrl or cmd click opens it in a new tab. The host in the row is a link that opens the resource itself in a new tab (`noopener noreferrer`), so one click from the list reaches the tool. **Save** sits next to the title and keeps the result without leaving the list. Back returns to the same place in the list. Under the list, **Show more** adds the next page in place: focus moves to its first row and "20 more results" is said once, politely. The page is in the URL (`page`), so Back and a reload come back to the same rows. Under it, a line states e.g. "4 of 5 sources answered" and a tile per source shows its outcome, response time or cache age.
6. Under the type tabs, **Best for** offers three chips: **Anyone**, **Apps I can use** (`path=USE_AI`) and **Build
   with AI** (`path=BUILD_WITH_AI`). A chip searches again with that path: it changes what ranks first and never
   hides a result. The path is in the URL, so Back returns to the previous choice. New words in the box go back to
   **All**: the choice was for the old words.

**Key decisions:** whether to refine, or to ask the guide. **Success:** relevant result in the first page; user understands where results came from.

| Variant | Behavior |
|---|---|
| Loading | The first search shows skeleton rows of the list's shape and one "Searching the listed sources…" line; a later search keeps the previous results on screen, at full contrast, under the same line until the new ones arrive. |
| Success | Aggregated, deduplicated results; every configured source listed with outcome. |
| Empty | "No results from the sources searched" + the list of sources + suggestions: remove filters, ask the guide, "Try different or fewer words." Never "nothing exists". |
| Partial | One quiet line under the heading, no banner: "2 of 5 sources did not answer · Which?". On a phone (up to 520px) it is shorter, "2 sources did not answer · Which? · Try again", so it stays on one line; up to 359px Which? and Try again go together on the line below. **Which?** lists those sources and why, in words: took too long, returned an error, paused after a rate limit, paused after repeated errors, busy. **Try again** shows only when a retry can help (an error, a timeout, a busy source); never while a source is paused or its circuit is open. The line is not announced to screen readers: it is read with the heading. Coverage marked `partial`. A source that is paused or failing counts as queried; a source left out on purpose (disabled, no web credits left) does not, and its tile says why. This line and the one under the results use the same two numbers. |
| Stale cache | Source failed live but a recent cached response exists: results shown, badge "cached 14 min ago — live check failed". |
| Found only in a list | A result whose own text does not match but an awesome list's words around it do ([ADR 0023](../architecture/decisions/ADR-0023-catalog-full-text-index.md)) ranks below direct matches and shows one small muted line under its text: "Found in owner/awesome-llm under Tools > Vector databases". No AI label: it is the list's own words. A list itself can be a result (type Awesome list), found by its headings. |
| All failed / API down | A prominent error state: "No source responded", not an empty result; no fake results. It offers Try again, except when every source is paused (a retry cannot help then), and a link to the Sources page. |
| Rate-limited | Source marked RATE_LIMITED (not hidden), others continue. |
| Invalid input | A query longer than 200 characters is cut to 200 in the URL and the request (the guide takes 3–500); an empty box plus Enter returns to the empty home page. The **Search** button is never disabled: pressed with an empty box, it puts the cursor in the box and says "Type what you need first." under it (read as the box's description and as a polite status), and searches nothing. The API answers an invalid `q` with 400 problem+json. |

## Refine: filter and sort

1. User opens **Filters**: in place under the heading on a wide screen, a drawer over the page on a phone (up to 720px) with **Close** and **Show N results**; Escape and Close give focus back to Filters. The button says how many are on ("Filters · 2 on"). The type tabs choose one type. The use cases the search detected sit under the tabs as one-tap chips. On a phone each row of chips is one line that scrolls sideways: the side with more chips fades out, and Tab brings each chip into view. Filters: category, type, use case, tech level, source, license, pricing, platform, open-source, updated-after.
2. Each facet shows counts and a **Not stated** bucket for results where the source did not say. **Category** (ADR 0026) groups the categories by family (Everyday apps, Developers, ...), lists only the ones with results or already chosen, and ends with **No category yet**. It says when the names are loading or could not be loaded (with **Try again**). A chosen category is a chip and is in the URL (`?category=coding`); asking the guide on that page picks only in those categories. A resource page lists its categories, each a link to a search filtered by it.
3. Sort: Relevance (default), Newest (publication date), Recently updated. Results with unknown dates sort last and are labeled "date unknown".
4. **Timeline** shows the same results on a time axis, `$ helper2 search --timeline · N`: one month beside a line of dots, newest first, by the date each item was created as its source states it. Items with no stated creation date sit in one folded group at the end, "Date unknown · N · Show them" (a button with `aria-expanded`); the date Helper2 found them is never used. **Show more** works as in the list.

**Key decision:** include or exclude unknowns. Selecting a value excludes unknowns; selecting "Not stated" includes them explicitly.
**Failure case:** filters produce zero results → empty state lists the active filters with one-click removal.

| State | Behavior |
|---|---|
| Loading | Category shows skeleton lines where the names will be; a screen reader hears "Loading categories…". |
| Success | Each facet with its counts and a **Not stated** bucket; the list in the chosen order, unknown dates last. |
| Empty | The filters produce zero results: the empty state lists the active filters with one-click removal. |
| Partial | As in the search above: the line under the heading says which sources did not answer. |
| Error | The category names could not be loaded: Category says so, with **Try again**. |
