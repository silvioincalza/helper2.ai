# 0012 — Scout collectors and events

**Status:** Accepted

**Date:** 2026-11-14

**Depends on:** [0004](ADR-0004-persistence-and-the-schema.md), [0006](ADR-0006-outbound-calls-and-resilience.md), [0008](ADR-0008-llm-integration.md), [0010](ADR-0010-data-retention.md), [0011](ADR-0011-scout-runner.md), [0013](ADR-0013-discovery.md), [0014](ADR-0014-evidence-gated-enrichment.md), [0026](ADR-0026-filters-facets-categories.md), [0027](ADR-0027-live-web-store-first-daily-quota.md), [0033](ADR-0033-marking-ai-written-text-research-trail.md)

**Requirements:** C-008, C-015

## Context
The Scout runner ([0011](ADR-0011-scout-runner.md)) needs collectors that find new AI resources without a user query. Each
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
  ([0004](ADR-0004-persistence-and-the-schema.md)). Search results mention many events but rarely
  state their days in a form code can read.

## Decision
### The collectors
1. **One port, the same rules.** Every collector implements the `Collector` port. They live in the back office, in the
   sources module: `sources.<provider>.scout` next to a library provider (`github`, `huggingface`, `tavily`), and
   `sources.mcp`, `sources.aideadlines`, `sources.events`. All use `RunInterval`, the shared HTTP clients
   ([0006](ADR-0006-outbound-calls-and-resilience.md)) and the item limit of the run (`max-items-per-run`,
   1000). Each collector's settings bind from `helper2.scout.<collector>` to a validated record
   (`CollectorProperties`): an invalid value stops startup. A collector returns hits, which the runner indexes, and
   candidates, which the runner sends to the review list ([0011](ADR-0011-scout-runner.md)). No collector calls another
   module itself.
2. **Official MCP Registry** (`mcp-registry`, every `H2_SCOUT_MCP_INTERVAL`, 1 h, as the registry asks of
   aggregators). `GET /v0.1/servers?limit=100&updated_since=<checkpoint>&cursor=…`, only entries marked latest, up to
   100 pages. Link: the repository, else the website, else the registry entry. A server published from a
   sub-folder of a repository (`repository.subfolder`) is linked at that folder (`/tree/HEAD/{folder}`), so the
   servers of one monorepo stay one resource each ([0021](ADR-0021-resource-identity-one-resource-per-repository.md) §2); the repository stays their repository link. The folder must be a
   plain path inside a GitHub repository (letters, digits, `.`, `_`, `-`, no `.` or `..` segment, at most 300
   characters); otherwise the server is linked at the repository. A server without a valid folder is the resource
   of its registry name, and joins its repository only when its name holds the repository's name
   ([0021](ADR-0021-resource-identity-one-resource-per-repository.md) §5). Platform `remote` when the server lists
   remotes, else `local`. The data is CC0. Incremental: it resumes with the registry's cursor. A server whose latest
   version has the status `deleted` is withdrawn: its record leaves the catalog on that run.
3. **Hugging Face trending** (`hf-trending`, 1 day). The top 100 models by `trendingScore`. The collector
   states each with its likes; the admission rule of the Scout indexes the ones with at least 20
   ([0020](ADR-0020-resource-vitality.md) §11). Each pass reads the current list; an unchanged model is a no-op in the catalog.
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
     The lists they link to are read too ([0013](ADR-0013-discovery.md), deep search).
   - **Lists an administrator adds.** On `/admin/settings`, "Lists to read": a GitHub repository, as `owner/name` or
     `https://github.com/owner/name`, or a web page, with an optional reason (`ManageListSeeds`, table `list_seed`,
     at most 50 together). A GitHub address that is not one repository (a file path, a branch) is refused. Each pass
     reads the added GitHub lists first, oldest first, then the lists the search finds, so the README time and size
     budget goes to them first. The star floor, the name rule and the AI words do not apply to an added list: a
     person chose it. Its facts come from `GET /repos/{owner}/{repo}`; a list the search also found costs no extra
     call. An archived list and one GitHub does not find are skipped
     (`helper2.scout.awesome.skips{reason="added-archived"|"added-unavailable"}`) and stay added; a renamed one is
     read under its new name. From there an added list is a seed like the others: its nested lists, its entries, its
     links and its type follow the same rules. Removing it stops the reading; the list and what was found in it stay
     in the catalog. API: `GET` and `POST /api/v1/admin/list-seeds`, `POST /api/v1/admin/list-seeds/{id}/remove`,
     both changes in the audit trail (`ADD_LIST_SEED`, `REMOVE_LIST_SEED`). The id of an added list is made from its
     address (`ListAddresses.idOf`), so it is the same at every read.
   - **A web page as a list.** The address is https, with a public host name: no user info, no port but 443 (dropped from the kept
     address), no fragment,
     no IP address in any notation, no private or reserved name, no punycode label: the Scout's one host check
     (`WebAddresses`, [0013](ADR-0013-discovery.md) §17), with the plain-page rule of `ListAddresses` on top (no
     fragment, at most 300 characters; 400 otherwise, with the rule that failed). Two addresses of one
     page (with and without "www.", with and without a trailing slash) are one list: the key is the canonical URL.
     - **Read through the live web page reader**, not by our own servers ([0014](ADR-0014-evidence-gated-enrichment.md),
       `PageReader`): `WebPageReader` asks for the page as Markdown, and `AwesomeListParser` reads that Markdown like
       a README. So the entries, the heading paths, the nested GitHub lists, every link of an entry and its rule, and
       the list index work as they do for a README. A page that answers with no text or from another host is not a
       list.
     - **The page is a list resource too**: its canonical URL is its identity, its title is the page's first
       heading (the host and path when it has none), its tag is `awesome-list`. It has no stars, licence or dates.
     - **Its own source, `curated-web-lists`** ("Curated web lists", `CollectorDescriptors.curatedWebLists`): the
       record of the page and its list resource carry it, so the Sources page shows the web lists apart from the
       GitHub lists and names the live web page reader in its terms line. The same collector and the same crawl
       write it: the collector names it in `Collector.alsoWrites`, and `RunScout` merges and indexes the hits of
       each source it writes under that source. The entries a page links to keep `github-awesome`: their facts
       come from GitHub, and one pass reads each entry once, whatever list it is in. Both apps register its
       descriptor (`CollectorsConfiguration`, `ScoutSourceDescriptors`), so its `source` row exists before a
       record. It has no switch of its own: it is on when `github-awesome` is on. Its type is `AWESOME_LIST` when it passes the counts of the README rule
       below (`AwesomeLists.readsAsList`: at least 20 linked items that are at least half of the text lines), and
       ARTICLE otherwise. The name part of the rule does not apply: a person added the page.
     - **One read in `H2_SCOUT_AWESOME_WEB_PAGES_EVERY`** (7 days; at least 1 day). `list_seed.read_tried_at` holds the last try,
       whatever its result, and `list_seed.read_failures` the tries in a row that gave no list. A try that gives
       no list (no text, another host, the reader down, a stop in the middle) is tried again after
       `H2_SCOUT_AWESOME_WEB_PAGES_RETRY_AFTER` (1 hour, so at the next pass, at most `every`); after 3 such tries
       in a row the page waits the full `every`. A read that gives a list sets the count back to 0. Between two
       reads each pass uses what the last read gave, kept in `list_seed_last_read` (one row per added web page,
       replaced at each read that gives a list, deleted with its list): the headings, the entries, the links and
       the two counts of the list rule, never the page text. A restart changes nothing; nothing of a page is
       withdrawn.
     - **Each read is one live web credit** on the Scout's daily cap (`ScoutLiveWebCredits`,
       `helper2.live-web.credits{feature="scout-list-pages"}`), a failed read too. Past the cap, without a page
       reader, or when the pass has spent its time or size budget, the page waits for the next pass
       (`helper2.scout.awesome.skips{reason="page-credit-cap"|"page-reader-off"|"time-budget"|"size-budget"}`);
       its `read_tried_at` does not change and nothing is withdrawn. The pages tried longest ago are read first.
     - **Hosts never read.** `helper2.scout.github-awesome.web-pages.never-read`
       (`H2_SCOUT_AWESOME_WEB_PAGES_NEVER_READ`): entries `host=reason`, default `coursera.org` (its terms forbid
       automated reading without written consent). A subdomain matches. A page on such a host is refused when it is
       added (400 with the host and the reason, nothing written) and again before every read
       (`skips{reason="page-never-read"}`), so a host added to the setting later stops the pages already added.
       Removing a host is a change of the setting, not a release.
   - **An entry is a list item with a web link.** An indented item with its own link is its own entry. The lines
     under an item that have no link of their own (a summary, the authors, a sub-item without a link) belong to it:
     their words are part of its text, at most 20 lines and 500 characters.
   - **Every link of an entry goes to the rule of its kind**, sorted by its host ([0013](ADR-0013-discovery.md) §17):
     a GitHub repository by the rules below; a Hugging Face model, an arXiv paper and an npm package by their
     source's own record, read by id; a page on a course
     platform (point 7) to review as a `COURSE_CANDIDATE`; any other link to review as a `RESOURCE_CANDIDATE`.
     Nothing a source cannot confirm is indexed without a person.
   - Each README (up to 5 MB) is parsed into linked repositories (`AwesomeListParser`): at most 1000 per pass and
     `H2_SCOUT_AWESOME_PER_RUN` (1000) per run, so a pass ends in one daily run; a pass cut short by
     `max-items-per-run` resumes after the last repository name it handled (entries sorted by name), so a list that
     changed between runs cannot make the pass skip or repeat entries. A repository GitHub does not find, an archived
     one and the old name of a renamed one are withdrawn ([0011](ADR-0011-scout-runner.md) §4).
   - Facts come through GraphQL, 50 repositories a call. A repository under 50 stars is not collected: that is the
     collection scope (`min-repo-stars`). The collector states `archived` and `fork` on each hit and drops neither
     itself: the vitality rule of the Scout decides ([0020](ADR-0020-resource-vitality.md)). An archived
     repository is refused and counted in `helper2.source.items.dropped{reason="archived"}`; a fork with stars or a
     recent push is indexed.
   - **The classifier.** The fast model (`SpringAiRepoClassifier`, `prompts/sources/classify-repos.txt`, thinking off)
     answers `ai` true or false and one allowed type, in batches of 25. The schema is in the prompt, not sent as
     `response_format`. Its own daily cap `H2_SCOUT_AWESOME_CLASSIFIER_DAILY_CALLS` (300), gauge
     `helper2.scout.classifier.budget.remaining`; calls are timed and their tokens counted with step `classify`. An id
     the model did not answer (error, spent budget, missing id), and every id in a run without a model, goes to
     keyword rules (`RepoClassifier.Heuristic`). The allowed types include `AWESOME_LIST`: a linked repository whose
     description calls it a curated list of AI links is `ai` true, type `AWESOME_LIST`; the name alone is not enough.
     The answer counts only when the README rule below passes too: the collector reads that repository's README.
     When the rule fails (not named like a list, no README, a README that is not a list), the keyword rules give the
     type instead, so a collection of MCP servers in one repository stays `MCP_SERVER` though its description calls it
     a curated list. When the README cannot be read now (timeout, 5xx), or the pass has spent the README time or size
     budget the crawl of lists draws on too, the repository is left out of this run and read again at the next one; it
     is not withdrawn. A rate limit on that README stops the pass, like a GraphQL rate limit (cursor kept).
     A repository whose facts say nothing gets `ai` false, type OTHER, and is not indexed.
   - A failing list search or README is skipped; a GraphQL rate limit stops the pass (run `FAILED`, cursor kept).
   - Each list read is a resource too (tag `awesome-list`), and its entry lines and headings become a search
     index ([0023](ADR-0023-catalog-full-text-index.md)).
   - **An awesome list is its own type, `AWESOME_LIST`** ("Awesome list" on the site): a curated list of links to other
     resources, not a library, a tool or an MCP server. The README rule is in one place, `AwesomeLists`, and reads the README:
     the repository name starts with "awesome" or "best-of-", or the repository has the topic `awesome-list`; at least
     20 list items carry a web link, and these items are at least half of the README's text lines. An item is a Markdown list item, a table row or an HTML `<li>`; the lines under
     it that are indented more (a summary, the authors, a `[[pdf]](…)` link, a sub-item without its own link) belong to
     it and are not counted on their own. A web link is an `http` or `https` link: an anchor to a section, a link to a
     file of the repository, an image and a badge are not. Headings and code blocks are not counted. The name alone
     never decides: a product called "Awesome Notes" has a normal README and stays what it is. A list with fewer than
     20 items is too small to be worth listing. A product README has a few links, mostly in prose and badges, so half
     of the lines being linked items separates the two. A list written as paragraphs, bold lines or tables under one
     heading per entry has no list items and fails the rule. The classifier of linked repositories (above) is the one
     other path to `AWESOME_LIST`, and it passes the same rule. A list the collector reads and that fails the rule
     stays ARTICLE.
   - `AWESOME_LIST` is the most specific type (`ResourceType.specificity` 0), so when one record says it, the
     resource is an awesome list whatever other sources say ([0004](ADR-0004-persistence-and-the-schema.md)
     §3). That is why every path to it passes the README rule, and an added web page its counts. Other sources that see a GitHub repository have no
     README at hand and keep their own type, so a repository a record wrongly calls ARTICLE still takes their type.
   - **Backfill:** `POST /api/v1/admin/ai-check/awesome-lists` (`CheckAwesomeLists`) reads the README of every
     catalog resource at `github.com/<owner>/<repo>` that is not `AWESOME_LIST` yet and that the rule names: the
     repository name starts with "awesome" or "best-of-", or a record of the resource carries the tag `awesome-list`
     (at most 2000, most popular first). The SQL (`JdbcAwesomeListSuspects`) reads the tags from `source_record.extra`,
     where the GitHub collectors put the repository's topics, all of them and in any case. The tags go with each
     suspect to the reader, which applies the same rule. A list that passes gets the curated type `AWESOME_LIST` with a
     `SET_FIELD` audit entry that holds the type before and after. A type an admin curated is never changed. A list
     known only by its topic is found only when a record of it carries its topics: a source that saw the repository
     without them gives the backfill nothing to go on until such a record comes in.
     Running it again reads only the ones still not typed, so it is safe to repeat; a README that cannot be read now
     is counted and read at the next run. A resource that is gone when its turn comes is not set, and the run goes on.
   - Enrichment proposes a type only for a resource typed OTHER ([0014](ADR-0014-evidence-gated-enrichment.md) §4); its
     excerpt states `AWESOME_LIST` with "awesome list", "curated list of" or "a list of awesome". A mis-typed list
     (LIBRARY, MCP_SERVER…) is fixed by the rule and the backfill, a new one by the collector.
7. **Courses** (`course-scout`, every `H2_SCOUT_COURSES_INTERVAL`, 7 days; [0031](ADR-0031-guide-pipeline.md)).
   One Tavily search per learning platform (`helper2.scout.courses.platforms`, 10 platforms), restricted to its domain
   with `include_domains`, `search_depth basic`, published since the last complete pass (the last month on the first).
   One credit per platform and pass, about 43 credits a month, counted on the Scout's daily cap (point 18, feature
   `scout-courses`). A result is kept only when it is on the platform's host
   and path, about AI, and reads like a course or certification page. Kept pages are **candidates**, not hits: each
   becomes a `COURSE_CANDIDATE` review item, and nothing reaches the catalog until an admin accepts it. Off without
   `H2_LIVE_WEB_API_KEY`. The rule that says a page is a course is in one place, `CoursePages`: the awesome-list
   collector asks it for the links of its lists (point 6).
8. **ai-deadlines** (`ai-deadlines`, 1 day). One GitHub contents call lists the conference YAML files of
   `huggingface/ai-deadlines` (MIT). Each file is read only from `raw.githubusercontent.com`, without the GitHub token
   and without following redirects (`HttpClients.getWithoutRedirect`): a redirect fails the file.
   One EVENT per conference: its next edition (point 22). A file that cannot be read or parsed, or whose download URL
   is on another host, is logged, skipped and counted in `helper2.source.items.dropped{source=ai-deadlines, reason}`
   (the exception type, or `ForeignDownloadUrl`). A conference with no upcoming edition is not counted. When the
   circuit breaker refuses a file, the pass ends there: the run fails and the next run tries again.
8a. **confs.tech** (`confs-tech`, 1 day). The crowd-sourced conference list of confs.tech,
   `tech-conferences/conference-data` (MIT). One GitHub contents call per year (this year and the next) lists its
   topic files; each JSON file is read only from `raw.githubusercontent.com`, by a client that does not follow
   redirects (`ObservedHttp.restClientBuilderWithoutRedirects`, `HttpClients.getWithoutRedirect`), so a redirect fails
   the file instead of leading to another host. The pass stops
   asking for files and years once it holds `maxItems` conferences. A conference is kept when its name passes
   the AI check (`Classification.isAiRelated`) and it has not ended; a conference listed under several topics is kept
   once, by canonical URL. Its days, city and country are the file's own (DAY precision); `online` with a city is
   HYBRID, without one ONLINE. A year with no folder yet gives nothing; an unreadable file is logged, skipped and
   counted in `helper2.source.items.dropped{source=confs-tech, reason}`.
9. **Tavily discovery** (`discovery-<category>`): one collector per category of the AI world, 34 categories in 7
   families, list pages indexed, products reviewed ([0013](ADR-0013-discovery.md)).

### LinkedIn
10. **The collector `linkedin-scout`** (`LinkedInCollector`, "LinkedIn" in the product). We never call LinkedIn. One
    Tavily search per fixed query, every 7 days (`H2_SCOUT_LINKEDIN_INTERVAL`), with
    `include_domains: ["linkedin.com"]`, `topic: general`, `search_depth: basic` (1 credit), `max_results: 10`,
    `include_usage`; no raw content, no images, no answer; only new pages: `start_date` = the previous complete pass,
    or `time_range: month` on the first. It is built as the discovery collectors are ([0013](ADR-0013-discovery.md)): a
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
    ([0013](ADR-0013-discovery.md) §8), with the tag `linkedin`. The batch has no hits: nothing from LinkedIn reaches the
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
    ([0013](ADR-0013-discovery.md) §8), which only the second may declare: the check takes every finder.
15. **A LinkedIn event has no days.** Its page is not the event's own site, so its days are not stated days (point
    21). An accepted LinkedIn event is found by search and never listed in Next events. To list it, an admin adds the
    event to the curated list with the days from its own site.
16. **`linkedin.com` stays excluded from discovery** ([0013](ADR-0013-discovery.md) §4), so this collector is the only way
    LinkedIn pages come in, and no LinkedIn candidate is merged with a discovery one.
17. **Failed searches.** As in discovery: a search that fails alone is logged and skipped; the others, already paid
    for, are kept, and the next run makes only the searches the pass still misses (the resume cursor lists those that
    answered, [0013](ADR-0013-discovery.md) §11); the checkpoint moves once all have answered. When half or more fail, the
    pass fails and its checkpoint stays, so the next daily run repeats it. The cap is checked before each search, and every
    attempt counts its credits, a retried one too ([0013](ADR-0013-discovery.md) §10). The live web events scout follows
    the same rules.
18. **Credits are counted and capped with discovery's.** Every search adds its credits (Tavily's `usage.credits`, or
    1) to the day's global credit counter and to the Scout's own counter (`ScoutLiveWebCredits`,
    [0013](ADR-0013-discovery.md) §10), with the metric `helper2.live-web.credits{feature="scout-linkedin"}`. Past the
    Scout's daily cap (`H2_SCOUT_DAILY_CREDITS`, shared with the discovery categories) the pass is left open
    ("Daily live web credit cap of the Scout reached") and tried again the next day. The same holds for the news
    (`scout-news`), the courses (`scout-courses`) and the events: no live web collector of the Scout spends a credit
    outside the cap. News and courses stopped by the cap between two searches keep what they found and leave the pass
    open: the resume cursor lists the searches done, the next run makes only the others over the same days, and the
    checkpoint moves when all are done. Each HTTP attempt of a retried search is counted; a search the circuit
    breaker refuses is not. On a day when the news (7 credits)
    and the courses (10) both run, discovery has 23 of the 40 left and finishes its categories the day after.
19. **Retention and titles.** `linkedin-scout` is a web source (`WebSources`, [0013](ADR-0013-discovery.md) §12): its titles
    are cleaned as page titles, and its NEWS and ARTICLE records go with the web retention.
20. **Alert.** `Helper2ScoutCollectorStale` fires when `linkedin-scout` has had no success for its run interval plus a
    day (8 days at the default 7), as for every collector ([0009](ADR-0009-observability.md)).

### Events
21. **Stated days only.** An event has a start (and maybe an end) only when a source states it.
    `DateFact.scheduledDay` stores the day at 00:00 UTC with DAY precision and the source id. Unlike a publication
    date it may be in the future; a day before 1995 is unknown. Source records keep `source_event_starts_at` and
    `source_event_ends_at`; on every upsert the writer copies the event days of the resource page's card into the
    resource (`ProjectedReconciliation`, [0004](ADR-0004-persistence-and-the-schema.md) §3):
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
23. **Curated industry events.** `scripts/industry-events-2026-11-12.json` holds each event's official site, the
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
      ([0010](ADR-0010-data-retention.md)) has deleted.
      `industry-events-seed.test.mjs` fails when the seed is not what the script writes from the file, when an event
      that is over is written, or when the file lists one.
    - **Descriptions.** One or two plain sentences per event: what it is, who runs it, who it is for, where. Every
      fact is on the organiser's page for this edition; a fact about a past edition is not used. The research pass
      drafts them, with a model. A person reads and approves each one in the pull request before the merge, so no
      description goes in unread. They are shown as curated text, with no AI label
      ([0033](ADR-0033-marking-ai-written-text-research-trail.md)). When the page does not carry an honest
      sentence about the edition, the event has no description: it is listed, but its page is not index-worthy
      ([0041](ADR-0041-site-pages-for-crawlers-policy-endpoint.md) §5) and is not in the sitemap. `CuratedSeedTest` checks
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
      AI and its link passes the Scout's host check (`PageScope.plainUrlProblem`, which is `WebAddresses`). At most 20 pages a pass
      (`H2_SCOUT_LIVE_WEB_EVENTS_MAX_PAGES`).
    - **On by default, one switch.** `H2_SOURCE_LIVE_WEB_EVENTS_SCOUT_ENABLED` is true; `false` switches it off.
      It runs only with `H2_LIVE_WEB_API_KEY` and an AI model. Tavily's written OK for scheduled searches whose
      pages we list is still to confirm (as for discovery and LinkedIn).
    - **The page is read with Tavily Extract** (basic, markdown, 4 pages a call). Search and Extract credits count on
      the Scout's daily cap (`H2_SCOUT_DAILY_CREDITS`, [0013](ADR-0013-discovery.md) §10). The cap is checked before the
      pass and before each Extract call; past it the pass keeps what it read, and its queries are not marked done:
      the pages left unread are found and read on the next run (their searches are paid again).
    - **Undated catalog events are read too.** Each pass also reads the page of every catalog EVENT with no start
      day first discovered since the last pass (the first pass: the last 30 days), oldest first
      (`Resources.undatedEvents`), with its own budget of `H2_SCOUT_LIVE_WEB_EVENTS_MAX_PAGES` pages next to the
      search pages. A full batch keeps the pass open: the cursor holds the last event read
      (`catalog:undated-events@<discovered at>/<id>`), and the next call reads on from there. The pass is complete
      only when the searches are done and a batch comes back short. Such a page goes through the same reader and gate;
      `extra.query` is `catalog:undated-events`.
    - **A LinkedIn post leads to the event's own site.** When the reader keeps no event from a LinkedIn post
      (`/posts/`, `/feed/update/`), the post's raw text gives its links (`LinkedInPosts.eventSite`): when exactly one
      https link is not LinkedIn, `lnkd.in` or a social site, that page is read through the same reader and gate, and
      the event is kept at that site, titled by the post's title up to its first ` | `, with `extra.linkedFrom` = the
      post. Short `lnkd.in` links are not followed. The post counts as read only once its site was read, so a call
      that stops before the second round leaves the post for the next one.
    - **Progress counts only pages read.** A page found by a search and in the catalog is read once, as a catalog page.
      A search is done when every chosen page it found was read; the catalog cursor moves only past pages read. A
      failed Extract call, the credit cap or a missing model ends the call and leaves the rest for the next one. A page
      that Extract answers as failed is not tried again.
    - **A model reads the page.** The reasoning model, thinking off ([0008](ADR-0008-llm-integration.md)),
      prompt `prompts/sources/read-event.txt`: how many events the page is about, the first and last day, and the
      words of the page that state them; kind, format and place, each with its own words. The title and the page are
      untrusted data: the title is flattened, cut to 200 characters and escaped (`UntrustedText`), the page is cut to
      12,000 characters and cannot close its block, and the prompt says to ignore instructions in both. The prompt
      states the rules `EventQuoteGate` applies (point 27), so the model gives the days the page states. The
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
30. **They are labelled as AI.** EU AI Act Art. 50 and [0033](ADR-0033-marking-ai-written-text-research-trail.md): a
    checked quote does not make the days the source's own words; they are the model's reading. A result card says
    `eventOrigin: AI_EXTRACTED` when its start comes from this source, and the UI (Coming up, the Events page, the
    resource page) shows the days, kind and place with the AI label and `data-ai-generated`. Server pages leave days
    and place a model read out: their facts and schema.org data cannot carry the label.
31. **The API.** `GET /api/v1/events/upcoming?days=&size=&offset=` (`size` 1–500, default 6; `offset` 0–100,000,
    default 0): resources of type EVENT with a start whose end (or start) is on or after today 00:00 UTC, soonest
    first, then by title and id; `offset` skips that many of them. With `days` (1–366), only
    events that start before today 00:00 UTC plus `days`; the response says that end in `until` (null without
    `days`). An event already running stays listed until it ends. `total` counts every event in the window, so "Show
    all N" is a real number ([0040](ADR-0040-design-system.md)). `Cache-Control: public, max-age=300`. The home
    page (`useEventsFeed` in `features/feeds/feeds.ts`, shown by `HomeLists`) asks for 60 events of the next 90 days (`days=90&size=60`).
    The Events page asks for the next 366 days in pages of 500 (`offset` 0, 500, …) until it holds `total` events, so
    its list and its kind and format counts cover the whole window.

**An admin's edit wins.** A type an admin sets need not be declared by a finder, and the events seed overwrites only
the fields it asserts ([0038](ADR-0038-back-office-and-review.md)).

### Code
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/` — the collectors: `mcp/McpRegistryCollector.java`,
  `huggingface/scout/HuggingFaceTrendingCollector.java`, `HuggingFaceProviderOrgsCollector.java`,
  `tavily/scout/LiveWebNewsCollector.java`, `CourseCollector.java`, `LinkedInCollector.java`, `LiveWebEventsCollector.java`,
  `ScoutLiveWebCredits.java`, `ScoutLiveWebSearch.java`, `QueryProgress.java`,
  `github/scout/GitHubAwesomeCollector.java`, `AwesomeListParser.java`, `AwesomeLists.java`, `ListReader.java`,
  `GitHubReadmeReader.java`, `WebPageReader.java`, `WebPageLists.java`,
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
  `catalog/web/HomeListsController.java` — `listUpcomingEvents`.
- `backend/libs/core/src/main/kotlin/ai/helper2/search/ResultMerger.kt` — `eventSources`, `statedFirst`;
  `backend/libs/core/src/main/java/ai/helper2/search/web/CardMapper.java` — `eventOrigin`;
  `backend/apps/public/src/main/java/ai/helper2/site/pages/usecase/RenderPage.java` — `view`: AI-read days off server pages.
- `scripts/industry-events-seed.mjs`, `scripts/industry-events-seed.test.mjs`,
  `scripts/industry-events-2026-11-12.json`,
  `backend/libs/schema/src/main/resources/db/migration/R__industry_events.sql`.
- `frontend/src/features/home/HomeLists.tsx`, `TermList.tsx`, `frontend/src/features/feeds/feeds.ts`, `feedCells.tsx`, `EventsPage.tsx`,
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
- Retention ([0010](ADR-0010-data-retention.md)): LinkedIn articles and posts are deleted 180 days after
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
- LinkedIn: an accepted company page links to LinkedIn, not to the product's own site, until enrichment or an admin adds it. An accepted LinkedIn event has no days and is not in Next events until it is added to the curated list by hand. The collector's 20 searches fall on one day, so on that day the Scout's daily cap of 40 leaves discovery about 20 credits ([0013](ADR-0013-discovery.md)).
- Live web events scout: Tavily has not confirmed in writing that its permission covers scheduled searches whose pages we list. The gate proves the days are written on the page, not that the page is right. It reads only English month names and ISO dates. A page about several events gives none. schema.org Event data is not read before the model is called.
- An event whose source gives no day is not listed; event days can be a few hours off far from UTC; conference dates are as correct as ai-deadlines; curated events need a research pass to stay current.
- A curated event leaves the catalog ten years after its last day, like every event ([0010](ADR-0010-data-retention.md)); until then search still finds it. A research file that still lists an event ended more than ten years ago puts it back when the seed changes, until the next daily purge: the seed script does not skip it.

### STRIDE
| Threat | Risk | Control |
|---|---|---|
| Spoofing | Anyone can post on LinkedIn or publish an event page | LinkedIn pages and courses wait for a person; on accept the URL is checked again, the type must be one a finder declares, tags must be tag-shaped. An event's days need a quote on its own page. |
| Tampering | A file of ai-deadlines served from another host; a hostile event page | Files only from `raw.githubusercontent.com`; event pages cut, fenced as untrusted, and checked by `EventQuoteGate`, not by the model. |
| Repudiation | Not knowing where a date or a candidate came from | Every event keeps its source and its quote (`eventEvidence`); a candidate keeps every finder. |
| Information disclosure | LinkedIn pages name people | Fixed queries with no user data; no author field, names out of titles, 300-character excerpts, 180-day retention for posts and articles, removal on request. |
| Denial of service / cost | Credits, a slow source | Fixed intervals, `max_results` 10, a circuit breaker and retry per collector (`collector-<id>`), the Scout's daily credit cap, the event reader's daily cap. |
| Elevation of privilege | — | Only an admin accepts (back office, VPN, [0036](ADR-0036-identity-and-the-admin-host.md)); the admin page renders a candidate as text and links only `https://` URLs. |

OWASP LLM Top 10: the classifier and the event reader read untrusted text, fenced and with instructions to ignore it;
their answers are checked by code (allowed types, the quote gate). An accepted LinkedIn post reaches a model in
enrichment and in the guide as untrusted text, as every web text does ([0013](ADR-0013-discovery.md)).

### AI architecture check
| Layer | Status |
|---|---|
| Model | Covered: the fast model classifies, the reasoning model reads event pages, NVIDIA only ([0008](ADR-0008-llm-integration.md)); without a model the classifier falls back to keyword rules and the events scout is off. |
| Agent framework, Memory, Browser & Computer Use | Deferred, safe: no agent; Tavily reads the pages, we run no browser. |
| Orchestration | Covered: each collector is a step of the Scout run, with its own cursor, interval and failure isolation ([0011](ADR-0011-scout-runner.md)). |
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

### Legal points
Tracked privately (S9.6.3).

## Diagrams
Collectors: [flow](../diagrams/flow-0012-scout-collectors.svg) · [sequence](../diagrams/sequence-0012-scout-collectors.svg) ·
next events: [flow](../diagrams/flow-0012-next-events.svg) · [sequence](../diagrams/sequence-0012-next-events.svg) ·
LinkedIn: [flow](../diagrams/flow-0012-linkedin-via-tavily.svg) · [sequence](../diagrams/sequence-0012-linkedin-via-tavily.svg) ·
live web events: [flow](../diagrams/flow-0012-live-web-events-scout.svg) · [sequence](../diagrams/sequence-0012-live-web-events-scout.svg)

## Backlog
- Decides: [F2.2](https://github.com/silvioincalza/helper2.ai/issues/32).
- Open issues that change it:
  - [S2.2.6](https://github.com/silvioincalza/helper2.ai/issues/191): the `linkedin-scout`, `course-scout` and live web events collectors fixed.
  - [S2.13.1](https://github.com/silvioincalza/helper2.ai/issues/218): source requests and more registries.
  - [S2.14.1](https://github.com/silvioincalza/helper2.ai/issues/219): live collectors for hackathons, meetups, talks and workshops.
  - [S2.3.9](https://github.com/silvioincalza/helper2.ai/issues/843): a list page that discovery finds is split into its items; an added web page is read (point 6).
  - [S9.6.1](https://github.com/silvioincalza/helper2.ai/issues/340): each source's terms read again for commercial use.
