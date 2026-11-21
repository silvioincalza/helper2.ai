# Screens and states: Follow New Resources and Events

Screen notes for UC-008: what the page shows, step by step and in each state. The behaviour is in the use case.

## See what is new (home lists)

**Entry:** the home page, right under the box and the examples; or **News** or **Events** in the header, which open their own pages (`/news`, `/events`, the News and Events pages below).

1. **New in the catalog**: what came into our index in the last 7 days, by the date each source states (Published, Updated), or First seen when no source states one. Tools and research only.
2. **Coming up**: AI events in the next three months, soonest first, only those whose source states the dates. Under the date it says how soon ("Today", "In 2 weeks"); an event that started before today says "on now" and "until Nov 8" (only "on now" when the source states no last day). An event whose dates an AI model read on its own page (the live web events scout, ADR 0012) says "Extracted by AI" next to its date, here, on the Events page and on its resource page; its date, kind and place are marked as AI output in the HTML.
3. On a wide screen the two lists are two tables side by side, like `ls -l`: Date · Type · Title · Source and When · Type · Event · Where. Below 920px they stack; below 768px they become one panel with two tabs, `> new in the catalog 38` and `coming up 12`, with one log row per item. `/#news` and `/#events` in an address open their tab (`/#news` opens New in the catalog); the header links go to the News and Events pages. News means only stories: the News page.
4. A row is its title, never a description (no AI-written text). The title opens the resource page ([UC-007](uc-007-view-resource-details.md)). A date shows its year only when it is not the current year.
5. A list shows its first six rows. `$ helper2 new --all · show all 24` opens the rest in place; **show fewer** closes them. The heading shows the API's total. When the API counts more than it sends (it sends at most 60), the heading still shows the total and **show all** counts only the rows it opens. A list with more rows than it shows moves like a news ticker: one row every 4 seconds. Hover, focus or a tap holds it, and on a phone a scroll of the page. It moves again when the mouse or the focus leaves, or after the tap. Under reduced motion, or with motion off on the account page, it never moves. Coming up ends with **All events ›**, to the Events page.

**Exit:** a row's name to its resource page, the box, the examples.

| State | Behavior |
|---|---|
| Loading | Skeleton rows, one for each row the list shows. |
| Success | Each list shows its first six rows under the API's total. |
| Partial | The API counts more than it sends (at most 60): the heading shows the total and **show all** counts only the rows it opens. |
| Empty | The list says there is nothing in its window ("Nothing with a stated publication date in the last 7 days."). The box above is the next step. |
| Error | An alert under the list's heading with Try again. |

## Read the AI news and plan for events

Entry: **News** and **Events** in the header (in the menu on a phone), **All events ›** under Coming up on Home, or a
shared link with its filters (`/news?site=…`, `/events?kind=HACKATHON&format=ONLINE`).

1. **News** is the last week of AI news our web search found, laid out like a front page: one lead story, six under
   it in two columns, five in **Latest** beside them (so the two columns end at about the same height), then **Earlier this week**. Headlines, site and date only; a headline opens the
   resource page, which links to the story at its source. The site chips filter the page; the choice is in the
   address, so Back and a shared link show the same stories.
2. **Events** is every event in the next year whose source states its dates, grouped by month, each month with its
   count. Chips filter by kind and by online or in person, each with its count; the choices are in the address. A row
   says its day, kind, place and how soon ("in 2 days", "on now"). Days, kind and place an AI model read carry
   "Extracted by AI" and the machine-readable mark.
3. Exit: a row opens its resource page; the header goes anywhere else.

| State | Behavior |
|---|---|
| Loading | Under the heading, a skeleton of the page: headlines on News, the chips, a month and its rows on Events. Announced once. |
| Success | News: one lead story, six under it, five in **Latest**, then **Earlier this week**. Events: every event with stated dates in the next year, by month. |
| Partial | Not specified. |
| Empty | "No AI news in the last week." / "No events with stated dates in the next year." |
| A filter with nothing | "No story from this site in the last week." / "No event matches these filters."; the chosen chip stays, so it can be turned off. |
| Error | "Could not load the news." / "Could not load the next events.", with **Try again**. |
