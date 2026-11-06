# 0051 — Tavily discovery: one collector per category of the AI world, lists indexed, products reviewed

**Status.** Accepted. **Builds on:** [0007](../foundation/0007-configuration-and-secrets.md), [0018](0018-scout-runner.md), [0019](0019-evidence-gated-enrichment.md), [0020](0020-scout-collectors.md), [0033](../foundation/0033-resilience-policies.md), [0037](../search/0037-tavily-store-and-daily-quota.md), [0042](../frontend/0042-redesign-design-system-v4.md) **Amended by:** [0062](0062-writing-about-a-resource.md) (posts about products are ARTICLE candidates).

## Context
The Scout's sources are good at models (Hugging Face), MCP servers (the registry) and repositories (GitHub). They
miss most of what a person looks for: prompt libraries, AI apps, SaaS products, model hosting, no-code builders,
self-hosted apps, browser extensions, tools for teachers, doctors and lawyers. These have no registry. They are found
on the open web.

The open web is also where SEO pages live. A search for "best self-hosted AI" returns lists written to rank, not the
tools. If a web result could become a catalog entry by itself, anyone who writes a page could put a product in our
catalog. The courses collector ([0032](../guide/0032-guide-intents.md) §8) already solves this for courses: results
are candidates in the admin review list.

## Decision
1. **34 categories in 7 families, each its own collector.** The categories are one YAML list, `helper2.categories`
   in `application.yaml`. Each entry has an `id` (the catalog tag), a `family`, a `short-name` (the radar label, at
   most 12 characters), a `name` (the Sources page's), its `words` and a `discovery` block:
   - `type`: what a product its searches find becomes (`PRODUCT`, `AGENT`, `PROMPT`, `SKILL`, `LIBRARY`, ...);
   - `queries`: one Tavily search each (most categories have one, five have two);
   - optional: `interval` (default `helper2.scout.discovery.interval`, 7 days), `enabled`, `min-score` (default
     0.4), `require-keyword`, `index-lists`, `exclude-domains` (added to the shared list).

   The families and their categories:
   - **Prompts and skills**: prompt libraries, skills and GPTs.
   - **Everyday apps**: AI assistants, writing, translation, meetings and transcription, documents and PDF, research
     and papers, AI search, browser extensions, education and tutors, health.
   - **Creative and media**: image generation, video generation, voice and audio, design and presentations.
   - **Business**: customer support, voice agents, data and analytics, no-code, marketing and SEO, legal.
   - **Developers**: coding, agent platforms, LLM frameworks, RAG and vector databases, LLMOps and evaluation, AI
     security, fine-tuning.
   - **Hosting and infrastructure**: model hosting, AI cloud and PaaS, AI gateways.
   - **Run it yourself**: self-hosted, local AI.

   Each category is a `TavilyDiscoveryCollector` with the source id `discovery-<id>`: its own cursor, its own run
   record, its own row on the Sources page and its own dot on the Home radar. The descriptor carries the `family`
   (`SourceDescriptor.family`, `SourceInfo.family` in the API), so the Sources page groups the categories by it.
   Models, datasets, MCP servers and awesome lists come from their own collectors. There is no SaaS category: SaaS is
   how a product is sold, not what a person needs.

   Startup fails on an unknown family or type, an id that is not lower-case words with hyphens or is longer than 30
   characters (`discovery-` plus the id fits the source id's 40), a short name over 12 characters, a type a product
   page cannot have (a reading or course type), a category without a query, or two categories with one id
   (`CategoryProperties`, `DiscoveryCategory`).
2. **Registered by a `BeanRegistrar`.** How many collectors there are depends on the configuration, so
   `DiscoveryCollectorRegistrar` (Spring Framework 7 `BeanRegistrar`, imported by `SourcesConfiguration`) registers
   one bean per category. **This is the one exception to [0007](../foundation/0007-configuration-and-secrets.md):**
   the registrar runs before any bean exists, so it reads `helper2.categories` from the `Environment` through Spring
   Boot's `Binder`, not as a `@ConfigurationProperties` bean. The entries are checked while they bind, in code. The
   collectors themselves are built from the usual `@ConfigurationProperties` beans: the shared settings
   `helper2.sources.discovery` (base URL, token, timeouts; `H2_SOURCE_DISCOVERY_ENABLED`,
   `H2_SOURCE_DISCOVERY_DEADLINE`) and `helper2.scout.discovery` (interval, depth, results, score floor, daily
   credits, excluded domains). All of them share one resilience policy, `collector-discovery`: a Tavily outage opens
   one circuit, not 34.
3. **Staggered over the week.** A category that never ran is first due on day `(its place in the list) mod 7`
   (days since 1970, UTC; `RunInterval.staggered`), then every interval after its last complete pass. So 4 or 5
   categories search a day, not 34 on the first one. A hash of the id spread them 2 to 9 a day, so the place in the
   list is used. Adding a category changes only the first day of categories that never ran. A run of a category that
   is not due is SKIPPED with the next day it is due ("Not due yet: next pass on 2026-11-07"); the admin's Run now
   follows the same rule. The stagger only delays the first try. When a pass fails or meets the daily cap, the Scout
   keeps it open (`ScoutCursor.passStartedAt`, no resume cursor), and an open pass runs on the next run whatever the
   day. So a first try that fails is tried again the next day, not a week later.
4. **How it asks.** Following Tavily's search guide:
   - one short query per search, never one long query for everything;
   - the query describes the product page in that page's own words ("serverless inference API for open-weight LLMs
     and image models"), without "best" or "launched": they bring back pages written to rank, and news;
   - `topic: general`, `search_depth: advanced` (2 credits, best relevance; `H2_SCOUT_DISCOVERY_DEPTH=basic` halves
     the cost), `max_results: 10`;
   - `include_published_date`, `include_favicon` and `include_usage` on; `include_answer`, `include_raw_content` and
     `include_images` off. We keep what the search says about a page, never the page's full text, its images or an
     answer Tavily writes;
   - `exclude_domains` for social and video sites (no product pages there) and for AI directories and review sites
     (Product Hunt, There's An AI For That, Futurepedia, G2, Capterra, AlternativeTo, ...): their terms forbid
     copying their listings. We link to them instead: they are entries of the curated list (`R__curated_seed.sql`)
     with our own one-line description, and nothing taken from their pages. A category adds its own: course platforms
     for education, health portals and agencies for health, law sites and legal directories for legal;
   - only new pages: `start_date` = the previous complete pass, or `time_range: month` on the first.
5. **How it sorts each result** (code, no model; `DiscoveryPages`), in this order:
   - dropped: Tavily score below the category's `min-score`, or not about AI (`Classification.isAiRelated`);
   - **a GitHub repository or a home page is a product**, whatever its title says ("an open-source alternative to
     ChatGPT" is a product's own words);
   - **list page**: any other page with a list-like title ("12 best ...", "best AI coding tools", "X vs Y",
     "ChatGPT alternatives"). Indexed as an `ARTICLE` hit, with Tavily's excerpt (400 characters) as its
     description. It stays an `ARTICLE` even when its title names an event ("10 best AI tools for event planning"):
     only the news mapping infers `EVENT`, and an `EVENT` with no event date would escape both retention rules. A
     list helps a person compare; it claims nothing about the products in it. With `index-lists:
     false` list pages are dropped;
   - **product page** one level below a home page (a language segment such as `/en` does not count). A site page
     (`/pricing`, `/features`, `/download`, ...) stands for its site's home page, titled with the side of the page
     title that names the site ("Pricing | AnythingLLM", "AnythingLLM - Pricing" → "AnythingLLM"). Any other page is
     the product itself: big vendors have one product per page (`aws.amazon.com/bedrock`). Not when the path is
     writing (`/blog`, `/docs`, a post's slug such as `/how-we-built-our-ai-stack`), not on a writing host (`blog.`,
     `docs.`, `help.` ...), not on a publisher's or directory's host;
   - with `require-keyword`, a result is kept only when its own title or excerpt has one of the category's words;
   - anything else is dropped.

   Health and legal use `min-score: 0.5`, `require-keyword: true` and `index-lists: false`: their searches pull
   general advice pages and law firms, and a list of "10 AI tools for doctors" is not worth indexing without review.
   The health searches cover tools for professionals (medical scribes, clinical notes) and for patients (health
   questions, symptoms).
6. **Every category a result belongs to.** A result gets the tag of its category, and of every category whose words
   are in its title or excerpt (at the start of a word, with hyphens read as spaces: `text-to-speech` fits "text to
   speech", `fine tuning platform` fits "fine-tuning platform", and `lora` does not fit "flora"). The words are
   compiled to patterns once, at startup. Words that tagged too much were made narrower: `ai art` became
   `ai art generat` and `ai artwork`, `no code` became `no code ai`, `no code builder` and `no code app`, `fine tun`
   became three phrases, `tts` became `tts api`, `ocr` became `ocr api` and `ocr model`. A candidate's type is its
   category's type. A candidate keeps at most 12 tags (`Candidate.MAX_TAGS`), the most a candidate can have and
   still be accepted: its own category first, then the categories whose words it has, in the configured order. A
   list page keeps every category it fits.
7. **What is kept.** Each kept result carries, in its record's `source_record.extra` (and in the candidate until it is
   accepted), never shown:
   - `discovery`: `category`, `query`, `rank`, `score`, `searchDepth`, `shape` (`LIST` or `PRODUCT`),
     `publishedDate`, `requestId`, `searchedAt`, `matchedWords` (per category, the words found) and `favicon`: the
     icon's URL as text, never loaded or shown;
   - `categories`: every category it belongs to.

   A list page keeps Tavily's 400-character excerpt, the one shown in search. A product candidate keeps the whole
   excerpt, up to 2,400 characters, as evidence for its enrichment. `SourceHit.facts` carries these values to the
   catalog, outside the record's fingerprint, so a new `searchedAt` does not count as a change.
8. **Candidates wait for a person.** A product is a `RESOURCE_CANDIDATE` review item (migration `V8`). Its details
   are the candidate as JSON, version 2 (`ResourceCandidateJson`): source (the first collector that found it), URL,
   title, type, tags, excerpt, and `foundBy`: one entry per collector that found it, with what its search said.
   Version 1 (without `foundBy`) is still read. The page is proposed once per URL, and never when the catalog already
   has it (the same rules as course candidates). **When a second category finds a page that waits OPEN**, it is
   merged into that item: the tags are joined (at most 12 again: the categories that found it first, in the order
   they found it, then the others), the most specific type is kept (a self-hosted agent platform is an
   `AGENT`), and the collector is added to `foundBy` and to the item's reason. A decided page stays as it was.
9. **Accepting indexes it.** `ResolveReview` passes the item's details to `IndexAcceptedCandidate` (scout use case).
   The use case checks the candidate first (known source, URL, title, a type that one of the collectors that found it
   declares, at most 12 tags), then:
   - a page another source indexed after it was proposed is left as that source wrote it: only the decision is
     recorded (port `CatalogLookup`);
   - otherwise it is indexed as **one record** of the first collector that found it, with every category as a tag
     and every finder in its extra (`foundBy`), in the same transaction as the decision. One record, not one per
     finder: the same page and excerpt once, one enrichment, and the categories are already in the tags. It has no
     enrichment yet; the next Scout run takes it from the backlog ([0018](0018-scout-runner.md) §7) and the evidence
     gate checks what the model says about it.

   A candidate that fails the checks is refused (400) and the item stays open. Rejecting records the decision only.
   A category declares its product type and ARTICLE; a merged candidate may have the type of any category that found
   it. LinkedIn ([0061](0061-linkedin-via-tavily.md)) declares ARTICLE, NEWS, PRODUCT and EVENT.
10. **Credits are counted and capped.** Every discovery search adds its credits to the day's global credit counter,
    the one the live search counts on and the Sources page shows ([0037](../search/0037-tavily-store-and-daily-quota.md)),
    and to the Scout's own counter (`ScoutTavilyCredits`; Redis, same key, field `scout-credits`). A search costs
    Tavily's `usage.credits`, or 2 (advanced) or 1 (basic) when the answer does not say. The metric is
    `helper2.tavily.credits{feature="scout-discovery"}`. Past `helper2.scout.daily-credits`
    (`H2_SCOUT_DAILY_CREDITS`, 40) the categories due that day are SKIPPED ("Daily Tavily credit cap of the
    Scout reached") and try again the next day: the skip is `CollectedBatch.deferred`, so their pass stays open. When
    the counter cannot be read, the cap counts as reached. LinkedIn ([0061](0061-linkedin-via-tavily.md)) counts on
    the same counters and cap, with the feature `scout-linkedin`.
11. **Failed searches.** A search that fails alone is logged and skipped; the others, already paid for, are kept. When
    half or more of a category's searches fail (an outage, a circuit that opened mid-pass), its pass fails, its
    checkpoint stays and its pass stays open, so the next daily run searches the same pages again.
12. **Web sources are one predicate.** `WebSources.isWeb(id)` (`tavily`, `tavily-scout`, `course-scout`,
    `linkedin-scout` and every `discovery-*`) decides which titles are cleaned as page titles
    (`WebPageTitle.forSource`, `ResourceStore`) and which NEWS and ARTICLE records the web retention deletes
    (`CatalogRetention`).
13. **Where people see it.**
    - **Sources page**: the categories come after the other sources in the one v4 table, one row group per family
      ("Discovery · Developers"), with the honest rules in the note under it: "List articles are added without review",
      "Products are added only after a person checks them". Health and
      legal rows carry their notice: "Not medical advice. We do not check whether a tool is approved as a medical
      device. Claims are the vendor's own, quoted with their source." and "Not legal advice. We do not check whether a
      tool is right for your country or case."
    - **Home radar** ([0042](../frontend/0042-redesign-design-system-v4.md)): every category is a dot. With about 50
      sources, fixed slots would leave some out, so a dot is placed when it comes on, clear of the 5 to 10 dots on the
      radar, starting from its own point (it tends to come back to the same spot). New dots are picked in turn from
      the live sources, the collectors and the categories, so 34 categories do not crowd out the rest; within each
      group the sources queue, so every category comes on before any comes back.
    - **Back office**: Run now for every collector, also one that never ran; the review queue filters by category
      (`?category=`, `ReviewItem.categories`), names every collector that found a page and shows the health and legal
      notices on their candidates. A candidate is a link (https only, `rel="noopener noreferrer nofollow"`), its
      type, its categories and the excerpt.
    - **Terms**: "How we describe resources" and "Health and legal tools". **Privacy**: the weekly category searches
      and what is kept from them.
14. **Alerts.** `Helper2ScoutCollectorStale` leaves out `discovery-*`. One rule, `Helper2DiscoveryStale`, fires once
    per environment when any category has had no success for more than 8 days, and lists them.
15. **Migration `V9`.** The records of the old single `tavily-discovery` collector move to `discovery-<first tag that
    is a category>` (or `discovery-ai-assistants` when none is), with their `categories`; open candidates name their
    new collector the same way; the old source goes with its cursor and run history. A resource that still names the
    old source after retention deleted its discovery record (another record, a review item or a report kept it)
    moves to `discovery-ai-assistants`, so the delete of the old source does not hit `ON DELETE RESTRICT`. No
    compatibility with the old id is kept. V9 was fixed in place before prod ran it; staging needs its new checksum
    set by hand ([RESET-DATABASE.md](../../../deploy/k8s/RESET-DATABASE.md#a-migration-edited-after-staging-ran-it)).

## Consequences
- The catalog grows in the areas no registry covers. No product enters it from the open web without a person; list
  pages are indexed as ARTICLE without review.
- Search can filter by the categories: every result carries their tags.
- A person reviews the candidates of 4 or 5 categories a day instead of 30 at once. The total is a guess (50-200 a
  week), to measure in the first weeks. If it is too many, raise a category's `min-score`, turn on its
  `require-keyword`, or turn it off (`enabled: false`).
- Credits: 39 searches × 2 credits = 78 a week, ~335 a month (390 in a month with 5 passes). With `tavily-scout`
  (~42), `course-scout` (~43) and `linkedin-scout` (~86, [0061](0061-linkedin-via-tavily.md)) the Scout uses ~506
  credits a month, out of the plan's 14,000 shared with live search. The daily cap (40) is about four times a normal
  day (5 categories × ~1.2 searches × 2 credits); on LinkedIn's day its 20 basic searches bring the day to about 32.
- The global daily credit cap of the live search (`H2_TAVILY_GLOBAL_DAILY_CREDITS`) now counts the Scout's discovery
  credits too: a day of discovery leaves less for visitors. The Scout itself checks only its own cap.
- 34 run records a week instead of one, and 34 rows on the Sources page: grouped by family so they stay readable.
- The registrar reads its configuration outside `@ConfigurationProperties` (the 0007 exception above). Its entries
  are still checked at startup and in `DiscoveryCollectorRegistrarTest`.
- Retention ([0034](../operations/0034-data-retention.md)): list pages (ARTICLE) are deleted 180 days after
  `retrieved_at` (`H2_RETENTION_WEB_NEWS`); an accepted product stays as long as it is listed; a review item stays
  until it is decided, then 180 days. The search facts go with their record or item. The Privacy page says so.
- List pages are indexed without review. Their claims are not facts in the catalog: they are `ARTICLE`s, and
  enrichment cites only what they say about themselves.
- An accepted candidate is in search at once, without a type check by a model; its description is the search
  excerpt (up to 2,400 characters) until enrichment runs.
- A category whose search keeps failing misses its pages for that week; its run record says why.

## Security (STRIDE)
- **Spoofing / tampering**: a page can pretend to be a product. Mitigation: it waits for a person; the URL is checked
  again on accept (`CanonicalUrl`), the type must be a product type, the tags must be tag-shaped, the source must be
  a known collector. A merge only joins tags and finders; it never changes the URL or the title.
- **Information disclosure**: no user data in the queries; they are fixed config. The favicon URL is kept as text and
  never loaded, so no visitor's browser calls the site.
- **Denial of service / cost**: one or two calls per category per week, a shared circuit breaker and retry
  (`collector-discovery`), `max_results` 10, a failed search is not repeated in the same pass, and a daily credit cap.
- **Elevation**: only an admin can accept (back office, VPN, [0035](../edge/0035-admin-host.md)). The admin page
  renders the candidate as text and links only `https://` URLs, so a hostile title or URL cannot run script. The
  category filter is checked as a tag before it reaches SQL, and is a bound parameter.
- **SEO spam**: list pages are indexed without review. Links to other sites carry `rel="nofollow ugc"` on our pages,
  so a page that gets in gains no ranking from us. Control characters are removed from every web text.
- **Prompt injection**: web text reaches a model in enrichment ([0019](0019-evidence-gated-enrichment.md)) and, as a
  search result, in the guide's choice step ([0015](../guide/0015-ai-guide-evidence-gate.md)): accepted products
  before enrichment, list pages when a person asks for reading. Both treat it as untrusted text and check every
  quote. The longer product excerpt (2,400 characters) is more untrusted text for the same gate.
- **Harm (health, legal)**: a health or legal tool can be unsafe or wrong for a person's case. We do not judge it; the
  notices say so, and the Terms say we do not list tools whose main purpose is illegal or deceptive.

## Alternatives considered
- **One collector for every category** (the first version of this decision) — one source on the radar and the Sources
  page, one run a week for all 33 searches, one circuit. But a person could not see which category failed or
  brought what, all searches ran on one day, and one slow category held up the rest.
- **A `@Bean` returning a list of collectors** instead of a `BeanRegistrar` — no 0007 exception, but Spring injects
  `List<Collector>` from beans of type `Collector`, not from a bean that is a list, so every consumer would need to
  know about it.
- **One record per finder on accept** — every category as its own provenance, but the same page and excerpt N times
  and N enrichments of the same text; the tags already say which categories it is in.
- **Index product pages directly, let enrichment decide** — fast, but an SEO page becomes a catalog entry with no
  person in between.
- **A model sorts the results and picks the categories** — better recall on odd page shapes, but a model call per
  result and a model that reads untrusted pages before anything is checked. URL rules sort lists from product sites;
  category words tag well enough, and the admin sees the tags before accepting.
- **Search known directories with `include_domains`** (Product Hunt, There's An AI For That) — their terms forbid
  copying their listings, and the result would be their catalog, not ours.
- **Tavily `/crawl`, `/extract` or `include_raw_content`** — more text for the reviewer and the enrichment, more
  credits, and a copy of the page we would have to keep and justify; the excerpt is enough to decide.

## Code
- `backend/src/main/java/ai/helper2/sources/tavily/TavilyDiscoveryCollector.java` — one category's searches and
  what is kept; `DiscoveryPages.java` — list or product, by URL and title; `DiscoveryCategory.java`,
  `DiscoveryFamily.java`; `ScoutTavilyCredits.java` — credits and the daily cap.
- `backend/src/main/java/ai/helper2/sources/config/DiscoveryCollectorRegistrar.java` — one bean per category;
  `CategoryProperties.java` — the checked entries; `CollectorProperties.java` (`Discovery`),
  `SourcesConfiguration.java`; the categories in `application.yaml` (`helper2.categories`).
- `backend/src/main/java/ai/helper2/sources/support/RunInterval.java` — `staggered`, `nextDueDay`.
- `backend/src/main/kotlin/ai/helper2/shared/WebSources.kt` — the web sources.
- `backend/src/main/java/ai/helper2/platform/quota/QuotaCounter.java` (`add`), `RedisQuotaCounter.java`.
- `backend/src/main/java/ai/helper2/scout/internal/ReviewQueueCandidateReview.java` — `RESOURCE_CANDIDATE` items and
  the merge; `ResourceCandidateJson.java` — the candidate JSON v2, written, merged and read.
- `backend/src/main/kotlin/ai/helper2/scout/usecase/IndexAcceptedCandidate.kt` — accepting a candidate; ports
  `CandidateDetails`, `CatalogLookup`.
- `backend/src/main/java/ai/helper2/catalog/ReviewQueue.java` — open candidate, update, category filter;
  `ResourceStore.java` (`extra`), `CatalogRetention.java`.
- `backend/src/main/resources/db/migration/V8__resource_candidate.sql`, `V9__discovery_categories.sql`;
  `R__curated_seed.sql` — the directories, linked, not copied.
- `backend/src/main/java/ai/helper2/admin/usecase/ResolveReview.java` — accept = index, then decide.
- `frontend/src/components/sources/Sources.tsx` (family row groups), `DiscoveryCategories.tsx` (the notices),
  `frontend/src/components/home/radarLayout.ts`
  (`placeDot`), `radarRotation.ts` (groups), `HomeRadar.tsx`, `frontend/src/pages/admin/ReviewQueuePage.tsx`,
  `ScoutRunsPage.tsx`.
- `deploy/monitoring/helper2-rules.yaml` — `Helper2DiscoveryStale`.
- Tests: `TavilyDiscoveryCollectorTest`, `DiscoveryPagesTest`, `DiscoveryCollectorRegistrarTest`, `RunIntervalTest`,
  `IndexAcceptedCandidateTest`, `ResourceCandidateJsonTest`, `DiscoveryCategoriesMigrationTest`,
  `CatalogPurgeExpiredDataIT`, `ResourceStoreIT`, `ResiliencePoliciesConfigIT`, `ReportAndReviewIT`
  (`twoCategoriesFindingOnePageMakeOneItemThatKeepsWhatBothSearchesSaid`), `DiscoveryCategories.test.tsx`,
  `radarLayout.test.ts`, `radarRotation.test.ts`, `HomeRadar.test.tsx`, `ReviewQueuePage.test.tsx`,
  `deploy/monitoring/tests/helper2-rules-test.yaml`.
- Diagrams: [flow](../../diagrams/flow-0051-tavily-discovery.svg) · [sequence](../../diagrams/sequence-0051-tavily-discovery.svg).
