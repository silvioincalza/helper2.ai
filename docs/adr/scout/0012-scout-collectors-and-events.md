# 0012 — Scout collectors and events

**Depends on:** [0004](../foundation/0004-persistence-and-the-schema.md), [0006](../foundation/0006-outbound-calls-and-resilience.md), [0008](../foundation/0008-llm-integration.md), [0010](../foundation/0010-data-retention.md), [0011](0011-scout-runner.md), [0013](0013-discovery.md), [0014](0014-evidence-gated-enrichment.md), [0026](../search/0026-filters-facets-categories.md), [0027](../search/0027-live-web-store-first-daily-quota.md), [0033](../guide/0033-marking-ai-written-text-research-trail.md)

## Context
The Scout runner ([0011](0011-scout-runner.md)) needs collectors that find new AI resources without a user query. Each
source has its own API, terms, rate limits and cost. Some return clean structured data (registries, the Hugging Face
Hub). Others mix AI resources with unrelated software (GitHub awesome lists). A collector reports only sources it
actually queried, and states where its data comes from.

Two kinds of source need more care:

- **LinkedIn.** Much of the AI world is announced there first: a launch, a model, a meetup. LinkedIn gives us no
  allowed way in: its User Agreement forbids scraping, crawlers and bots, and its API has no search. Tavily already
  indexes public LinkedIn pages, under the same terms as our other Tavily searches. LinkedIn pages are about people
  more than any other source: a post has an author, and the author's name is in the title and in the URL.
- **Events.** The home page lists the next AI events: research conferences, industry events and community events
  (hackathons, meetups, talks, workshops). A wrong date is worse than no date: a visitor may plan a trip around it.
  Event dates are days in the future, not instants in the past. Helper2 never invents a date
  ([0004](../foundation/0004-persistence-and-the-schema.md)). Search results mention many events but rarely
  state their days in a form code can read.

## Decision
### The collectors
1. **One port, the same rules.** Every collector implements the `Collector` port. They live in the back office, in the
   sources module: `sources.<provider>.scout` next to a library provider (`github`, `huggingface`, `tavily`), and
   `sources.mcp`, `sources.aideadlines`, `sources.events`. All use `RunInterval`, the shared HTTP clients
   ([0006](../foundation/0006-outbound-calls-and-resilience.md)) and the item limit of the run (`max-items-per-run`,
   1000). Each collector's settings bind from `helper2.scout.<collector>` to a validated record
   (`CollectorProperties`): an invalid value stops startup. A collector returns hits, which the runner indexes, and
   candidates, which the runner sends to the review list ([0011](0011-scout-runner.md)). No collector calls another
   module itself.
2. **Official MCP Registry** (`mcp-registry`, every `H2_SCOUT_MCP_INTERVAL`, 1 h, as the registry asks of
   aggregators). `GET /v0.1/servers?limit=100&updated_since=<checkpoint>&cursor=…`, only entries marked latest, up to
   100 pages. Link: the repository, else the website, else the registry entry. A server published from a
   sub-folder of a repository (`repository.subfolder`) is linked at that folder (`/tree/HEAD/{folder}`), so the
   servers of one monorepo stay one resource each ([0021](0021-resource-identity-one-resource-per-repository.md) §2); the repository stays their repository link. The folder must be a
   plain path inside a GitHub repository (letters, digits, `.`, `_`, `-`, no `.` or `..` segment, at most 300
   characters); otherwise the server is linked at the repository. Platform `remote` when the server lists
   remotes, else `local`. The data is CC0. Incremental: it resumes with the registry's cursor. A server whose latest
   version has the status `deleted` is withdrawn: its record leaves the catalog on that run.
3. **Hugging Face trending** (`hf-trending`, 1 day). The top 100 models by `trendingScore`. The collector
   states each with its likes; the admission rule of the Scout indexes the ones with at least 20
   ([0020](0020-resource-vitality.md) §11). Each pass reads the current list; an unchanged model is a no-op in the catalog.
4. **Hugging Face provider organisations** (`hf-provider-orgs`, 1 day). The 20 newest models of each official org: Labs:
   `meta-llama`, `nvidia`, `mistralai`, `deepseek-ai`, `Qwen`, `google`, `openai`, `xai-org`, `microsoft`, `amazon`,
   `ibm-granite`, `CohereLabs`, `facebook`; Chinese labs: `zai-org`, `moonshotai`, `MiniMaxAI`, `baidu`, `tencent`,
   `ByteDance-Seed`, `stepfun-ai`, `internlm`, `openbmb`, `inclusionAI`, `XiaomiMiMo`; open research: `allenai`,
   `swiss-ai`, `LiquidAI`, `LGAI-EXAONE`, `upstage`; embeddings: `BAAI`, `jinaai`; image, video and speech:
   `stabilityai`, `black-forest-labs`, `Lightricks`, `Wan-AI`, `kyutai`. The org is a tag. An org that fails is
   logged and skipped; the others' models are kept, and the checkpoint stays so the next run asks again. The pass fails
   only when every org fails.
5. **Tavily news** (`live-web-scout`, every `H2_SCOUT_LIVE_WEB_INTERVAL`, 5 days). 7 fixed news queries ("new AI tool
   launched", "new MCP server released" …), `search_depth basic`, up to 20 results each, published since the day of the
   last complete pass (the last week on the first). One credit a query: about 42 credits a month, counted on the Scout's
   daily cap like every other live web collector (point 18, feature `scout-news`). Stored as NEWS,
   ARTICLE or EVENT with Tavily's excerpt as description. Off without `H2_LIVE_WEB_API_KEY`.
6. **GitHub awesome lists** (`github-awesome`, 1 day). Off without `H2_GITHUB_TOKEN` (GraphQL refuses anonymous calls).
   - A topic search with 6 topic pairs; only repositories named "awesome…" with at least 1000 stars; the top 10 lists.
     The lists they link to are read too ([0013](0013-discovery.md), deep search).
   - Each README (up to 5 MB) is parsed into linked repositories (`AwesomeListParser`): at most 1000 per pass and
     `H2_SCOUT_AWESOME_PER_RUN` (1000) per run, so a pass ends in one daily run; a pass cut short by
     `max-items-per-run` resumes after the last repository name it handled (entries sorted by name), so a list that
     changed between runs cannot make the pass skip or repeat entries. A repository GitHub does not find, an archived
     one and the old name of a renamed one are withdrawn ([0011](0011-scout-runner.md) §4).
   - Facts come through GraphQL, 50 repositories a call. A repository under 50 stars is not collected: that is the
     collection scope (`min-repo-stars`). The collector states `archived` and `fork` on each hit and drops neither
     itself: the vitality rule of the Scout decides ([0020](0020-resource-vitality.md)). An archived
     repository is refused and counted in `helper2.source.items.dropped{reason="archived"}`; a fork with stars or a
     recent push is indexed.
   - **The classifier.** The fast model (`SpringAiRepoClassifier`, `prompts/sources/classify-repos.txt`, thinking off)
     answers `ai` true or false and one allowed type, in batches of 25. The schema is in the prompt, not sent as
     `response_format`. Its own daily cap `H2_SCOUT_AWESOME_CLASSIFIER_DAILY_CALLS` (300), gauge
     `helper2.scout.classifier.budget.remaining`; calls are timed and their tokens counted with step `classify`. An id
     the model did not answer (error, spent budget, missing id), and every id in a run without a model, goes to
     keyword rules (`RepoClassifier.Heuristic`).
   - A failing list search or README is skipped; a GraphQL rate limit stops the pass (run `FAILED`, cursor kept).
   - Each list read is a resource too (ARTICLE, tag `awesome-list`), and its entry lines and headings become a search
     index ([0023](../search/0023-catalog-full-text-index.md)).
7. **Courses** (`course-scout`, every `H2_SCOUT_COURSES_INTERVAL`, 7 days; [0031](../guide/0031-guide-pipeline.md)).
   One Tavily search per learning platform (`helper2.scout.courses.platforms`, 10 platforms), restricted to its domain
   with `include_domains`, `search_depth basic`, published since the last complete pass (the last month on the first).
   One credit per platform and pass, about 43 credits a month, counted on the Scout's daily cap (point 18, feature
   `scout-courses`). A result is kept only when it is on the platform's host
   and path, about AI, and reads like a course or certification page. Kept pages are **candidates**, not hits: each
   becomes a `COURSE_CANDIDATE` review item, and nothing reaches the catalog until an admin accepts it. Off without
   `H2_LIVE_WEB_API_KEY`.
8. **ai-deadlines** (`ai-deadlines`, 1 day). One GitHub contents call lists the conference YAML files of
   `huggingface/ai-deadlines` (MIT). Each file is read only from `raw.githubusercontent.com`, without the GitHub token.
   One EVENT per conference: its next edition (point 22). A file that cannot be read or parsed, or whose download URL
   is on another host, is logged, skipped and counted in `helper2.source.items.dropped{source=ai-deadlines, reason}`
   (the exception type, or `ForeignDownloadUrl`). A conference with no upcoming edition is not counted. When the
   circuit breaker refuses a file, the pass ends there: the run fails and the next run tries again.
9. **Tavily discovery** (`discovery-<category>`): one collector per category of the AI world, 34 categories in 7
   families, list pages indexed, products reviewed ([0013](0013-discovery.md)).

### LinkedIn
10. **The collector `linkedin-scout`** (`LinkedInCollector`, "LinkedIn" in the product). We never call LinkedIn. One
    Tavily search per fixed query, every 7 days (`H2_SCOUT_LINKEDIN_INTERVAL`), with
    `include_domains: ["linkedin.com"]`, `topic: general`, `search_depth: basic` (1 credit), `max_results: 10`,
    `include_usage`; no raw content, no images, no answer; only new pages: `start_date` = the previous complete pass,
    or `time_range: month` on the first. It is built as the discovery collectors are ([0013](0013-discovery.md)): a
    skipped pass says why ("Not due yet: next pass on …", or the credit cap). The 20 queries are config
    (`helper2.scout.linkedin.queries`): launches of models, tools and MCP servers, company announcements, events.
11. **The URL path decides what a page is** (code, no model). The query string is tracking and is dropped.

    | Path | Becomes | Review label |
    |---|---|---|
    | `/pulse/{slug}` | ARTICLE | LinkedIn article |
    | `/posts/{slug}`, `/feed/update/{id}`, `/company/{name}/posts/{slug}` | NEWS | LinkedIn post |
    | `/company/{name}`, `/company/{name}/about`, `/showcase/{name}` | PRODUCT | LinkedIn company page |
    | `/events/{slug}` | EVENT | LinkedIn event |

    Anything else is dropped: personal profiles (`/in/`), jobs, learning, schools, topic pages, other hosts. There is
    no score or AI check: the queries are about AI, and a doubtful page is for the admin to decide.
12. **Every kept page waits for a person.** It is a `RESOURCE_CANDIDATE` review item, as discovery's are
    ([0013](0013-discovery.md) §8), with the tag `linkedin`. The batch has no hits: nothing from LinkedIn reaches the
    catalog without an admin accepting it.
13. **No author in the title.**
    - "Jane Doe on LinkedIn: text" keeps the text;
    - the site and what follows it go (" | LinkedIn", " - LinkedIn"), and so do the segments after the first " | "
      (an article's author, its comment count);
    - a post whose title names only its author ("Jane Doe's Post") takes the start of its text, cut at a word at 120
      characters;
    - an article whose title names only its author is dropped.

    The excerpt is cut at a word at 300 characters, shorter than other web excerpts (400). There is no author field. A
    post's URL and text can still name people: that is the page.
14. **Each collector proposes only its own types.** Accepting a candidate checks that its type is one a collector that
    found it declares (`SourceDescriptor.resourceTypes`, the first finder or any in `foundBy`), inside the candidate
    types (products, plus ARTICLE, NEWS and EVENT). `linkedin-scout` declares ARTICLE, NEWS, PRODUCT and EVENT; a
    discovery category its product type and ARTICLE. A candidate two categories merged keeps the most specific type
    ([0013](0013-discovery.md) §8), which only the second may declare: the check takes every finder.
15. **A LinkedIn event has no days.** Its page is not the event's own site, so its days are not stated days (point
    21). An accepted LinkedIn event is found by search and never listed in Next events. To list it, an admin adds the
    event to the curated list with the days from its own site.
16. **`linkedin.com` stays excluded from discovery** ([0013](0013-discovery.md) §4), so this collector is the only way
    LinkedIn pages come in, and no LinkedIn candidate is merged with a discovery one.
17. **Failed searches.** As in discovery: a search that fails alone is logged and skipped; the others, already paid
    for, are kept, and the next run makes only the searches the pass still misses (the resume cursor lists those that
    answered, [0013](0013-discovery.md) §11); the checkpoint moves once all have answered. When half or more fail, the
    pass fails and its checkpoint stays, so the next daily run repeats it. The cap is checked before each search, and every
    attempt counts its credits, a retried one too ([0013](0013-discovery.md) §10). The live web events scout follows
    the same rules.
18. **Credits are counted and capped with discovery's.** Every search adds its credits (Tavily's `usage.credits`, or
    1) to the day's global credit counter and to the Scout's own counter (`ScoutLiveWebCredits`,
    [0013](0013-discovery.md) §10), with the metric `helper2.live-web.credits{feature="scout-linkedin"}`. Past the
    Scout's daily cap (`H2_SCOUT_DAILY_CREDITS`, 40, shared with the discovery categories) the pass is left open
    ("Daily live web credit cap of the Scout reached") and tried again the next day. The same holds for the news
    (`scout-news`), the courses (`scout-courses`) and the events: no live web collector of the Scout spends a credit
    outside the cap. News and courses stopped by the cap between two searches keep what they found and leave the pass
    open: the resume cursor lists the searches done, the next run makes only the others over the same days, and the
    checkpoint moves when all are done. Each HTTP attempt of a retried search is counted; a search the circuit
    breaker refuses is not. On a day when the news (7 credits)
    and the courses (10) both run, discovery has 23 of the 40 left and finishes its categories the day after.
19. **Retention and titles.** `linkedin-scout` is a web source (`WebSources`, [0013](0013-discovery.md) §12): its titles
    are cleaned as page titles, and its NEWS and ARTICLE records go with the web retention.
20. **Alert.** `Helper2ScoutCollectorStale` fires when `linkedin-scout` has had no success for more than 8 days (its
    7-day interval plus a day), as for `course-scout`.

### Events
21. **Stated days only.** An event has a start (and maybe an end) only when a source states it.
    `DateFact.scheduledDay` stores the day at 00:00 UTC with DAY precision and the source id. Unlike a publication
    date it may be in the future; a day before 1995 is unknown. Source records keep `source_event_starts_at` and
    `source_event_ends_at`; on every upsert the writer copies the event days of the resource page's card into the
    resource (`ProjectedReconciliation`, [0004](../foundation/0004-persistence-and-the-schema.md) §3):
    `event_starts_at` is the earliest stated start and `event_ends_at` the latest stated end. Days a model read
    (point 26) count only when no source states any.
    A search hit from the catalog (`CatalogSearchSource.hits`) carries these days, and for each day the source of
    the record that states it (`ResourceRows.statedEventFacts`, the same rule), so an event found only in the catalog
    shows its days in the results, answers a "when" question, and days a model read keep their AI label. When the
    records state different days, the hit carries the other days with their sources and is marked as conflicting,
    as the resource page is; the merge keeps both (`DateFact.earliest`, `DateFact.latest`).
    The hit also carries the place, the kind and the format of the event, from the same read of the same records
    (`extra.location`, `extra.eventKind`, `extra.eventFormat`): the first one stated, among the records that state
    the days when a source states them, among all the records otherwise. So a place a model read is never shown
    beside days a source states, and when only a model read the event, its days carry the AI label. An event
    without days carries its place, kind and format too.
22. **Conferences from ai-deadlines** (point 8). For each conference file the collector keeps the edition with the
    soonest start that has not ended (end, or start when there is no end, on or after today, UTC). An edition without
    a start it can read ("TBA") is skipped, and so is one without an official link or a title. City and country come
    from the file.
23. **Curated industry events.** `docs/research/industry-events-2026-11-12.json` holds each event's official site, the
    page that states the dates and the exact excerpt, checked against the live page. `scripts/industry-events-seed.mjs`
    generates the repeatable migration `R__industry_events.sql` (32 events, from 2026-11-17 to 2027-11-08) and fails
    when a start date, a URL or an excerpt is missing. Each event gets an `eventDates` assertion (`CURATED`,
    `VERIFIED`) with the excerpt as evidence. The place is only what the page states; a country is kept only when the
    excerpt names it. Editions that share a site get their own ids (`?event=<edition slug>`).
    - **The list.** AI conferences and summits, the flagship conferences of AI, cloud and data vendors, and the large
      technology shows with an AI programme. Research conferences come from ai-deadlines, not from this file. An
      edition is listed only when its organiser's page states its days, and only until it ends.
    - **A research pass.** The file is named by the day its pages were read, and the script writes that day as the
      retrieval day of every record and excerpt; a file with no day in its name is refused. A pass writes the file anew:
      it reads every listed page again, drops the editions that are over and adds the next ones.
    - **An event that is over is not written.** The script leaves out an event whose last day is before the day in
      the file name, and the seed removes it like an event dropped from the file. The day comes from the file name,
      not from the clock, so the same file always gives the same seed. A repeatable seed runs again whenever it
      changes: without this rule it would write back a past event that retention
      ([0010](../foundation/0010-data-retention.md)) has deleted.
      `industry-events-seed.test.mjs` fails when the seed is not what the script writes from the file, when an event
      that is over is written, or when the file lists one.
    - **Descriptions.** One or two plain sentences per event: what it is, who runs it, who it is for, where. Every
      fact is on the organiser's page for this edition; a fact about a past edition is not used. The research pass
      drafts them, with a model. A person reads and approves each one in the pull request before the merge, so no
      description goes in unread. They are shown as curated text, with no AI label
      ([0033](../guide/0033-marking-ai-written-text-research-trail.md)). When the page does not carry an honest
      sentence about the edition, the event has no description: it is listed, but its page is not index-worthy
      ([0041](../frontend/0041-site-pages-for-crawlers-policy-endpoint.md) §5) and is not in the sitemap. `CuratedSeedTest` checks
      both. Today 26 events have a description and 6 have none.
24. **No curated list of community events.** Hackathons, meetups, talks and workshops come from the live web events
    scout (point 26), not from a list kept by hand: such an event is over a few weeks after it is announced, so a
    list is out of date as soon as it is written. The seed script reads a `kind` and a `format` from the industry
    file; a format with the excerpt that says so (`formatExcerpt`) also gets an `eventFormat` assertion (`CURATED`,
    `VERIFIED`) with that excerpt as evidence. No industry event has such an excerpt today.
25. **Kind and format.** `ResultCard.eventKind` (CONFERENCE, HACKATHON, MEETUP, TALK, WORKSHOP) and
    `ResultCard.eventFormat` (IN_PERSON, ONLINE, HYBRID) are set only when a source states them, never guessed; they
    travel in the source record's `extra`, like `location`. ai-deadlines is a list of conferences, so its events are
    `CONFERENCE`, with no format. Industry events are `CONFERENCE`, hybrid where the page says so. The home page shows
    them as one tag, for example "Hackathon · Online". Tavily news can store an EVENT, but never with days, so it is
    never listed.
26. **The live web events scout** (`live-web-events-scout`, "Upcoming AI events"): a model reads an event page's days,
    and code checks them against the page.
    - Weekly (`H2_SCOUT_LIVE_WEB_EVENTS_INTERVAL`, P7D). One Tavily search per query (`general`, `basic`, 10 results):
      upcoming AI hackathons, meetups and talks, conferences and summits, workshops. A result is kept when it is about
      AI and its link is plain https (`PageScope.plainUrlProblem`). At most 20 pages a pass
      (`H2_SCOUT_LIVE_WEB_EVENTS_MAX_PAGES`).
    - **On by default, one switch.** `H2_SOURCE_LIVE_WEB_EVENTS_SCOUT_ENABLED` is true; `false` switches it off.
      It runs only with `H2_LIVE_WEB_API_KEY` and an AI model. Tavily's written OK for scheduled searches whose
      pages we list is still to confirm (as for discovery and LinkedIn).
    - **The page is read with Tavily Extract** (basic, markdown, 4 pages a call). Search and Extract credits count on
      the Scout's daily cap (`H2_SCOUT_DAILY_CREDITS`, [0013](0013-discovery.md) §10). The cap is checked before the
      pass and before each Extract call; past it the pass keeps what it read, and its queries are not marked done:
      the pages left unread are found and read on the next run (their searches are paid again).
    - **A model reads the page.** The reasoning model, thinking off ([0008](../foundation/0008-llm-integration.md)),
      prompt `prompts/sources/read-event.txt`: how many events the page is about, the first and last day, and the
      words of the page that state them; kind, format and place, each with its own words. The page is untrusted data:
      it is cut to 12,000 characters, it cannot close its block, and the prompt says to ignore instructions in it. The
      prompt's version is a hash of its rendered text. One call per page, at most `H2_SCOUT_EVENT_READER_DAILY_CALLS`
      (40) a UTC day, inside the `llm-chat` circuit. When no model can be called (cap, circuit), the pass keeps what
      it read and stays open, so the next Scout run tries again.
27. **Code decides, not the model** (`EventQuoteGate`). An event is kept only when:
    - the page is about one event;
    - the date quote is 20 to 300 characters and is on the page (case, spaces, dashes and quotation marks folded);
    - code reads one range of days from the quote, with its year (`QuotedDays`: "12–14 March 2027", "March 12–14,
      2027", "30 March to 2 April 2027", ISO dates; not 12/03/2027, whose order is unclear);
    - those days are the model's days;
    - it starts between today and six months from today, and lasts at most 31 days.

    Kind, format and place are kept only when their own quote is on the page and names them (kind and format by their
    words, place as written). Otherwise they stay null, as in point 25.
28. **The evidence is kept with the record.** `source_record.extra.eventEvidence`: the date quote, the model id and the
    prompt version; `extra.query`: the search that found the page.
29. **Days a model read never win.** When another source states an event's days (ai-deadlines, the curated list), the
    model's are ignored: in the search merge (`ResultMerger`) and in the stored days
    (`ProjectedReconciliation`, which copies the card's days). The same for kind, format and place.
30. **They are labelled as AI.** EU AI Act Art. 50 and [0033](../guide/0033-marking-ai-written-text-research-trail.md): a
    checked quote does not make the days the source's own words; they are the model's reading. A result card says
    `eventOrigin: AI_EXTRACTED` when its start comes from this source, and the UI (Coming up, the Events page, the
    resource page) shows the days, kind and place with the AI label and `data-ai-generated`. Server pages leave days
    and place a model read out: their facts and schema.org data cannot carry the label.
31. **The API.** `GET /api/v1/events/upcoming?days=&size=` (`size` 1–60, default 6): resources of type EVENT with a
    start whose end (or start) is on or after today 00:00 UTC, soonest first, then by title. With `days` (1–366), only
    events that start before today 00:00 UTC plus `days`; the response says that end in `until` (null without
    `days`). An event already running stays listed until it ends. `total` counts every event in the window, so "Show
    all N" is a real number ([0040](../frontend/0040-design-system.md)). `Cache-Control: public, max-age=300`. The home
    page (`useEventsFeed` in `homeFeeds.ts`, shown by `HomeLists`) asks for 60 events of the next 90 days (`days=90&size=60`).

**An admin's edit wins.** A type an admin sets need not be declared by a finder, and the events seed overwrites only
the fields it asserts ([0038](../backoffice/0038-back-office-and-review.md)).

### Code
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/` — the collectors: `mcp/McpRegistryCollector.java`,
  `huggingface/scout/HuggingFaceTrendingCollector.java`, `HuggingFaceProviderOrgsCollector.java`,
  `tavily/scout/LiveWebNewsCollector.java`, `CourseCollector.java`, `LinkedInCollector.java`, `LiveWebEventsCollector.java`,
  `ScoutLiveWebCredits.java`, `github/scout/GitHubAwesomeCollector.java`, `AwesomeListParser.java`,
  `aideadlines/AiDeadlinesCollector.java` (`nextEdition`, `toHit`); `classifier/RepoClassifier.java`,
  `SpringAiRepoClassifier.java`; `config/scout/CollectorsConfiguration.java` (beans).
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/events/EventQuoteGate.java`, `QuotedDays.java`,
  `PageText.java`, `EventReading.java`, `EventReader.java`, `SpringAiEventReader.java`;
  `backend/apps/backoffice/src/main/resources/prompts/sources/read-event.txt`, `classify-repos.txt`.
- `backend/libs/core/src/main/java/ai/helper2/sources/config/CollectorProperties.java` — each collector's validated
  settings; `support/RunInterval.java`, `support/HttpClients.java` — shared by every collector.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/IndexAcceptedCandidate.kt` — the type check per
  finder; `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/ResourceCandidateJson.java` reads the finders.
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/WebSources.kt` — `FIXED`, `EVENTS_SCOUT`,
  `eventDaysReadByAi`; `DateFact.kt` — `scheduledDay`; `Vocabulary.kt` — `EventKind`, `EventFormat`.
- `backend/libs/core/src/main/java/ai/helper2/catalog/internal/ProjectedReconciliation.java` — the stored event days;
  `JdbcResources.java` — `upcomingEvents`; `catalog/usecase/ListUpcomingEvents.java`,
  `catalog/web/ResourcesController.java` — `listUpcomingEvents`.
- `backend/libs/core/src/main/kotlin/ai/helper2/search/ResultMerger.kt` — `eventSources`, `statedFirst`;
  `backend/libs/core/src/main/java/ai/helper2/search/web/CardMapper.java` — `eventOrigin`;
  `backend/apps/public/src/main/java/ai/helper2/site/pages/usecase/RenderPage.java` — `view`: AI-read days off server pages.
- `scripts/industry-events-seed.mjs`, `scripts/industry-events-seed.test.mjs`,
  `docs/research/industry-events-2026-11-12.json`,
  `backend/libs/schema/src/main/resources/db/migration/R__industry_events.sql`.
- `frontend/src/features/home/HomeLists.tsx`, `homeFeeds.ts`, `TermList.tsx`, `listParts.tsx`, `frontend/src/features/events/EventsPage.tsx`,
  `frontend/src/features/resource/DateTimeline.tsx`, `DateFactView.tsx` — the AI label.
- `backend/libs/platform/src/main/resources/helper2-shared.yaml`, `backend/apps/backoffice/src/main/resources/application.yaml` — `helper2.scout.*`, `helper2.sources.*`;
  `deploy/monitoring/helper2-rules.yaml` — `Helper2ScoutCollectorStale`, `Helper2LiveWebCreditsHigh`.
- Tests: `LinkedInCollectorTest`, `LiveWebEventsCollectorTest`, `EventQuoteGateTest`, `QuotedDaysTest`,
  `SpringAiEventReaderTest`, `IndexAcceptedCandidateTest`, `ResourceCandidateJsonTest`, `SourcesPropertiesBindingTest`,
  `CatalogPurgeExpiredDataIT`, the rule tests.

## Alternatives considered
- **Full crawl of GitHub or the Hub** — millions of items; bounded windows (trending, newest, curated lists) are enough.
- **Topic search alone for awesome lists** — returns prompt sites and apps; the "awesome" name filter removes them.
- **Keyword rules only for awesome lists** — they let generic developer tools through; the model is the first choice.
- **Scrape conference websites** — ai-deadlines already keeps the dates, under MIT, in one format.
- **The LinkedIn API or scraping LinkedIn** — the API has no search and needs partner approval; scraping is forbidden by
  the User Agreement, and LinkedIn enforces it in court.
- **LinkedIn as a live search source** — a credit on every search that misses the stored set, and posts shown before
  anyone checked them.
- **LinkedIn inside discovery** — discovery sorts by product-site rules; LinkedIn pages never match them.
- **A model sorts LinkedIn pages** — the path already says what a page is; a model call per result reads untrusted text
  before anything is checked.
- **Drop LinkedIn events and company pages** — simpler, but events are a main reason people use LinkedIn for AI, and a
  company page is often the first trace of a new product.
- **Event days from news text, with no check** — a model can misread or invent a day, and nothing checks it. The events
  scout keeps only days code finds in a quote of the page.
- **An admin confirms each event's days** (or the model suggests, the admin confirms) — no AI dates, but an admin reads
  every page each week.
- **Days from schema.org Event data** — exact when present, but Extract returns markdown without it, and many event
  pages have none. It is not read before the model (Known limits).
- **Show events without days as "soon"** — invents a timing claim.
- **Event days as instants with a time zone** — the sources state days, not times; DAY precision says so.
- **Read ai-deadlines live per page view** — slower and dependent on GitHub; the Scout reads it once a day.

## Consequences
- The index grows from registries, the Hub, news and curated lists, each record with its source disclosed.
- Intervals keep cost and load low. Tavily credits: about 540 a month (news ~42, courses ~43, discovery ~335, LinkedIn
  ~86, events ~35), out of the plan's 14,000 shared with live search; GitHub limits and Hub calls are bounded per pass.
- The classifier keeps non-AI repositories of awesome lists out; the keyword rules keep the collector working without
  a model.
- A course page, a LinkedIn page or a discovery product reaches the catalog only after an admin accepts it.
- LinkedIn's 20 searches fall on one day: with that day's discovery searches (about 12 credits) it stays under the cap
  of 40. If discovery grows, LinkedIn's day is the first to be skipped for the cap.
- Every listed event day can be traced to a source: the ai-deadlines file, an excerpt of the event's own page, or a
  quote a model found on the page and code checked, labelled as AI.
- A page that states a wrong date passes the gate: the gate proves the days are written on the page, not that the
  page is right. The same holds for every source.
- An event stays listed until its last day is over (UTC). The events seed removes curated events that are not in the
  research file: their curated assertions, evidence and source record, and the resource when no other record points
  to it.
- Retention ([0010](../foundation/0010-data-retention.md)): LinkedIn articles and posts are deleted 180 days after
  `retrieved_at` (`H2_RETENTION_WEB_NEWS`), like other web news; an accepted company page stays as long as it is
  listed; events are deleted ten years after they end (`past-events`), with their quote, curated ones included; a
  review item stays until it is decided, then 180 days.
- The Privacy page names LinkedIn pages as a source of public data, says that titles and excerpts can name people,
  and how to object. Search can filter LinkedIn pages by the `linkedin` tag.

### Known limits
- When two sources state two places, kinds or formats of one event, a catalog hit carries the one of the first source by name and the resource page the one of the first source by priority (the catalog source does not know the priorities); `IndexHitAndCardParityIT` writes from npm and github, where the two orders agree.
- When a catalog hit carries days a model read and a live source states other days in the same search, the merge counts both as stated (it reads the source of the hit, not of the day): the result shows the earliest start and the latest end, marked as conflicting.
- Hugging Face provider models get no enrichment (the list API has no description).
- Course pages wait for an admin each week, and an accepted course is added to the curated list by hand.
- LinkedIn: an accepted company page links to LinkedIn, not to the product's own site, until enrichment or an admin adds it. An accepted LinkedIn event has no days and is not in Next events until it is added to the curated list by hand. The collector's 20 searches fall on one day, so on that day the Scout's daily cap of 40 leaves discovery about 20 credits ([0013](0013-discovery.md)).
- Live web events scout: Tavily has not confirmed in writing that its permission covers scheduled searches whose pages we list. The gate proves the days are written on the page, not that the page is right. It reads only English month names and ISO dates. A page about several events gives none. schema.org Event data is not read before the model is called.
- An event whose source gives no day is not listed; event days can be a few hours off far from UTC; conference dates are as correct as ai-deadlines; curated events need a research pass to stay current.
- A curated event leaves the catalog ten years after its last day, like every event ([0010](../foundation/0010-data-retention.md)); until then search still finds it. A research file that still lists an event ended more than ten years ago puts it back when the seed changes, until the next daily purge: the seed script does not skip it.

### STRIDE
| Threat | Risk | Control |
|---|---|---|
| Spoofing | Anyone can post on LinkedIn or publish an event page | LinkedIn pages and courses wait for a person; on accept the URL is checked again, the type must be one a finder declares, tags must be tag-shaped. An event's days need a quote on its own page. |
| Tampering | A file of ai-deadlines served from another host; a hostile event page | Files only from `raw.githubusercontent.com`; event pages cut, fenced as untrusted, and checked by `EventQuoteGate`, not by the model. |
| Repudiation | Not knowing where a date or a candidate came from | Every event keeps its source and its quote (`eventEvidence`); a candidate keeps every finder. |
| Information disclosure | LinkedIn pages name people | Fixed queries with no user data; no author field, names out of titles, 300-character excerpts, 180-day retention for posts and articles, removal on request. |
| Denial of service / cost | Credits, a slow source | Fixed intervals, `max_results` 10, a circuit breaker and retry per collector (`collector-<id>`), the Scout's daily credit cap, the event reader's daily cap. |
| Elevation of privilege | — | Only an admin accepts (back office, VPN, [0036](../identity/0036-identity-and-the-admin-host.md)); the admin page renders a candidate as text and links only `https://` URLs. |

OWASP LLM Top 10: the classifier and the event reader read untrusted text, fenced and with instructions to ignore it;
their answers are checked by code (allowed types, the quote gate). An accepted LinkedIn post reaches a model in
enrichment and in the guide as untrusted text, as every web text does ([0013](0013-discovery.md)).

### AI architecture check
| Layer | Status |
|---|---|
| Model | Covered: the fast model classifies, the reasoning model reads event pages, NVIDIA only ([0008](../foundation/0008-llm-integration.md)); without a model the classifier falls back to keyword rules and the events scout is off. |
| Agent framework, Memory, Browser & Computer Use | Deferred, safe: no agent; Tavily reads the pages, we run no browser. |
| Orchestration | Covered: each collector is a step of the Scout run, with its own cursor, interval and failure isolation ([0011](0011-scout-runner.md)). |
| Tool & API | Covered: each source behind its collector and its resilience policy; LinkedIn only through Tavily's index. |
| Knowledge & RAG | Covered: what the collectors bring is what search and the guide retrieve. |
| Data | Covered: hits as source records with their provenance; event evidence in `extra`; seeds as repeatable migrations. |
| Guardrails | Covered: `EventQuoteGate`, path rules for LinkedIn, the classifier's allowed values, review for candidates. |
| Observability | Covered: run records, dropped-item counters, credit metrics, classifier and reader budgets, stale-collector alerts. |
| Evaluation | Gap, deferred: no graded set for the classifier or the event reader; the gate's keep rate is not measured. |
| Deployment | Covered: settings with safe defaults; the events scout on by default, one switch. |
| Security & Identity | Covered: no new endpoint; the GitHub token never goes to `raw.githubusercontent.com`. |
| Human-in-the-loop | Covered: courses, LinkedIn pages and discovery products wait for an admin. |
| Cost & Reliability | Covered: intervals, the Scout's daily credit cap, the classifier's and the reader's daily caps, circuit breakers. |

### Points for a lawyer
1. The legal basis for post authors' names in LinkedIn pages (legitimate interest, GDPR Art. 6(1)(f) and Swiss nFADP
   Art. 31), and the Art. 14 notice through the Privacy page.
2. Does Tavily's written OK cover pages Tavily indexed from LinkedIn, and scheduled searches whose pages we list
   (discovery, LinkedIn, the events scout)?

## Diagrams
Collectors: [flow](../../diagrams/flow-0012-scout-collectors.svg) · [sequence](../../diagrams/sequence-0012-scout-collectors.svg) ·
next events: [flow](../../diagrams/flow-0012-next-events.svg) · [sequence](../../diagrams/sequence-0012-next-events.svg) ·
LinkedIn: [flow](../../diagrams/flow-0012-linkedin-via-tavily.svg) · [sequence](../../diagrams/sequence-0012-linkedin-via-tavily.svg) ·
live web events: [flow](../../diagrams/flow-0012-live-web-events-scout.svg) · [sequence](../../diagrams/sequence-0012-live-web-events-scout.svg)

## Backlog
- Decides: [F2.2](https://github.com/silvioincalza/helper2.ai/issues/32).
- Open issues that change it:
  - [S2.2.6](https://github.com/silvioincalza/helper2.ai/issues/191): the `linkedin-scout`, `course-scout` and live web events collectors fixed.
  - [S2.13.1](https://github.com/silvioincalza/helper2.ai/issues/218): source requests and more registries.
  - [S2.14.1](https://github.com/silvioincalza/helper2.ai/issues/219): live collectors for hackathons, meetups, talks and workshops.
  - [S9.6.1](https://github.com/silvioincalza/helper2.ai/issues/340): each source's terms read again for commercial use.
