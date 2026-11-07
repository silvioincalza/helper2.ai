# 0022 — Discovery: one collector per category, deep search in awesome lists, discovery hosts

**Status.** Accepted. Merges old 0051, 0058, 0063 (texts merged in PR 5 of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0051"></a>

## Part: was ADR 0051 — Tavily discovery: one collector per category of the AI world, lists indexed, products reviewed

**Status.** Accepted. **Builds on:** [0007](../foundation/0007-configuration-secrets-and-settings.md), [0020](0020-scout-runner.md), [0023](0023-evidence-gated-enrichment.md), [0021](0021-collectors-and-events.md), [0006](../foundation/0006-outbound-calls-and-resilience.md), [0014](../search/0014-tavily-store-and-daily-quota.md), [0028](../frontend/0028-design-system.md) **Amended by:** [0023](0023-evidence-gated-enrichment.md) (posts about products are ARTICLE candidates), [0026](../edge/0026-back-office-and-review.md) (an admin edits a candidate; a later finder keeps the edit).

### Context
The Scout's sources are good at models (Hugging Face), MCP servers (the registry) and repositories (GitHub). They
miss most of what a person looks for: prompt libraries, AI apps, SaaS products, model hosting, no-code builders,
self-hosted apps, browser extensions, tools for teachers, doctors and lawyers. These have no registry. They are found
on the open web.

The open web is also where SEO pages live. A search for "best self-hosted AI" returns lists written to rank, not the
tools. If a web result could become a catalog entry by itself, anyone who writes a page could put a product in our
catalog. The courses collector ([0016 (part was 0032)](../guide/0016-guide-pipeline.md) §8) already solves this for courses: results
are candidates in the admin review list.

### Decision
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
   one bean per category. **This is the one exception to [0007](../foundation/0007-configuration-secrets-and-settings.md):**
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
     enrichment yet; the next Scout run takes it from the backlog ([0020](0020-scout-runner.md) §7) and the evidence
     gate checks what the model says about it.

   A candidate that fails the checks is refused (400) and the item stays open. Rejecting records the decision only.
   A category declares its product type and ARTICLE; a merged candidate may have the type of any category that found
   it. LinkedIn ([0021](0021-collectors-and-events.md)) declares ARTICLE, NEWS, PRODUCT and EVENT.
10. **Credits are counted and capped.** Every discovery search adds its credits to the day's global credit counter,
    the one the live search counts on and the Sources page shows ([0014](../search/0014-tavily-store-and-daily-quota.md)),
    and to the Scout's own counter (`ScoutTavilyCredits`; Redis, same key, field `scout-credits`). A search costs
    Tavily's `usage.credits`, or 2 (advanced) or 1 (basic) when the answer does not say. The metric is
    `helper2.live-web.credits{feature="scout-discovery"}`. Past `helper2.scout.daily-credits`
    (`H2_SCOUT_DAILY_CREDITS`, 40) the categories due that day are SKIPPED ("Daily Tavily credit cap of the
    Scout reached") and try again the next day: the skip is `CollectedBatch.deferred`, so their pass stays open. When
    the counter cannot be read, the cap counts as reached. LinkedIn ([0021](0021-collectors-and-events.md)) counts on
    the same counters and cap, with the feature `scout-linkedin`.
11. **Failed searches.** A search that fails alone is logged and skipped; the others, already paid for, are kept. When
    half or more of a category's searches fail (an outage, a circuit that opened mid-pass), its pass fails, its
    checkpoint stays and its pass stays open, so the next daily run searches the same pages again.
12. **Web sources are one predicate.** `WebSources.isWeb(id)` (`live-web`, `live-web-scout`, `course-scout`,
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
    - **Home radar** ([0028](../frontend/0028-design-system.md)): every category is a dot. With about 50
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

### Consequences
- The catalog grows in the areas no registry covers. No product enters it from the open web without a person; list
  pages are indexed as ARTICLE without review.
- Search can filter by the categories: every result carries their tags.
- A person reviews the candidates of 4 or 5 categories a day instead of 30 at once. The total is a guess (50-200 a
  week), to measure in the first weeks. If it is too many, raise a category's `min-score`, turn on its
  `require-keyword`, or turn it off (`enabled: false`).
- Credits: 39 searches × 2 credits = 78 a week, ~335 a month (390 in a month with 5 passes). With `live-web-scout`
  (~42), `course-scout` (~43) and `linkedin-scout` (~86, [0021](0021-collectors-and-events.md)) the Scout uses ~506
  credits a month, out of the plan's 14,000 shared with live search. The daily cap (40) is about four times a normal
  day (5 categories × ~1.2 searches × 2 credits); on LinkedIn's day its 20 basic searches bring the day to about 32.
- The global daily credit cap of the live search (`H2_LIVE_WEB_DAILY_CREDITS`) now counts the Scout's discovery
  credits too: a day of discovery leaves less for visitors. The Scout itself checks only its own cap.
- 34 run records a week instead of one, and 34 rows on the Sources page: grouped by family so they stay readable.
- The registrar reads its configuration outside `@ConfigurationProperties` (the 0007 exception above). Its entries
  are still checked at startup and in `DiscoveryCollectorRegistrarTest`.
- Retention ([0035](../operations/0035-data-retention.md)): list pages (ARTICLE) are deleted 180 days after
  `retrieved_at` (`H2_RETENTION_WEB_NEWS`); an accepted product stays as long as it is listed; a review item stays
  until it is decided, then 180 days. The search facts go with their record or item. The Privacy page says so.
- List pages are indexed without review. Their claims are not facts in the catalog: they are `ARTICLE`s, and
  enrichment cites only what they say about themselves.
- An accepted candidate is in search at once, without a type check by a model; its description is the search
  excerpt (up to 2,400 characters) until enrichment runs.
- A category whose search keeps failing misses its pages for that week; its run record says why.

### Security (STRIDE)
- **Spoofing / tampering**: a page can pretend to be a product. Mitigation: it waits for a person; the URL is checked
  again on accept (`CanonicalUrl`), the type must be a product type, the tags must be tag-shaped, the source must be
  a known collector. A merge only joins tags and finders; it never changes the URL or the title.
- **Information disclosure**: no user data in the queries; they are fixed config. The favicon URL is kept as text and
  never loaded, so no visitor's browser calls the site.
- **Denial of service / cost**: one or two calls per category per week, a shared circuit breaker and retry
  (`collector-discovery`), `max_results` 10, a failed search is not repeated in the same pass, and a daily credit cap.
- **Elevation**: only an admin can accept (back office, VPN, [0024](../edge/0024-identity-and-admin-host.md)). The admin page
  renders the candidate as text and links only `https://` URLs, so a hostile title or URL cannot run script. The
  category filter is checked as a tag before it reaches SQL, and is a bound parameter.
- **SEO spam**: list pages are indexed without review. Links to other sites carry `rel="nofollow ugc"` on our pages,
  so a page that gets in gains no ranking from us. Control characters are removed from every web text.
- **Prompt injection**: web text reaches a model in enrichment ([0023](0023-evidence-gated-enrichment.md)) and, as a
  search result, in the guide's choice step ([0016](../guide/0016-guide-pipeline.md)): accepted products
  before enrichment, list pages when a person asks for reading. Both treat it as untrusted text and check every
  quote. The longer product excerpt (2,400 characters) is more untrusted text for the same gate.
- **Harm (health, legal)**: a health or legal tool can be unsafe or wrong for a person's case. We do not judge it; the
  notices say so, and the Terms say we do not list tools whose main purpose is illegal or deceptive.

### Alternatives considered
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

### Code
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/tavily/TavilyDiscoveryCollector.java` — one category's searches and
  what is kept; `DiscoveryPages.java` — list or product, by URL and title; `DiscoveryCategory.java`,
  `DiscoveryFamily.java`; `ScoutTavilyCredits.java` — credits and the daily cap.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/config/DiscoveryCollectorRegistrar.java` — one bean per category;
  `CategoryProperties.java` — the checked entries; `CollectorProperties.java` (`Discovery`),
  `SourcesConfiguration.java`; the categories in `application.yaml` (`helper2.categories`).
- `backend/libs/sources/src/main/java/ai/helper2/sources/support/RunInterval.java` — `staggered`, `nextDueDay`.
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/WebSources.kt` — the web sources.
- `backend/libs/platform/src/main/java/ai/helper2/platform/quota/QuotaCounter.java` (`add`), `RedisQuotaCounter.java`.
- `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/ReviewQueueCandidateReview.java` — `RESOURCE_CANDIDATE` items and
  the merge; `ResourceCandidateJson.java` — the candidate JSON v2, written, merged and read.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/IndexAcceptedCandidate.kt` — accepting a candidate; ports
  `CandidateDetails`, `CatalogLookup`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/ReviewQueue.java` — open candidate, update, category filter;
  `ResourceStore.java` (`extra`), `CatalogRetention.java`.
- `backend/libs/schema/src/main/resources/db/migration/V8__resource_candidate.sql`, `V9__discovery_categories.sql`;
  `R__curated_seed.sql` — the directories, linked, not copied.
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/usecase/ResolveReview.java` — accept = index, then decide.
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
- Diagrams: [flow](../../diagrams/flow-0022-tavily-discovery.svg) · [sequence](../../diagrams/sequence-0022-tavily-discovery.svg).

<a id="part-0058"></a>

## Part: was ADR 0058 — Deep search in awesome lists: nested lists, product links for review, section headings as categories

**Status.** Accepted. **Builds on:** [0006](../foundation/0006-outbound-calls-and-resilience.md), [0007](../foundation/0007-configuration-secrets-and-settings.md), [0020](0020-scout-runner.md), [0021](0021-collectors-and-events.md), [0031](../operations/0031-observability.md), [0006](../foundation/0006-outbound-calls-and-resilience.md), [the part that was ADR 0051](#part-0051), [0013](../search/0013-filters-facets-and-categories.md), [0010](../search/0010-catalog-full-text-index.md), [0023](0023-evidence-gated-enrichment.md), [0035](../operations/0035-data-retention.md), [0018](../guide/0018-ai-content-marking-and-research-trail.md)

### Context
The awesome-list collector ([0021 (part was 0020)](0021-collectors-and-events.md) §5) reads the README of the top 10 AI awesome lists and
keeps the GitHub repositories they link. [0010](../search/0010-catalog-full-text-index.md) made the words around
each entry searchable. Three things were still thrown away:
- **Lists of lists.** Big lists link to smaller, focused lists ("Awesome RAG", "Awesome MCP servers"). Those are often
  the best source for a niche, and we never read them.
- **Product sites.** Many entries link to a product's own site, not to GitHub (a hosted vector database, a voice
  studio). We kept only GitHub links, so commercial tools were missing.
- **Section headings.** A list sorts its entries under headings such as "Vector databases" or "Text-to-speech". That
  is a person's classification, and our categories ([0013](../search/0013-filters-facets-and-categories.md)) did not use it.

I want the Scout to read deeper, without new storage, without a model, and with the same limits and review as
discovery ([the part that was ADR 0051](#part-0051)).

### Decision
1. **Follow nested lists.** A run reads the seed lists, then the lists they link to, then the lists those link to:
   at most `nested.max-depth` (2) steps from a seed, breadth first (`AwesomeCrawl`).
   - A link is a nested list when the repository's name says "awesome", or when it sits under a heading below the
     title that names a section of lists ("Other awesome lists", "Related lists", "Lists of lists").
   - It is followed only when its GitHub facts (`GET /repos/{owner}/{repo}`) say it is alive and about AI: not
     archived, not a fork, at least `min-repo-stars` (50) stars, and AI words in its name, description or topics.
   - Each list is read once per run: a set of names read or queued is the cycle guard. A renamed repository answers
     with its new name, which is checked too.
   - At most `nested.max-lists-per-run` (30) nested lists are tried per run.
   - A nested list becomes a list resource like a seed (ARTICLE, tag `awesome-list`), and the list index of
     [0010](../search/0010-catalog-full-text-index.md) indexes its words. A list that was read is not also an
     entry to classify. Its entries join the pass after the seeds' entries, under the same `max-entries-per-pass`
     (1000).
2. **Limits per run.** Reading stops when `max-run-time` (15 min) or `max-readme-bytes-per-run` (50 MB of README) is
   spent; the lists left wait for the next pass. Every call goes through the collector's resilience policy
   (`collector-github-awesome`, [0006](../foundation/0006-outbound-calls-and-resilience.md)) and its GitHub client
   ([0006](../foundation/0006-outbound-calls-and-resilience.md)): one README at most 5 MB in 60 s. A rate limit on a seed stops the
   pass, as before. A rate limit on a nested list stops the following and keeps what was read. A full run makes at most
   about 70 GitHub calls for lists (6 searches, 40 READMEs, 30 facts) plus the GraphQL calls for entries: far below
   GitHub's 5,000 calls an hour.
3. **Links to product sites go to review.** The first link of an entry line names the entry. When it points outside
   GitHub, `ProductLinks` checks it, by code only:
   - https only; a name, not an IP address in any notation; no user info; no port; no private or reserved name
     (`localhost`, `.local`, `.internal`, `.corp`, `.home.arpa` and others); no punycode label (a look-alike name can
     hide in it); no link that redirects (a URL inside the path or the query, a redirect path such as `/out` or `/go`,
     a link shortener); not GitHub's own pages; not an excluded domain. The excluded domains are discovery's
     (directories, review, social and video sites) plus papers, chats, notebooks, package registries and app stores
     (`links.exclude-domains`).
   - The link becomes the product's home page or its one-level page, with no query and no fragment, by the rules
     discovery uses (`DiscoveryPages.productSite`): docs, blogs, articles, publisher hosts and deeper pages are skipped.
   - Each site is kept once per pass, at most `links.max-per-pass` (500), and only on the first run of a pass.
   - Each becomes a RESOURCE_CANDIDATE review item of type PRODUCT, never indexed straight away. Its title is the
     link's words (the product's name). It has no snippet: the list's text is not kept. Its foundBy entry records
     `github-awesome`, the list's URL and the section's heading path.
   - At most `links.per-day` (50) new items a UTC day. `ReviewQueueCandidateReview` counts the items this collector
     opened since midnight UTC in the database. Past the cap, a link opens nothing new but still joins the OPEN item of
     the same site, so a product that discovery and a list both found is one item with both finders.
4. **Section headings become categories.** The category matcher ([0013](../search/0013-filters-facets-and-categories.md)) gets the
   heading paths as one more input. Each heading of a path is read alone, like a tag: it gives a category when it is
   the category's id ("Voice and audio") or has one of its words ("Vector databases"). A category that needs a word
   (health, legal) never comes from a heading alone. No new matcher, no model.
   - On a candidate, the matched categories are its tags, at most `Candidate.MAX_TAGS` (12), in the configured order.
   - On an indexed resource, `ResourceStore` reads the heading paths the list index already keeps
     (`awesome_list_entry.heading_path`). `IndexAwesomeLists` assigns the categories of a list's entries again after
     it writes them, so a new heading counts in the same run.
5. **Metrics.** `helper2.scout.awesome.lists{depth=seed|nested}` counts lists read,
   `helper2.scout.awesome.candidates` links proposed, `helper2.scout.awesome.skips{reason}` every skip: a nested list
   (`nested-seen`, `nested-depth`, `nested-cap`, `nested-archived`, `nested-fork`, `nested-few-stars`, `nested-not-ai`,
   `nested-unavailable`), a budget (`time-budget`, `size-budget`, `rate-limited`, `readme-unavailable`) or a link
   (`link-seen`, `link-cap`, and `link-` plus the reason of `ProductLinks`). The reasons are a fixed set. No URL or list
   text is logged.
6. **Configuration.** Under `helper2.scout.github-awesome` (`CollectorProperties.GitHubAwesome`, [0007](../foundation/0007-configuration-secrets-and-settings.md)):
   `H2_SCOUT_AWESOME_MAX_RUN_TIME`, `H2_SCOUT_AWESOME_MAX_README_BYTES`, `H2_SCOUT_AWESOME_NESTED_DEPTH`,
   `H2_SCOUT_AWESOME_NESTED_LISTS`, `H2_SCOUT_AWESOME_LINKS_PER_DAY`. Defaults in `application.yaml`. Depth 0 turns
   nested lists off; 0 links a day turns product links off.

No new table, no migration, no new dependency.

### Consequences
- The Scout reads up to 40 lists a day instead of 10, and the focused lists bring niche tools.
- Commercial tools linked from lists reach the review list, with the list and section that named them.
- A resource gets categories from the sections it is listed under, so more of the catalog can be filtered by category.
- All seed lists are now read every pass, even when the first ones already fill `max-entries-per-pass`. Before, the
  read stopped there. More README calls, but every list becomes a resource and is indexed.
- A list known only by its topics, under a plain heading, is not followed: topics are read from the facts of a link
  that is already a candidate.
- A product site on plain http, on an IDN name or linked only through HTML (`<a href>`) is skipped.
- A category given by a heading stays after the list stops linking the resource, until the resource's categories are
  assigned again (its next upsert, or a backfill).

### STRIDE
| Threat | Risk | Control |
|---|---|---|
| Spoofing | A look-alike site or a list that pretends to be about AI | Punycode, IP, user info and private names are skipped; a nested list needs AI words, 50 stars, not archived or forked; product links are never indexed without a person. |
| Tampering | A hostile link: `javascript:`, `data:`, an open redirect, a shortener, an internal host for SSRF | https only, redirect patterns and shorteners skipped, private names and IPs skipped. We never fetch a candidate URL here; the evidence ladder checks it again before reading ([0023](0023-evidence-gated-enrichment.md)). |
| Repudiation | Not knowing why an item is in review | foundBy records the collector, the list URL and the section; the reason line names the finders. |
| Information disclosure | List text or personal data kept | Only the link, its words (≤ 100 characters) and the heading path (≤ 200) are kept; no snippet, no README text. Retention of review items is unchanged ([0035](../operations/0035-data-retention.md)). |
| Denial of service | A list graph that never ends, a huge README, a flood of links | Depth 2, 30 nested lists, a set against cycles, 15 min and 50 MB per run, 5 MB per README, 500 links a pass, 50 new items a day. |
| Elevation of privilege | — | No new endpoint or role; the write path is the Scout's. |

OWASP LLM Top 10: no model reads the new inputs. The classifier still gets the section name, cleaned as in
[0010](../search/0010-catalog-full-text-index.md). Categories from headings are code-made, not AI-made, so they
carry no AI label ([0018](../guide/0018-ai-content-marking-and-research-trail.md)).

### AI architecture check
| # | Layer | State |
|---|---|---|
| 1 | Model | Covered: no new model call. The classifier sees more entries (the nested lists'); its daily cap is unchanged. |
| 2 | Agent Framework | Deferred, safe: no agent. |
| 3 | Orchestration | Covered: part of the Scout pass. Budgets stop the read; the next pass resumes. A rate limit on a nested list keeps what was read. |
| 4 | Tool & API | Covered: GitHub REST and GraphQL under the collector's policy; one more call per nested list (`/repos/{owner}/{repo}`). |
| 5 | Memory | Deferred, safe: nothing about users. |
| 6 | Knowledge & RAG | Covered: more lists in the list index; more categories for retrieval filters. |
| 7 | Data | Covered: no new table. Review items and heading paths as before; provenance in foundBy. |
| 8 | Browser & Computer Use | Deferred, safe: no page is fetched; product links are only stored for review. |
| 9 | Guardrails | Covered: `ProductLinks` (URL safety, normalisation), AI and liveness check for nested lists, tag cap, require-keyword categories. |
| 10 | Observability | Covered: the three metrics above and one log line per budget stop. |
| 11 | Evaluation | Gap, deferred: category precision from headings is measured by the category eval ([0013](../search/0013-filters-facets-and-categories.md)) once its labels exist. |
| 12 | Deployment | Covered: settings with safe defaults, no chart change, no migration. Rollback: depth 0 and 0 links a day. |
| 13 | Security & Identity | Covered: no new endpoint, no new secret. |
| 14 | Human-in-the-Loop | Covered: every product link waits for a person; at most 50 new items a day. |
| 15 | Cost & Reliability | Covered: no credits spent; GitHub calls bounded per run; time and size budget. |

### Points for a lawyer
1. Do GitHub's terms (Acceptable Use, API terms) allow reading up to 40 README files a day through the API, following
   links from list to list, to build a search index and a review list?
2. Is keeping a link to a third-party product site, with the link's words and the list's section heading, and showing
   it (after our review) as a catalog entry, allowed without the site owner's consent? Does it make us responsible for
   the linked content (DSA, Swiss law on links)?
3. Is a list's section heading used as a category a reuse of the list author's selection or arrangement (copyright,
   EU sui generis database right)?

### Alternatives considered
- **Follow every GitHub link, then decide by facts** — finds lists known only by their topics, but one facts call per
  linked repository (thousands a day). Rejected: name and section find most lists for 30 calls.
- **Index product links straight away** — faster, but a list can link anything; discovery already sends products to
  review. Rejected: same rule for both.
- **A model maps headings to categories** — better recall on odd headings, but a call per heading, values that need an
  AI label, and a model reading untrusted text. Rejected: code first, measured by the category eval.
- **A daily cap counted in Redis** (like the classifier's budget) — but the review items are already in PostgreSQL, and
  counting them there needs no second store and survives a cache flush.

### Code
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/github/AwesomeCrawl.java` (nested lists, budgets), `ProductLinks.java`
  (link checks), `AwesomeMetrics.java`, `AwesomeListParser.java` (`Link`, `Entry.pointsAtList`),
  `GitHubAwesomeCollector.java` (`Deep`, `candidates`, `candidate`, `repoFacts`).
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/tavily/DiscoveryPages.java` (`productSite`, now shared),
  `sources/config/CollectorProperties.java`, `SourcesConfiguration.java`; `application.yaml`.
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/Categories.kt` (`CategoryInput.headings`),
  `scout/Ports.kt` (`CollectedBatch.newCandidatesPerDay`, `CandidateReview.propose` with a daily cap),
  `scout/usecase/RunScout.kt`.
- `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/ReviewQueueCandidateReview.java`, `ResourceCandidateJson.java`
  (`awesome` in foundBy), `catalog/ReviewQueue.java` (`candidatesOpenedToday`),
  `catalog/usecase/IndexAwesomeLists.java`, `catalog/internal/AwesomeListIndex.java`, `ResourceStore.java`
  (`assignCategories`).
- `frontend/src/i18n/locales/en.json` (the Privacy and Terms pages).
- Tests: `AwesomeCrawlTest`, `ProductLinksTest`, `GitHubAwesomeDeepSearchTest`, `AwesomeListParserTest`,
  `CategoryMatcherTest`, `CollectorPropertiesTest`, `AwesomeListSearchIT`, `AwesomeDeepSearchIT`.

### Roadmap
See [the roadmap](../../roadmap.md): the lawyer points above; follow lists known only by their topics; read HTML links
in READMEs.

### Diagrams
[flow](../../diagrams/flow-0022-awesome-deep-search.svg) · [sequence](../../diagrams/sequence-0022-awesome-deep-search.svg)

<a id="part-0063"></a>

## Part: was ADR 0063 — Discovery hosts: the writing sites and directories are kept in the back office

**Status.** Accepted. **Builds on:** [0024](../edge/0024-identity-and-admin-host.md), [0026](../edge/0026-back-office-and-review.md), [the part that was ADR 0051](#part-0051), [the part that was ADR 0058](#part-0058), [0007](../foundation/0007-configuration-secrets-and-settings.md), [0023](0023-evidence-gated-enrichment.md)

### Context
Discovery knows some sites by name ([0023](0023-evidence-gated-enrichment.md)). A writing site (Medium, TechCrunch …)
publishes articles about many products: a post there is an ARTICLE candidate. A directory (Product Hunt, G2 …) lists
products: a page there is never kept, and never taken for a product's own site. Both lists were constants in
`DiscoveryPages`. A new news site or a new directory needed a pull request and a deploy, and the review list filled
with pages of a site the code did not know yet.

The runtime settings ([0007](../foundation/0007-configuration-secrets-and-settings.md)) are numbers with a maximum in code, and their
audit keeps 100 characters a value. A list of hosts does not fit there.

### Decision
1. **A table of hosts.** `discovery_host` (V17): one row per host, its kind (WRITING or DIRECTORY), who added it,
   when and why. The host is the key, so a host is in one list only. V17 seeds the lists that were in code. The Scout
   owns the table (`scout.DiscoveryHostStore`, `JdbcDiscoveryHostStore`).
2. **Read once per run.** Tavily discovery and the awesome-list collector read both lists at the start of each run
   and build their `DiscoveryPages` from them. A change applies at the next run; a run already going keeps the lists
   it started with. No snapshot, no refresh thread: the collectors are batch jobs, and nothing on a search path reads
   the lists.
3. **Fail closed.** A run whose lists cannot be read fails before any search, and its checkpoint stays. Discovery
   never runs on empty lists: an empty directory list would send every Product Hunt page to review as a product.
4. **Only a host name.** `HostNames.check`: lower case, no trailing dot, no "www.". Refused (400): a scheme, path,
   port or space; a name alone or a top-level domain ("com"); a registry's second level ("co.uk"); an IP address;
   github.com (discovery reads repositories by their own rule). An entry also matches every subdomain, so "com" would
   match nearly every site. At most 500 hosts.
5. **The page.** On `/admin/settings`, below the spending limits: the two lists, each host with who added it, when
   and why. Add: host, list, optional reason. Remove: a review that says what changes, then Confirm, with an
   optional reason. To move a host to the other list, remove it, then add it. API: `GET /api/v1/admin/discovery-hosts`,
   `POST /api/v1/admin/discovery-hosts`, `POST /api/v1/admin/discovery-hosts/{host}/remove`; ADMIN only, on the back office,
   CSRF on writes.
6. **Audited.** Each add and each remove is an `admin_audit` row in the same transaction: `ADD_DISCOVERY_HOST` or
   `REMOVE_DISCOVERY_HOST`, the host as target, the outcome (ADDED, ALREADY_LISTED, FULL, REMOVED, NOT_FOUND), the
   list it went to or came from, and the reason. A refused host name changes nothing and is not recorded.
7. **Retention.** A host stays until an administrator removes it. Its audit rows follow the audit trail's retention
   ([0035](../operations/0035-data-retention.md)).

### Consequences
- A new news site or directory is one form in the back office, without a deploy. The next discovery run uses it.
- Not within seconds, like a spending limit: a category runs every few days, so the change shows at its next run.
- A wrong entry has a cost: a directory entry drops that site's product pages, a writing entry turns its pages into
  article candidates. The audit row says who added it and why; removing it undoes it at the next run.
- The places of a site (`/blog`, `/docs`, `/careers` …) and GitHub's own pages stay in code: they are rules about
  addresses, not about sites, and change with the code that reads them.
- Privacy: the table holds the GitHub user ID of the administrator who added a host and the reason they wrote. The
  Privacy page lists it with its retention.

### Security and privacy (STRIDE)
- **Spoofing**: only a signed-in ADMIN on the admin host; GitHub is the identity provider
  ([0024](../edge/0024-identity-and-admin-host.md)).
- **Tampering**: an admin could add a host that matches too much ("com", "co.uk"), or move a product's site into a
  directory. Mitigation: the host checks above, the 500 cap, one list per host, and an audit row for each change.
- **Repudiation**: every change has an audit row with the admin, the time, the list and the reason.
- **Information disclosure**: the lists are site names, public anyway. The admin ID is shown on the admin page only.
- **Denial of service**: at most 500 hosts; each run reads them with one query.
- **Elevation**: unchanged; the endpoints are under `/api/v1/admin`, ADMIN only, with CSRF on writes.

### Alternatives considered
- **A runtime setting of type list** ([0007](../foundation/0007-configuration-secrets-and-settings.md)) — reuses its history and page,
  but its value is a number and its audit keeps 100 characters; a changed list would show as one long line.
- **`H2_` variables** with today's lists as defaults — simple, but a change still needs a deploy.
- **A snapshot per replica, refreshed every 20 s** — like the runtime settings; not needed while only batch runs read
  the lists, and one more thread to run.

### Code
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/DiscoveryHosts.kt` — `HostKind`, `DiscoveryHost`, `HostLists`,
  `DiscoveryHostStore`, `HostNames`.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/ManageDiscoveryHosts.kt`;
  `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/JdbcDiscoveryHostStore.java`, `ScoutConfiguration.java`.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/tavily/DiscoveryPages.java` (built from `HostLists`),
  `TavilyDiscoveryCollector.java`, `sources/github/GitHubAwesomeCollector.java`, `ProductLinks.java`.
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/web/DiscoveryHostsController.java`; `api/admin.yaml`
  (`DiscoveryHosts`, `DiscoveryHostEntry`, `DiscoveryHostAdd`, `DiscoveryHostRemove`).
- `backend/libs/schema/src/main/resources/db/migration/V17__discovery_host.sql`; `identity/AuditTrail.java` (the two actions).
- `frontend/src/pages/admin/DiscoveryHostsPanel.tsx`, `SettingsPage.tsx`.
- Tests: `ManageDiscoveryHostsTest`, `JdbcDiscoveryHostStoreIT` (the seed), `DiscoveryHostsApiIT`, `DiscoveryPagesTest`,
  `TavilyDiscoveryCollectorTest`, `DiscoveryHostsPanel.test.tsx`.
- Diagrams: [flow](../../diagrams/flow-0022-discovery-hosts.svg) · [sequence](../../diagrams/sequence-0022-discovery-hosts.svg).
