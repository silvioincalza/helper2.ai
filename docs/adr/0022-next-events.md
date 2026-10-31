# 0022 — Next events: stated event days, ai-deadlines and curated industry and community events

**Status.** Accepted (2026-10-27). **Builds on:** [0004](0004-persistence-and-provenance.md), [0010](0010-filters-facets-dates-timeline.md), [0018](0018-scout-runner.md), [0020](0020-scout-collectors.md)

## Context
The home page lists the next artificial intelligence (AI) events: research conferences, industry events and community
events (hackathons, meetups, talks and workshops, online or in person). A wrong date is worse than no
date: a visitor may plan travel around it. News results often mention events but rarely state their days in a form
code can read. Event dates also differ from other dates in the catalog: they lie in the future, and they are days,
not instants. Helper2 never invents a date ([0004](0004-persistence-and-provenance.md)).

## Decision
1. **Stated days only.** An event has a start (and maybe an end) only when a source states it. `DateFact.scheduledDay`
   stores the day at 00:00 Coordinated Universal Time (UTC) with DAY precision and the source id. Unlike publication dates it may be in the future;
   a day before 1995 is treated as unknown. Source records keep `source_event_starts_at` / `source_event_ends_at`;
   `ResourceStore.refreshEventDates` sets the resource's `event_starts_at` to the earliest stated start and
   `event_ends_at` to the latest stated end, on every upsert.
2. **Conferences from ai-deadlines** ([0020](0020-scout-collectors.md)). For each conference file the collector keeps
   the edition with the soonest start that has not ended (end, or start when there is no end, on or after today, UTC).
   An edition without a parseable start ("TBA") is skipped, and so is one without an official link or a title. City and
   country come from the file.
3. **Curated industry events.** `docs/research/industry-events-2026-10-27.json` holds each event's official site, the
   page that states the dates and the exact excerpt, checked against the live page on 2026-10-27.
   `scripts/industry-events-seed.mjs` generates the repeatable migration `R__industry_events.sql` (21 events today,
   from 2026-11-09 to 2027-09-20) and fails when a start date, web address (URL) or excerpt is missing. Each event gets an `eventDates`
   assertion (`CURATED`, `VERIFIED`) with the excerpt as evidence. The place is only what the page states; a country is
   kept only when the excerpt names it. Editions that share a site get distinct ids (`?event=<edition slug>`).
4. **Curated community events.** `docs/research/community-events-2026-10-27.json` lists hackathons, meetups, talks and
   workshops in the same shape, plus `kind` and, when the page states it, `format` with the excerpt that says so
   (`formatExcerpt`). The same script, run with the set `community`, generates `R__community_events.sql`
   (fingerprint `community-events-v1`, tag `community-event`), so each seed removes only its own dropped events.
   A stated format also gets an `eventFormat` assertion (`CURATED`, `VERIFIED`) with that excerpt as evidence.
5. **Kind and format.** `ResultCard.eventKind` (CONFERENCE, HACKATHON, MEETUP, TALK, WORKSHOP) and
   `ResultCard.eventFormat` (IN_PERSON, ONLINE, HYBRID) are set only when a source states them, never inferred; they
   travel in the source record's `extra`, like `location`. ai-deadlines is a list of conferences, so its events are
   `CONFERENCE`, with no format. Industry events are `CONFERENCE`, hybrid where the page says so. On the home page's
   Events board the kind is the row's type tag (else "Event") and the format comes before the place, for example
   "Online" or "Hybrid · Zurich".
6. **Other events have no dates.** Tavily stores some news items as EVENT, but never with stated days, so they are never listed.
7. **Application programming interface (API).** `GET /api/v1/events/upcoming?size=` (1–60, default 6): resources of type EVENT with a stated start whose
   end (or start) is on or after today 00:00 UTC, soonest first, then by title. `Cache-Control: public, max-age=300`.
   The home page's Events board (`HomeBoards`, [0040](0040-frontend-design.md)) asks for 60 (about 45 curated events
   today, plus conferences) and lists those that start within 90 days of the response's `generatedAt`, each with
   when it starts ("in 13 days", "On now").

## Consequences
- Every listed date can be traced to a source: the ai-deadlines file or an excerpt of the event's own page.
- An event whose source gives no day is not listed, even if it is well known.
- An event stays listed until its last day is over (UTC), which can be a few hours off for visitors far from UTC.
- Curated events need a research pass to stay current. The events seed removes curated events that are no longer
  in the research file: their curated assertions, evidence and source record, and the resource itself when no other
  source record points to it.
- Conference dates are only as correct as the community-maintained ai-deadlines files.

## Alternatives considered
- Extract dates from news text with the model — a model can misread or invent a day; nothing to verify it against.
- Show events without dates as "soon" — invents a timing claim.
- Store event days as instants with the event's time zone — the sources state days, not times; DAY precision says so.
- Call ai-deadlines live per page view — slower and dependent on GitHub; the Scout reads it once a day.

## Code
- `backend/src/main/java/ai/helper2/sources/aideadlines/AiDeadlinesCollector.java` — `nextEdition`, `toHit`.
- `backend/src/main/kotlin/ai/helper2/shared/DateFact.kt` — `scheduledDay`.
- `backend/src/main/java/ai/helper2/catalog/internal/ResourceStore.java` — `refreshEventDates`, `upcomingEvents`.
- `backend/src/main/java/ai/helper2/catalog/usecase/ListUpcomingEvents.java`, `catalog/web/ResourcesController.java` — `listUpcomingEvents`.
- `scripts/industry-events-seed.mjs`, `docs/research/industry-events-2026-10-27.json`,
  `docs/research/community-events-2026-10-27.json`, `backend/src/main/resources/db/migration/R__industry_events.sql`,
  `R__community_events.sql`.
- `backend/src/main/kotlin/ai/helper2/shared/Vocabulary.kt` — `EventKind`, `EventFormat`.
- `frontend/src/components/HomeBoards.tsx` — the Events board.

## Diagrams
[flow](../diagrams/flow-0022-next-events.svg) · [sequence](../diagrams/sequence-0022-next-events.svg)

## Roadmap
- [Live collectors for hackathons, meetups, talks and workshops](../roadmap.md#6-search-and-catalog)
- [`ListUpcomingEvents` makes two queries per item; batch them](../roadmap.md#6-search-and-catalog)
- [Count skipped ai-deadlines files in a metric](../roadmap.md#6-search-and-catalog)
