# 0067 — Live web events scout: a model reads an event page's days, code checks them against the page

**Status.** Accepted. **Builds on:** [0014](../guide/0014-llm-integration.md), [0018](0018-scout-runner.md), [0020](0020-scout-collectors.md), [0022](0022-next-events.md), [0033](../foundation/0033-resilience-policies.md), [0037](../search/0037-tavily-store-and-daily-quota.md), [0038](../guide/0038-ai-content-marking.md), [0051](0051-tavily-discovery.md), [0056](0056-evidence-ladder.md). **Amends:** [0022](0022-next-events.md) (§6 and its first rejected alternative).

## Context
Coming up lists the next AI events. Its events come from ai-deadlines (research conferences) and from our curated
lists, which need a research pass by hand to stay current. Hackathons, meetups, talks and workshops are announced
every week, and the list misses most of them.

Tavily finds their pages. It does not give their days: a search result has a title, a link and an excerpt. ADR 0022
lists an event only when a source states its days, because a wrong date can make a visitor plan a trip for nothing.
It rejected reading dates from text with a model: a model can misread or invent a day, and there was nothing to check
it against.

## Decision
1. **A new Scout source, `live-web-events-scout` ("Upcoming AI events").** Weekly (`H2_SCOUT_LIVE_WEB_EVENTS_INTERVAL`,
   P7D). One Tavily search per query (`general`, `basic`, 10 results): upcoming AI hackathons, meetups and talks,
   conferences and summits, workshops. A result is kept when it is about AI and its link is plain https
   (`PageScope.plainUrlProblem`). At most 20 pages a pass (`H2_SCOUT_LIVE_WEB_EVENTS_MAX_PAGES`).
2. **Off by default.** `H2_SOURCE_LIVE_WEB_EVENTS_SCOUT_ENABLED` is false: Tavily's written OK for scheduled searches
   whose pages we list is still to confirm (as for discovery and LinkedIn). It also needs `H2_LIVE_WEB_API_KEY` and
   an AI model; without a model it is off.
3. **The page is read with Tavily Extract** (basic, markdown, 4 pages a call). Search and Extract credits count on the
   Scout's daily cap (`H2_SCOUT_DAILY_CREDITS`, ADR 0051). The cap is checked before the pass and before each Extract
   call; past it the pass keeps what it read.
4. **A model reads the page.** The reasoning model, thinking off (ADR 0014), prompt `prompts/sources/read-event-v1.txt`:
   how many events the page is about, the first and last day, and the words of the page that state them; kind,
   format and place, each with its own words. The page is untrusted data: it is cut to 12,000 characters, it cannot
   close its block, and the prompt says to ignore instructions in it. One call per page, at most
   `H2_SCOUT_EVENT_READER_DAILY_CALLS` (40) a UTC day, inside the `llm-chat` circuit. When no model can be called
   (cap, circuit), the pass keeps what it read and stays open, so the next Scout run tries again.
5. **Code decides, not the model** (`EventQuoteGate`). An event is kept only when:
   - the page is about one event;
   - the date quote is 20 to 300 characters and is on the page (case, spaces, dashes and quotation marks folded);
   - code reads one range of days from the quote, with its year (`QuotedDays`: "12–14 March 2027", "March 12–14,
     2027", "30 March to 2 April 2027", ISO dates; not 12/03/2027, whose order is unclear);
   - those days are the model's days;
   - it starts between today and six months from today, and lasts at most 31 days.
   Kind, format and place are kept only when their own quote is on the page and names them (kind and format by
   their words, place as written). Otherwise they stay null, as in ADR 0022 §5.
6. **The evidence is kept with the record.** `source_record.extra.eventEvidence`: the date quote, the model id and the
   prompt version; `extra.query`: the search that found the page.
7. **AI-read days never win.** When another source states an event's days (ai-deadlines, the curated lists), the
   model's are ignored: in the search merge (`ResultMerger`) and in the stored days (`ResourceStore.refreshEventDates`).
   The same for kind, format and place.
8. **They are labelled as AI.** EU AI Act Art. 50 and ADR 0038: a checked quote does not make the days the source's
   own words; they are the model's reading. A result card says `eventOrigin: AI_EXTRACTED` when its start comes from
   this source, and the UI (Coming up, the Events page, the resource page) shows the days, kind and place with the AI label and `data-ai-generated`. Server pages leave AI-read days and place out: their facts and schema.org data cannot carry the label.

## Consequences
- Coming up can list hackathons, meetups, talks and workshops without a research pass, each with a quote of its own
  page that states its days.
- A page that states a wrong date passes the gate: the gate proves the days are written on the page, not that the
  page is right. The same holds for every source.
- Credits: about 4 search and 4 Extract credits a week, ~35 a month, inside the Scout's daily cap.
- Retention: events are deleted 365 days after they end (ADR 0034, `past-events`), with their quote.

## Alternatives considered
- **Admin confirms the days** (review queue, as LinkedIn): no AI dates, but an admin has to read every page each week.
- **Suggested days, admin confirms**: less typing, the same admin time.
- **Dates from structured data (schema.org Event)**: exact when present, but Extract returns markdown, without it,
  and many event pages have none. Kept as a later step before the model.
- **The model's days without a quote check**: ADR 0022's rejected alternative; a model can invent a day.

## Code
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/tavily/LiveWebEventsCollector.java` — `collect`, `pagesToRead`, `readAll`, `readPage`, `eventHit`.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/events/EventQuoteGate.java`, `QuotedDays.java`, `PageText.java`, `EventReading.java`.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/events/EventReader.java`, `SpringAiEventReader.java`, `backend/apps/backoffice/src/main/resources/prompts/sources/read-event-v1.txt`.
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/WebSources.kt` — `EVENTS_SCOUT`, `eventDaysReadByAi`.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/ResultMerger.kt` — `eventSources`, `statedFirst`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/ResourceStore.java` — `refreshEventDates`.
- `backend/apps/common-web/src/main/java/ai/helper2/search/web/CardMapper.java` — `eventOrigin`.
- `backend/apps/public/src/main/java/ai/helper2/pages/usecase/RenderPage.java` — `view`: AI-read days off server pages.
- `frontend/src/components/home/TermList.tsx`, `listParts.tsx`, `frontend/src/pages/EventsPage.tsx`,
  `frontend/src/components/Timeline.tsx`, `DateFactView.tsx` — the AI label.

## Diagrams
[flow](../../diagrams/flow-0067-live-web-events-scout.svg) · [sequence](../../diagrams/sequence-0067-live-web-events-scout.svg)

## Roadmap
- Read schema.org Event data before calling the model.
- Measure in the first weeks how many pages a pass reads and how many events the gate keeps; drop a query that
  brings only noise.
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
