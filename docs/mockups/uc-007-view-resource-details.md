# Screens and states: View Resource Details

Screen notes for UC-007 and Set it up of UC-006: what the page shows, step by step and in each state. The behaviour is in the use case.

## Evaluate a result (row → resource page)

1. A result row shows: type, host (a link to the resource itself, new tab), a date (updated, created, or first seen by Searchavo), the title (a heading, so a screen reader can jump from result to result) with Save next to it, the description (labelled when AI wrote it), a guide pick with its reason and quote, and the sources that list it.
2. The row opens the resource page, one column: **← Back to results** (back in history, so the list keeps its place). Opened from another list the link names it: **← Back to news**, **← Back to events**, **← Back to saved**, **← Back** from Home; "Back to search" when opened directly. Then the title, under it type · host · the same date as the row, one primary button **Open on host ↗** (it says it opens a new tab), Save (named "Save" or "Saved" with the title, like the word it shows), and **Set it up** only for things you install or run. Under Set it up one line says how many AI setups are left today before the click, e.g. "18 of 20 AI setups left today. Steps that are already written do not count." The numbers come from `limits.setupGuides` on `/me`; the page reads `/me` again after each guide it asks for. The limit is per address, so a signed-in user sees it too. With none left the line says so in words, that written guides still open, and when new ones are back (the next 00:00 UTC, in the user's own clock); the button still opens. No line when the setup guide has no model, or for a test environment's high ceiling while guides are left. Then **Why the guide picked it** (only when opened from a pick), **From the source** (the checked sentence highlighted), **At a glance** (only the Use and Build facts that have a value, each once; a value the AI read carries its AI label; then one line for the rest: "Not stated by any source: pricing, platforms"), the three dates, the AI summary with citations per claim (only when there is one), relationships (verified vs unverified candidates kept apart), then **Where this information comes from**, folded and one click away: each source, its mode and when it was read, a link to Sources, every field with its origin ("Not stated" per field), the facts with origin, verification and evidence (field names in plain words, dates as on the rest of the page), the source records (provenance), and one line when there is no AI summary and why.
3. User follows "Open original" (new tab, `noopener noreferrer`), hostname visible before clicking.

**Success:** user can answer "who says so, and when?" for every displayed fact.
**Failure cases:** sources disagree → "Sources disagree on license: MIT (GitHub) vs Apache-2.0 (npm)" — never silently resolved; resource not yet indexed → 404 with link back to search.

| State | Behavior |
|---|---|
| Loading | Skeleton sections in the shape of the page. Opened from a list, the title shows at once and only the rest waits. **Set it up** shows skeleton steps under "Reading its documentation… (up to half a minute)". |
| Success | The user can answer "who says so, and when?" for every displayed fact. |
| Empty | A fact no source states is not shown as a fact: one line says "Not stated by any source: pricing, platforms". No AI summary: one line says so and why. |
| Partial | Sources disagree: "Sources disagree on license: MIT (GitHub) vs Apache-2.0 (npm)", never silently resolved. |
| Error | The resource is not indexed: 404 with a link back to search. |
