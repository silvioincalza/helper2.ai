# 0051 — Tavily discovery: one web search per category of the AI world, lists indexed, products reviewed

**Status.** Accepted. **Builds on:** [0018](0018-scout-runner.md), [0019](0019-evidence-gated-enrichment.md), [0020](0020-scout-collectors.md), [0033](../foundation/0033-resilience-policies.md), [0037](../search/0037-tavily-store-and-daily-quota.md)

## Context
The Scout's sources are good at models (Hugging Face), MCP servers (the registry) and repositories (GitHub). They
miss most of what a person looks for: prompt libraries, AI apps, SaaS products, model hosting, no-code builders,
self-hosted apps, browser extensions. These have no registry. They are found on the open web.

The open web is also where SEO pages live. A search for "best self-hosted AI" returns lists written to rank, not the
tools. If a web result could become a catalog entry by itself, anyone who writes a page could put a product in our
catalog. The courses collector ([0032](../guide/0032-guide-intents.md) §8) already solves this for courses: results
are candidates in the admin review list.

## Decision
1. **Collector `tavily-discovery`** (`TavilyDiscoveryCollector`). One Tavily search per category, every 7 days
   (`H2_SCOUT_DISCOVERY_INTERVAL`). The categories are config (`helper2.scout.discovery.categories`), a
   comma-separated list of `TYPE|tag|query|word;word` entries, so no entry may contain a comma. An unreadable entry,
   a tag that is not lower-case words with hyphens, or a reading or course type stops startup.
   - `TYPE`: what a product found by this search becomes (`PRODUCT`, `AGENT`, `PROMPT`, `SKILL`, `LIBRARY`, ...).
   - `tag`: the catalog tag of the category (`self-hosted`, `model-hosting`, ...).
   - `query`: the search.
   - `word;word`: words that put any result in the category, whichever search found it.
2. **33 searches in 30 categories**: prompt libraries, skills, AI assistants, writing, translation, image
   generation, video generation, voice and music, voice agents, meetings and transcription, customer support,
   documents and PDF, research and papers, coding, self-hosted, local AI, model hosting, AI cloud and PaaS, AI
   gateways, agent platforms, LLM frameworks, RAG and vector databases (with document parsing), LLMOps and evaluation,
   fine-tuning, AI security, no-code, browser extensions, AI search, data and analytics, design and presentations.
   Two entries may share a tag: one category, two searches. Models, datasets, MCP servers and awesome lists come from
   their own collectors. There is no SaaS search: SaaS is how a product is sold, not what a person needs, and the
   query brought back boilerplate kits and idea lists.
3. **How it asks.** Following Tavily's search guide:
   - one short query per category, never one long query for everything;
   - the query describes the product page in that page's own words ("serverless inference API for open-weight LLMs
     and image models"), without "best" or "launched": they bring back pages written to rank, and news;
   - `topic: general`, `search_depth: advanced` (2 credits, best relevance; `H2_SCOUT_DISCOVERY_DEPTH=basic` halves
     the cost), `max_results: 10`, `include_answer: false`, `include_raw_content: false`;
   - `exclude_domains` for social and video sites (no product pages there) and for AI directories and review sites
     (Product Hunt, There's An AI For That, Futurepedia, G2, Capterra, AlternativeTo, ...): their terms forbid
     copying their listings. We link to them instead: they are entries of the curated list (`R__curated_seed.sql`)
     with our own one-line description, and nothing taken from their pages;
   - only new pages: `start_date` = the previous complete pass, or `time_range: month` on the first.
4. **How it sorts each result** (code, no model), in this order:
   - dropped: Tavily score below `min-score` (0.4), or not about AI (`Classification.isAiRelated`);
   - **a GitHub repository or a home page is a product**, whatever its title says ("an open-source alternative to
     ChatGPT" is a product's own words);
   - **list page**: any other page with a list-like title ("12 best ...", "best AI coding tools", "X vs Y",
     "ChatGPT alternatives"). Indexed as an `ARTICLE` hit, with Tavily's excerpt as its description. A list helps a
     person compare; it claims nothing about the products in it;
   - **product page** one level below a home page (a language segment such as `/en` does not count). A site page
     (`/pricing`, `/features`, `/download`, ...) stands for its site's home page, titled with the side of the page
     title that names the site ("Pricing | AnythingLLM", "AnythingLLM - Pricing" → "AnythingLLM"). Any other page is
     the product itself: big vendors have one product per page (`aws.amazon.com/bedrock`). Not when the path is
     writing (`/blog`, `/docs`, a post's slug such as `/how-we-built-our-ai-stack`), not on a writing host (`blog.`,
     `docs.`, `help.` ...), not on a publisher's or directory's host;
   - anything else is dropped.
5. **Every category a result belongs to.** Results are merged by URL across the searches. A result gets the tag of
   each category whose search found it, and of each category whose words are in its title or excerpt (at the start
   of a word, with hyphens read as spaces: `text-to-speech` fits "text to speech", `fine tun` fits "fine-tuning",
   and `lora` does not fit "flora"). A product's type is the most
   specific type of the categories whose search found it: a self-hosted agent platform is an `AGENT`.
   A candidate keeps at most 12 tags (`Candidate.MAX_TAGS`), the most a candidate can have and still be accepted:
   the categories whose search found it first, in the configured order, then the ones whose words it has.
6. **Candidates wait for a person.** A product is a `RESOURCE_CANDIDATE` review item (migration `V8`). Its details
   are the candidate as JSON: source, URL, title, type, tags, excerpt. The page is proposed once per URL, and never
   when the catalog already has it (the same rules as course candidates).
7. **Accepting indexes it.** `ResolveReview` passes the item's details to `IndexAcceptedCandidate` (scout use case).
   `ResourceCandidateJson` writes the details when the page is proposed and reads them back here: one codec, in one
   module. The use case checks the candidate first (known source, URL, title, product type, at most 12 tags), then:
   - a page another source indexed after it was proposed is left as that source wrote it: only the decision is
     recorded (port `CatalogLookup`);
   - otherwise it is indexed as a `tavily-discovery` record with its tags, in the same transaction as the decision.
     It has no enrichment yet; the next Scout run takes it from the backlog ([0018](0018-scout-runner.md) §7) and
     the evidence gate checks what the model says about it.

   A candidate that fails the checks is refused (400) and the item stays open. Rejecting records the decision only.
8. **Failed searches.** A search that fails alone is logged and skipped; the others, already paid for, are kept. When
   half or more fail (an outage, a circuit that opened mid-pass), the pass fails and its checkpoint stays, so the next
   daily run searches the same pages again instead of losing a week.
9. **The back office** shows a resource candidate as a link (https only, `rel="noopener noreferrer nofollow"`), its
   type, its categories and the excerpt.

## Consequences
- The catalog grows in the areas no registry covers. No product enters it from the open web without a person; list
  pages are indexed as ARTICLE without review.
- Search can filter by the categories: every result carries their tags.
- A person reviews about 50-200 candidates a week (a guess, to measure in the first weeks). If it is too many, raise
  `min-score` or remove a category.
- Credits: 33 searches × 2 credits × ~4.3 passes ≈ 285 credits a month (330 in a month with 5 passes). With
  `tavily-scout` (~42) and `course-scout` (~43) the Scout uses ~370 credits a month, out of the plan's 14,000 shared
  with live search ([0037](../search/0037-tavily-store-and-daily-quota.md)).
- Retention ([0034](../operations/0034-data-retention.md)): list pages (ARTICLE) are deleted 180 days after
  `retrieved_at` (`H2_RETENTION_WEB_NEWS`); an accepted product stays as long as it is listed; a review item stays
  until it is decided, then 180 days. The Privacy page says so.
- List pages are indexed without review. Their claims are not facts in the catalog: they are `ARTICLE`s, and
  enrichment cites only what they say about themselves.
- An accepted candidate is in search at once, without a type check by a model; its description is the search
  excerpt until enrichment runs.
- A category whose search keeps failing misses its pages for that week; the run log says which one.

## Security (STRIDE)
- **Spoofing / tampering**: a page can pretend to be a product. Mitigation: it waits for a person; the URL is checked
  again on accept (`CanonicalUrl`), the type must be a product type, the tags must be tag-shaped, the source must be
  a known collector.
- **Information disclosure**: no user data in the queries; they are fixed config.
- **Denial of service / cost**: one call per category per week, a circuit breaker and retry
  (`collector-tavily-discovery`), `max_results` 10, a failed search is not repeated in the same pass.
- **Elevation**: only an admin can accept (back office, VPN, [0035](../edge/0035-admin-host.md)). The admin page
  renders the candidate as text and links only `https://` URLs, so a hostile title or URL cannot run script.
- **SEO spam**: list pages are indexed without review. Links to other sites carry `rel="nofollow ugc"` on our pages,
  so a page that gets in gains no ranking from us. Control characters are removed from every web text.
- **Prompt injection**: web text reaches a model in enrichment ([0019](0019-evidence-gated-enrichment.md)) and, as a
  search result, in the guide's choice step ([0015](../guide/0015-ai-guide-evidence-gate.md)): accepted products
  before enrichment, list pages when a person asks for reading. Both treat it as untrusted text and check every
  quote.

## Alternatives considered
- **Index product pages directly, let enrichment decide** — fast, but an SEO page becomes a catalog entry with no
  person in between.
- **A model sorts the results and picks the categories** — better recall on odd page shapes, but a model call per
  result and a model that reads untrusted pages before anything is checked. URL rules sort lists from product sites;
  category words tag well enough, and the admin sees the tags before accepting.
- **One category per result** (the one whose search found it) — simple, but a self-hosted local chat app would only
  be found under one of its categories.
- **Search known directories with `include_domains`** (Product Hunt, There's An AI For That) — their terms forbid
  copying their listings, and the result would be their catalog, not ours.
- **Tavily `/crawl` or `/extract` on each candidate** — more text for the reviewer, more credits; the excerpt is enough
  to decide, and enrichment reads the page's own record later.

## Code
- `backend/src/main/java/ai/helper2/sources/tavily/TavilyDiscoveryCollector.java` — the searches, the sorting, the
  categories.
- `backend/src/main/java/ai/helper2/sources/config/CollectorProperties.java` (`Discovery`), `SourcesConfiguration.java`;
  the categories in `application.yaml` (`helper2.scout.discovery`).
- `backend/src/main/java/ai/helper2/scout/internal/ReviewQueueCandidateReview.java` — `RESOURCE_CANDIDATE` items.
- `backend/src/main/kotlin/ai/helper2/scout/usecase/IndexAcceptedCandidate.kt` — accepting a candidate; ports
  `CandidateDetails`, `CatalogLookup`.
- `backend/src/main/java/ai/helper2/scout/internal/ResourceCandidateJson.java` — the candidate JSON, written and read.
- `backend/src/main/resources/db/migration/R__curated_seed.sql` — the directories, linked, not copied.
- `backend/src/main/java/ai/helper2/admin/usecase/ResolveReview.java` — accept = index, then decide.
- `backend/src/main/resources/db/migration/V8__resource_candidate.sql`.
- `frontend/src/pages/admin/ReviewQueuePage.tsx` — `ResourceCandidate`.
- Tests: `TavilyDiscoveryCollectorTest`, `IndexAcceptedCandidateTest`, `ResourceCandidateJsonTest`,
  `CollectorPropertiesTest`, `CatalogPurgeExpiredDataIT`, `PageHtmlTest`,
  `ReportAndReviewIT` (`acceptingAResourceCandidateIndexesItAndAnUnreadableOneStaysOpen`), `ReviewQueuePage.test.tsx`.
- Diagrams: [flow](../../diagrams/flow-0051-tavily-discovery.svg) · [sequence](../../diagrams/sequence-0051-tavily-discovery.svg).
