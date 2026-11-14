# 0013 — Discovery

**Depends on:** [0006](../foundation/0006-outbound-calls-and-resilience.md), [0007](../foundation/0007-configuration-secrets-runtime-settings.md), [0009](../foundation/0009-observability.md), [0010](../foundation/0010-data-retention.md), [0011](0011-scout-runner.md), [0012](0012-scout-collectors-and-events.md), [0014](0014-evidence-gated-enrichment.md), [0023](../search/0023-catalog-full-text-index.md), [0026](../search/0026-filters-facets-categories.md), [0027](../search/0027-live-web-store-first-daily-quota.md), [0033](../guide/0033-marking-ai-written-text-research-trail.md), [0036](../identity/0036-identity-and-the-admin-host.md), [0040](../frontend/0040-design-system.md)

## Context
The Scout's sources are good at models (Hugging Face), MCP servers (the registry) and repositories (GitHub). They miss
most of what a person looks for: prompt libraries, AI apps, SaaS products, model hosting, no-code builders,
self-hosted apps, browser extensions, tools for teachers, doctors and lawyers. These have no registry. They are found
on the open web, and in the awesome lists people keep on GitHub.

The open web is also where SEO pages live. A search for "best self-hosted AI" returns lists written to rank, not the
tools. If a web result could become a catalog entry by itself, anyone who writes a page could put a product in our
catalog. The courses collector ([0012](0012-scout-collectors-and-events.md)) already solves this for courses: results are
candidates in the admin review list.

The awesome-list collector ([0012](0012-scout-collectors-and-events.md)) read the top 10 lists and kept only their GitHub
links. Three things were thrown away: the smaller lists a big list links to ("Awesome RAG"), the links to a product's
own site, and the section headings ("Vector databases"), a person's own classification.

Sorting a web page needs to know some sites by name. A writing site (Medium, TechCrunch …) publishes articles about
many products. A directory (Product Hunt, G2 …) lists products: a page there is never a product's own site. These
lists change more often than the code, and a runtime setting ([0007](../foundation/0007-configuration-secrets-runtime-settings.md))
is a number with a maximum in code, audited at 100 characters a value: a list of hosts does not fit there.

## Decision
### One collector per category
1. **34 categories in 7 families, each its own collector.** The categories are one YAML list, `helper2.categories` in
   `application.yaml`. Each entry has an `id` (the catalog tag), a `family`, a `short-name` (the radar label, at most
   12 characters), a `name` (the Sources page's), its `words` and a `discovery` block:
   - `type`: what a product its searches find becomes (`PRODUCT`, `AGENT`, `PROMPT`, `SKILL`, `LIBRARY`, ...);
   - `queries`: one Tavily search each (most categories have one, five have two);
   - optional: `interval` (default `helper2.scout.discovery.interval`, 7 days), `enabled`, `min-score` (default 0.4),
     `require-keyword`, `index-lists`, `exclude-domains` (added to the shared list).

   The families and their categories:
   - **Prompts and skills**: prompt libraries, skills and GPTs.
   - **Everyday apps**: AI assistants, writing, translation, meetings and transcription, documents and PDF, research and
     papers, AI search, browser extensions, education and tutors, health.
   - **Creative and media**: image generation, video generation, voice and audio, design and presentations.
   - **Business**: customer support, voice agents, data and analytics, no-code, marketing and SEO, legal.
   - **Developers**: coding, agent platforms, LLM frameworks, RAG and vector databases, LLMOps and evaluation, AI
     security, fine-tuning.
   - **Hosting and infrastructure**: model hosting, AI cloud and PaaS, AI gateways.
   - **Run it yourself**: self-hosted, local AI.

   Each category is a `DiscoveryCollector` with the source id `discovery-<id>`: its own cursor, its own run
   record, its own row on the Sources page and its own dot on the Home radar. The descriptor carries the `family`
   (`SourceDescriptor.family`, `SourceInfo.family` in the API), so the Sources page groups the categories by it.
   Models, datasets, MCP servers and awesome lists come from their own collectors. There is no SaaS category: SaaS is
   how a product is sold, not what a person needs.

   Startup fails on an unknown family or type, an id that is not lower-case words with hyphens or is longer than 30
   characters (`discovery-` plus the id fits the source id's 40), a short name over 12 characters, a type a product page
   cannot have (a reading or course type), a category without a query, or two categories with one id
   (`CategoryProperties`, `DiscoveryCategory`).
2. **Registered by a `BeanRegistrar`.** How many collectors there are depends on the configuration, so
   `DiscoveryCollectorRegistrar` (Spring Framework 7 `BeanRegistrar`, imported by `CollectorsConfiguration`) registers
   one bean per category. **This is the one exception to [0007](../foundation/0007-configuration-secrets-runtime-settings.md):**
   the registrar runs before any bean exists, so it reads `helper2.categories` from the `Environment` through Spring
   Boot's `Binder`, not as a `@ConfigurationProperties` bean. The entries are checked while they bind, in code. The
   collectors themselves are built from the usual `@ConfigurationProperties` beans: the shared settings
   `helper2.sources.discovery` (base URL, token, timeouts; `H2_SOURCE_DISCOVERY_ENABLED`,
   `H2_SOURCE_DISCOVERY_DEADLINE`) and `helper2.scout.discovery` (interval, depth, results, score floor, excluded
   domains). All of them share one resilience policy, `collector-discovery`: a Tavily outage opens one circuit, not 34.
   The public app describes the same sources from the same settings (`ScoutSourceDescriptors`), so both apps list them.
3. **Staggered over the week.** A category that never ran is first due on day `(its place in the list) mod 7` (days
   since 1970, UTC; `RunInterval.staggered`), then every interval after its last complete pass. So 4 or 5 categories
   search a day, not 34 on the first. The place in the list spreads them evenly; a hash of the id spread them 2 to 9 a
   day. Adding a category changes only the first day of categories that never ran. A run of a category that is not due
   is SKIPPED with the next day it is due ("Not due yet: next pass on 2026-11-07"); the admin's Run now follows the same
   rule. The stagger only delays the first try. When a pass fails or meets the daily cap, the Scout keeps it open
   (`ScoutCursor.passStartedAt`, no resume cursor), and an open pass runs on the next run whatever the day. So a first
   try that fails is tried again the next day, not a week later.
4. **How it asks.** Following Tavily's search guide:
   - one short query per search, never one long query for everything;
   - the query describes the product page in that page's own words ("serverless inference API for open-weight LLMs and
     image models"), without "best" or "launched": they bring back pages written to rank, and news;
   - `topic: general`, `search_depth: advanced` (2 credits, best relevance; `H2_SCOUT_DISCOVERY_DEPTH=basic` halves the
     cost), `max_results: 10`;
   - `include_published_date`, `include_favicon` and `include_usage` on; `include_answer`, `include_raw_content` and
     `include_images` off. We keep what the search says about a page, never the page's full text, its images or an
     answer Tavily writes;
   - `exclude_domains` for social and video sites (no product pages there, and `linkedin.com` has its own collector,
     [0012](0012-scout-collectors-and-events.md)) and for AI directories and review sites (Product Hunt, There's An AI For
     That, Futurepedia, G2, Capterra, AlternativeTo, ...): their terms forbid copying their listings. We link to them
     instead: they are entries of the curated list (`R__curated_seed.sql`) with our own one-line description, and
     nothing taken from their pages. A category adds its own: course platforms for education, health portals and
     agencies for health, law sites and legal directories for legal;
   - only new pages: `start_date` = the previous complete pass, or `time_range: month` on the first.
5. **How it sorts each result** (code, no model; `DiscoveryCollector.keep`, then `DiscoveryPages.classify`), in
   this order:
   - dropped: Tavily score below the category's `min-score`; a page on Tavily's own site (`tavily.com` or a subdomain,
     `TavilySource.isTavilysOwnPage`, the rule live search uses too: Tavily's company profiles are its index, not a
     publisher's page); not about AI (`Classification.isAiRelated`);
   - **a GitHub repository or a home page is a product**, whatever its title says ("an open-source alternative to
     ChatGPT" is a product's own words);
   - **list page**: any other page with a list-like title ("12 best ...", "best AI coding tools", "X vs Y", "ChatGPT
     alternatives"). Indexed as an `ARTICLE` hit, with Tavily's excerpt (400 characters) as its description. It stays
     an `ARTICLE` even when its title names an event ("10 best AI tools for event planning"): only the news mapping
     infers `EVENT`, and an `EVENT` with no event date would escape both retention rules. A list helps a person
     compare; it claims nothing about the products in it. With `index-lists: false` list pages are dropped;
   - **product page** one level below a home page (a language segment such as `/en` does not count). A site page
     (`/pricing`, `/features`, `/download`, ...) stands for its site's home page, titled with the side of the page title
     that names the site ("Pricing | AnythingLLM", "AnythingLLM - Pricing" → "AnythingLLM"). Any other page is the
     product itself: big vendors have one product per page (`aws.amazon.com/bedrock`). Not when the path is writing,
     not on a writing host, not on a publisher's or a directory's host;
   - **a post about a product** is an ARTICLE candidate ([0014](0014-evidence-gated-enrichment.md), writing about a
     resource): a page below a writing place (`/blog/…`, `/news/…`, `/posts/…`, `/insights/…`), on a writing host
     (`blog.`, `news.`, or one of the writing hosts, point 21), or with a post's slug
     (`/how-we-built-our-ai-stack`). Not a post: a blog's index, an author's page (`medium.com/@someone`), docs, help,
     careers, legal and community pages, and pages on a directory's host;
   - with `require-keyword`, a result is kept only when its own title or excerpt has one of the category's words;
   - anything else is dropped.

   Health and legal use `min-score: 0.5`, `require-keyword: true` and `index-lists: false`: their searches pull
   general advice pages and law firms, and a list of "10 AI tools for doctors" is not worth indexing without review.
   The health searches cover tools for professionals (medical scribes, clinical notes) and for patients (health
   questions, symptoms).
6. **Every category a result belongs to.** A result gets the tag of its category, and of every category whose words
   are in its title or excerpt (at the start of a word, with hyphens read as spaces: `text-to-speech` fits "text to
   speech", `fine tuning platform` fits "fine-tuning platform", and `lora` does not fit "flora"). The words are
   compiled to patterns once, at startup. Words are narrow enough not to tag too much: `ai art generat` and
   `ai artwork`, not `ai art`; `no code ai`, `no code builder` and `no code app`, not `no code`; `tts api`, not `tts`;
   `ocr api` and `ocr model`, not `ocr`. A candidate's type is its category's type, ARTICLE for a post. A candidate
   keeps at most 12 tags (`Candidate.MAX_TAGS`), the most a candidate can have and still be accepted: its own category
   first, then the categories whose words it has, in the configured order. A list page keeps every category it fits.
7. **What is kept.** Each kept result carries, in its record's `source_record.extra` (and in the candidate until it is
   accepted), never shown:
   - `discovery`: `category`, `query`, `rank`, `score`, `searchDepth`, `shape` (`LIST`, `PRODUCT` or `ARTICLE`),
     `publishedDate`, `requestId`, `searchedAt`, `matchedWords` (per category, the words found) and `favicon`: the
     icon's URL as text, never loaded or shown;
   - `categories`: every category it belongs to.

   A list page keeps Tavily's 400-character excerpt, the one shown in search. A candidate keeps the whole excerpt, up
   to 2,400 characters, as evidence for its enrichment. `SourceHit.facts` carries these values to the catalog, outside
   the record's fingerprint, so a new `searchedAt` does not count as a change.
8. **Candidates wait for a person.** A product or a post is a `RESOURCE_CANDIDATE` review item. Its details are the
   candidate as JSON, version 2 (`ResourceCandidateJson`): source (the first collector that found it), URL, title,
   type, tags, excerpt, and `foundBy`: one entry per collector that found it, with what its search said. A page is
   proposed once per URL, and never when the catalog already has it, as a resource or as a source record of one (the
   same rules as course candidates). A decision on a candidate is kept in `candidate_decision` (kind, a one-way key of
   the URL made the way a resource id is, status, time; never the URL) for as long as the catalog runs, so a page rejected once is never proposed again, also after
   its review item is deleted ([0010](../foundation/0010-data-retention.md)). **When a
   second category finds a page that waits OPEN**, it is merged into that item: the tags are joined (at most 12 again:
   the categories that found it first, in the order they found it, then the others), the most specific type is kept (a
   self-hosted agent platform is an `AGENT`), and the collector is added to `foundBy` and to the item's reason. A
   decided page stays as it was.
9. **Accepting indexes it.** `ResolveReview` passes the item's details to `IndexAcceptedCandidate` (scout use case).
   The use case checks the candidate first (a known source, URL, title, a type that one of the collectors that found it
   declares, at most 12 tags), then:
   - a page another source listed after it was proposed (as a resource or as a source record of one) adds nothing:
     the item is ACCEPTED with the note "Already listed as <title>.", the decision is kept, and the answer names the
     listed resource (`alreadyListed`: id and title), so the page links to it and the admin can edit it there (port
     `CatalogLookup.listedAs`);
   - otherwise it is indexed as **one record** of the first collector that found it, with every category as a tag and
     every finder in its extra (`foundBy`), in the same transaction as the decision. One record, not one per finder:
     the same page and excerpt once, one enrichment, and the categories are already in the tags. It has no enrichment
     yet; the next Scout run takes it from the backlog, accepted products first, and the evidence gate checks what the
     model says about it ([0014](0014-evidence-gated-enrichment.md));
   - the record keeps the signals the first collector stated (`CandidateSignals`, in its `foundBy` entry as
     `signals`): the stars are its popularity, the newer of the last push and the latest release is its last update,
     archived and fork are its vitality ([0020](0020-resource-vitality.md)). A signal the collector did not
     state is not known, and so is every signal of a candidate opened before signals were written. Today the two
     GitHub collectors state them (`github-sdks`, `github-topic-leaders`).

   A candidate that fails the checks is refused (400) and the item stays open. Rejecting records the decision only. A
   category declares its product type and ARTICLE; a merged candidate may have the type of any category that found it.
   LinkedIn ([0012](0012-scout-collectors-and-events.md)) declares ARTICLE, NEWS, PRODUCT and EVENT.
10. **Credits are counted and capped.** Every discovery search adds its credits to the day's global credit counter, the
    one the live search counts on and the Sources page shows ([0027](../search/0027-live-web-store-first-daily-quota.md)),
    and to the Scout's own counter (`ScoutLiveWebCredits`; Redis, same key, field `scout-credits`). A search costs
    Tavily's `usage.credits`, or 2 (advanced) or 1 (basic) when the answer does not say. The metric is
    `helper2.live-web.credits{feature="scout-discovery"}`. Past `helper2.scout.daily-credits` (`H2_SCOUT_DAILY_CREDITS`,
    40) the categories due that day are SKIPPED ("Daily Tavily credit cap of the Scout reached") and try again the next
    day: the skip is `CollectedBatch.deferred`, so their pass stays open. The cap is checked again before each search:
    a pass that reaches it stops there, keeps its checkpoint and remembers the searches it made (§11). Every attempt counts, a retried one too
    (`ScoutLiveWebCredits.countedAttempt`): a failed attempt costs what its depth costs, since Tavily may have charged a
    request that timed out. When the counter cannot be read, the cap counts as reached. LinkedIn and the live web events scout ([0012](0012-scout-collectors-and-events.md)) count on the same
    counters and cap.
11. **Failed searches.** A search that fails alone is logged and skipped; the others, already paid for, are kept. A
    pass with a failed or a skipped search stays open: its resume cursor lists the searches that answered
    (`QueryProgress`, `queries-done:` and their queries), and the next run makes only the missing ones, over the same
    window. The checkpoint moves once every search of the pass has answered. When half or more of a category's
    searches fail (an outage, a circuit that opened mid-pass), its pass fails, its checkpoint and cursor stay, so the
    next daily run tries again. LinkedIn and the live web events scout follow the same rules; for the events scout a
    search counts as done only when its pages were read too (a model that ran out keeps the cursor as it was).
12. **Web sources are one predicate.** `WebSources.isWeb(id)` (`live-web`, `live-web-scout`, `course-scout`,
    `linkedin-scout`, `live-web-events-scout` and every `discovery-*`) decides which titles are cleaned as page titles
    (`WebPageTitle.forSource`, `ResourceRows`) and which NEWS and ARTICLE records the web retention deletes
    (`CatalogRetention`).
13. **Where people see it.**
    - **Sources page**: the categories come after the other sources in the one table, one row group per family
      ("Discovery · Developers"), with the rules in the note under it: "List articles are added without review",
      "Products are added only after a person checks them". Health and legal rows carry their notice: "Not medical
      advice. We do not check whether a tool is approved as a medical device. Claims are the vendor's own, quoted with
      their source." and "Not legal advice. We do not check whether a tool is right for your country or case."
    - **Home radar** ([0040](../frontend/0040-design-system.md)): every category is a dot. With about 50 sources, fixed
      slots would leave some out, so a dot is placed when it comes on, clear of the 5 to 10 dots on the radar, starting
      from its own point (it tends to come back to the same spot). New dots are picked in turn from the live sources,
      the collectors and the categories, so 34 categories do not crowd out the rest; within each group the sources
      queue, so every category comes on before any comes back.
    - **Back office**: Run now for every collector, also one that never ran; the review queue filters by category
      (`?category=`, `ReviewItem.categories`), names every collector that found a page and shows the health and legal
      notices on their candidates. A candidate is a link (https only, `rel="noopener noreferrer nofollow"`), its type,
      its categories and the excerpt.
    - **Terms**: "How we describe resources" and "Health and legal tools". **Privacy**: the weekly category searches
      and what is kept from them.
14. **Alerts.** `Helper2ScoutCollectorStale` leaves out `discovery-*`. One rule, `Helper2DiscoveryStale`, fires once per
    environment when any category has had no success for more than 8 days, and lists them.

### Deep search in awesome lists
15. **Follow nested lists.** A run reads the seed lists, then the lists they link to, then the lists those link to: at
    most `nested.max-depth` (2) steps from a seed, breadth first (`AwesomeCrawl`).
    - A link is a nested list when the repository's name says "awesome", or when it sits under a heading below the
      title that names a section of lists ("Other awesome lists", "Related lists", "Lists of lists").
    - It is followed only when its GitHub facts (`GET /repos/{owner}/{repo}`) say it is alive and about AI: not
      archived, not a fork, at least `min-repo-stars` (50) stars, and AI words in its name, description or topics.
    - Each list is read once per run: a set of names read or queued is the cycle guard. A renamed repository answers
      with its new name, which is checked too.
    - At most `nested.max-lists-per-run` (30) nested lists are tried per run.
    - A nested list becomes a list resource like a seed (ARTICLE, tag `awesome-list`), and the list index of
      [0023](../search/0023-catalog-full-text-index.md) indexes its words. A list that was read is not also an entry to
      classify. Its entries join the pass after the seeds' entries, under the same `max-entries-per-pass` (1000).
      Every seed list is read every pass, even when the first ones already fill `max-entries-per-pass`, so every list
      is a resource and is indexed.
16. **Limits per run.** Reading stops when `max-run-time` (15 min) or `max-readme-bytes-per-run` (50 MB of README) is
    spent; the lists left wait for the next pass. Every call goes through the collector's resilience policy
    (`collector-github-awesome`) and its GitHub client ([0006](../foundation/0006-outbound-calls-and-resilience.md)):
    one README at most 5 MB in 60 s. A rate limit on a seed stops the pass. A rate limit on a nested list stops the
    following and keeps what was read. A full run makes at most about 70 GitHub calls for lists (6 searches, 40
    READMEs, 30 facts) plus the GraphQL calls for entries: far below GitHub's 5,000 calls an hour.
17. **Links to product sites go to review.** The first link of an entry line names the entry. When it points outside
    GitHub, `ProductLinks` checks it, by code only:
    - https only; a name, not an IP address in any notation; no user info; no port; no private or reserved name
      (`localhost`, `.local`, `.internal`, `.corp`, `.home.arpa` and others); no punycode label (a look-alike name can
      hide in it); no link that redirects (a URL inside the path or the query, a redirect path such as `/out` or
      `/go`, a link shortener); not GitHub's own pages; not an excluded domain. The excluded domains are discovery's
      (directories, review, social and video sites) plus papers, chats, notebooks, package registries and app stores
      (`links.exclude-domains`).
    - The link becomes the product's home page or its one-level page, with no query and no fragment, by the rules of
      point 5 (`DiscoveryPages.productSite`): docs, blogs, articles, publisher hosts and deeper pages are skipped.
    - Each site is kept once per pass, at most `links.max-per-pass` (500), and only on the first run of a pass.
    - Each becomes a `RESOURCE_CANDIDATE` review item of type PRODUCT, never indexed straight away. Its title is the
      link's words (the product's name). It has no excerpt: the list's text is not kept. Its `foundBy` entry records
      `github-awesome`, the list's URL and the section's heading path.
    - At most `links.per-day` (50) new items a UTC day. `ReviewQueueCandidateReview` counts the items this collector
      opened since midnight UTC in the database. Past the cap, a link opens nothing new but still joins the OPEN item
      of the same site, so a product that discovery and a list both found is one item with both finders.
      When the cap held links back at the end of a pass, the runner gives the cursor `held-back` and the next run
      proposes the links again: the ones already open join their item, the next 50 are opened. That run reads the
      lists but classifies no entry again: the entries were done on the run before.
18. **Section headings become categories.** The category matcher ([0026](../search/0026-filters-facets-categories.md))
    gets the heading paths as one more input. Each heading of a path is read alone, like a tag: it gives a category
    when it is the category's id ("Voice and audio") or has one of its words ("Vector databases"). A category that
    needs a word (health, legal) never comes from a heading alone. No new matcher, no model.
    - On a candidate, the matched categories are its tags, at most `Candidate.MAX_TAGS` (12), in the configured order.
    - On an indexed resource, `JdbcCatalogWriter` reads the heading paths the list index already keeps
      (`awesome_list_entry.heading_path`). `IndexAwesomeLists` assigns the categories of a list's entries again after
      it writes them, so a new heading counts in the same run.
19. **Metrics.** `helper2.scout.awesome.lists{depth=seed|nested}` counts lists read, `helper2.scout.awesome.candidates`
    links proposed, `helper2.scout.awesome.skips{reason}` every skip: a nested list (`nested-seen`, `nested-depth`,
    `nested-cap`, `nested-archived`, `nested-fork`, `nested-few-stars`, `nested-not-ai`, `nested-unavailable`), a budget
    (`time-budget`, `size-budget`, `rate-limited`, `readme-unavailable`) or a link (`link-seen`, `link-cap`, and `link-`
    plus the reason of `ProductLinks`). The reasons are a fixed set ([0009](../foundation/0009-observability.md)). No
    URL or list text is logged.
20. **Configuration.** Under `helper2.scout.github-awesome` (`CollectorProperties.GitHubAwesome`):
    `H2_SCOUT_AWESOME_MAX_RUN_TIME`, `H2_SCOUT_AWESOME_MAX_README_BYTES`, `H2_SCOUT_AWESOME_NESTED_DEPTH`,
    `H2_SCOUT_AWESOME_NESTED_LISTS`, `H2_SCOUT_AWESOME_LINKS_PER_DAY`. Defaults in `application.yaml`. Depth 0 turns
    nested lists off; 0 links a day turns product links off.

### Discovery hosts
21. **A table of hosts.** `discovery_host` (`V1__schema.sql`): one row per host, its kind (WRITING or DIRECTORY), who
    added it, when and why. The host is the key, so a host is in one list only. V1 seeds the first lists. The Scout
    owns the table (`scout.DiscoveryHostStore`, `JdbcDiscoveryHostStore`).
22. **Read once per run.** Tavily discovery and the awesome-list collector read both lists at the start of each run and
    build their `DiscoveryPages` from them. A change applies at the next run; a run already going keeps the lists it
    started with. No snapshot, no refresh thread: the collectors are batch jobs, and nothing on a search path reads the
    lists.
23. **Fail closed.** A run whose lists cannot be read fails before any search, and its checkpoint stays. Discovery never
    runs on empty lists: an empty directory list would send every Product Hunt page to review as a product.
24. **Only a host name.** `HostNames.check`: lower case, no trailing dot, no "www.". Refused (400): a scheme, path, port
    or space; a name alone or a top-level domain ("com"); a registry's second level ("co.uk"); an IP address;
    github.com (discovery reads repositories by their own rule). An entry also matches every subdomain, so "com" would
    match nearly every site. At most 500 hosts.
25. **The page.** On `/admin/settings`, below the spending limits: the two lists, each host with who added it, when and
    why. Add: host, list, optional reason. Remove: a review that says what changes, then Confirm, with an optional
    reason. To move a host to the other list, remove it, then add it. API: `GET /api/v1/admin/discovery-hosts`,
    `POST /api/v1/admin/discovery-hosts`, `POST /api/v1/admin/discovery-hosts/{host}/remove`; ADMIN only, on the back
    office, CSRF on writes.
26. **Audited.** Each add and each remove is an `admin_audit` row in the same transaction: `ADD_DISCOVERY_HOST` or
    `REMOVE_DISCOVERY_HOST`, the host as target, the outcome (ADDED, ALREADY_LISTED, FULL, REMOVED, NOT_FOUND), the
    list it went to or came from, and the reason. A refused host name changes nothing and is not recorded.
27. **Retention.** A host stays until an administrator removes it. Its audit rows follow the audit trail's retention
    ([0010](../foundation/0010-data-retention.md)).

No new table for deep search, no new dependency.

**An admin's edit wins.** An admin edits a candidate in the review list, and a later finder keeps the edit
([0038](../backoffice/0038-back-office-and-review.md)).

### Code
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/liveweb/scout/DiscoveryCollector.java` — one category's
  searches, `keep` (Tavily's own pages, score, AI, shape), what is kept; `DiscoveryPages.java` — list, product or post,
  by URL and title, built from `HostLists`; `ScoutLiveWebCredits.java` — credits and the daily cap.
- `backend/libs/sources/src/main/java/ai/helper2/sources/tavily/TavilySource.java` — `isTavilysOwnPage`, `toHit`;
  `DiscoveryCategory.java`, `DiscoveryFamily.java`; `sources/config/CategoryProperties.java` — the checked entries;
  `CollectorProperties.java` (`Discovery`, `GitHubAwesome`); `support/RunInterval.java` — `staggered`, `nextDueDay`.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/config/scout/DiscoveryCollectorRegistrar.java` — one bean per
  category; `CollectorsConfiguration.java`; the categories in `application.yaml` (`helper2.categories`);
  `backend/apps/public/src/main/java/ai/helper2/sources/config/scout/ScoutSourceDescriptors.java`.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/github/scout/AwesomeCrawl.java` (nested lists, budgets),
  `ProductLinks.java` (link checks), `AwesomeMetrics.java`, `AwesomeListParser.java` (`Link`, `Entry.pointsAtList`),
  `GitHubAwesomeCollector.java` (`Deep`, `candidates`, `candidate`, `repoFacts`).
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/DiscoveryHosts.kt` — `HostKind`, `DiscoveryHost`,
  `HostLists`, `DiscoveryHostStore`, `HostNames`; `scout/usecase/ManageDiscoveryHosts.kt`;
  `scout/internal/JdbcDiscoveryHostStore.java`; `admin/web/DiscoveryHostsController.java`; `api/admin.yaml`
  (`DiscoveryHosts`, `DiscoveryHostEntry`, `DiscoveryHostAdd`, `DiscoveryHostRemove`).
- `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/ReviewQueueCandidateReview.java` — `RESOURCE_CANDIDATE`
  items, the merge, the daily link cap; `ResourceCandidateJson.java` — the candidate JSON, written, merged and read.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/Ports.kt` (`CandidateSignals`) — what a source states about a candidate;
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/IndexAcceptedCandidate.kt` — accepting a candidate;
  ports `CandidateDetails`, `CatalogLookup`; `scout/Ports.kt` (`CollectedBatch.newCandidatesPerDay`,
  `CandidateReview.propose`); `backend/apps/backoffice/src/main/java/ai/helper2/admin/usecase/ResolveReview.java`.
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/WebSources.kt`, `Categories.kt` (`CategoryInput.headings`);
  `backend/libs/platform/src/main/java/ai/helper2/platform/quota/QuotaCounter.java` (`add`), `RedisQuotaCounter.java`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/ReviewQueue.java` (open candidate, category filter,
  `candidatesOpenedToday`), `catalog/usecase/IndexAwesomeLists.java`, `catalog/internal/JdbcCatalogWriter.java`
  (`extra`, `assignCategories`), `JdbcAwesomeListIndex.java`, `JdbcCatalogRetention.java`.
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` (the `RESOURCE_CANDIDATE` review kind,
  `discovery_host` and its seed rows, `candidate_decision`); `catalog/internal/JdbcReviewQueue.java`
  (`proposeCandidate`, `candidateKnown`, `listedAs`), `JdbcReviewDecisions.java`; `R__curated_seed.sql` — the directories, linked, not copied;
  `platform/AuditTrail.java` (the two host actions).
- `frontend/src/features/sources/Sources.tsx` (family row groups), `DiscoveryCategories.tsx` (the notices),
  `frontend/src/features/home/radarLayout.ts` (`placeDot`), `radarRotation.ts` (groups), `HomeRadar.tsx`,
  `frontend/src/admin/ReviewQueuePage.tsx`, `ScoutRunsPage.tsx`, `DiscoveryHostsPanel.tsx`, `SettingsPage.tsx`;
  `frontend/src/i18n/locales/en.legal.json` (the Privacy and Terms pages).
- `deploy/monitoring/helper2-rules.yaml` — `Helper2DiscoveryStale`.
- Tests: `DiscoveryCollectorTest` (`tavilysOwnPagesAreNeitherCandidatesNorListPages`), `TavilySourceTest`,
  `DiscoveryPagesTest`, `DiscoveryCollectorRegistrarTest`, `RunIntervalTest`, `IndexAcceptedCandidateTest`,
  `ResourceCandidateJsonTest`, `AwesomeCrawlTest`, `ProductLinksTest`, `GitHubAwesomeDeepSearchTest`,
  `AwesomeListParserTest`, `CategoryMatcherTest`, `CollectorPropertiesTest`, `AwesomeListSearchIT`,
  `AwesomeDeepSearchIT`, `ManageDiscoveryHostsTest`, `JdbcDiscoveryHostStoreIT` (the seed), `DiscoveryHostsApiIT`,
  `CatalogPurgeExpiredDataIT`, `JdbcCatalogWriterIT`, `ResiliencePoliciesConfigIT`, `ReportAndReviewIT`
  (`twoCategoriesFindingOnePageMakeOneItemThatKeepsWhatBothSearchesSaid`), `DiscoveryCategories.test.tsx`,
  `radarLayout.test.ts`, `radarRotation.test.ts`, `HomeRadar.test.tsx`, `ReviewQueuePage.test.tsx`,
  `DiscoveryHostsPanel.test.tsx`, `deploy/monitoring/tests/helper2-rules-test.yaml`.

## Alternatives considered
- **One collector for every category** — one source on the radar and the Sources page, one run a week for all
  searches, one circuit. But a person could not see which category failed or brought what, all searches ran on one
  day, and one slow category held up the rest.
- **A `@Bean` returning a list of collectors** instead of a `BeanRegistrar` — no exception to [0007](../foundation/0007-configuration-secrets-runtime-settings.md), but Spring injects
  `List<Collector>` from beans of type `Collector`, not from a bean that is a list, so every consumer would need to know
  about it.
- **One record per finder on accept** — every category as its own provenance, but the same page and excerpt N times and
  N enrichments of the same text; the tags already say which categories it is in.
- **Index product pages or list links directly, let enrichment decide** — fast, but an SEO page or any link in a list
  becomes a catalog entry with no person in between.
- **A model sorts the results, picks the categories or maps headings** — better recall on odd pages and headings, but a
  model call per result, values that need an AI label, and a model that reads untrusted pages before anything is
  checked.
- **Search known directories with `include_domains`** (Product Hunt, There's An AI For That) — their terms forbid
  copying their listings, and the result would be their catalog, not ours.
- **Tavily `/crawl`, `/extract` or `include_raw_content`** — more text for the reviewer and the enrichment, more
  credits, and a copy of the page we would have to keep and justify; the excerpt is enough to decide.
- **Drop Tavily's own pages in `DiscoveryPages.classify`** — works too, but the rule then lives in two places; `keep()`
  reuses the one live search applies.
- **Follow every GitHub link of a list, then decide by facts** — finds lists known only by their topics, but one facts
  call per linked repository (thousands a day). Name and section find most lists for 30 calls.
- **A daily link cap counted in Redis** — the review items are already in PostgreSQL; counting them there needs no
  second store and survives a cache flush.
- **Host lists as a runtime setting of type list, as `H2_` variables, or a snapshot refreshed every 20 s** — the setting
  is a number audited at 100 characters; a variable needs a deploy; a snapshot is one more thread, not needed while only
  batch runs read the lists.

## Consequences
- The catalog grows in the areas no registry covers. No product enters it from the open web or from a list's link
  without a person; list pages are indexed as ARTICLE without review.
- Search can filter by the categories: every result carries their tags, and a resource also gets the categories of
  the sections it is listed under.
- A person reviews the candidates of 4 or 5 categories a day instead of 34 at once. The total is a guess (50-200 a
  week), to measure in the first weeks. If it is too many, raise a category's `min-score`, turn on its
  `require-keyword`, or turn it off (`enabled: false`). Lists add at most 50 product links a day.
- Credits: 39 searches × 2 credits = 78 a week, ~335 a month (390 in a month with 5 passes). The daily cap (40) is
  about four times a normal day (5 categories × ~1.2 searches × 2 credits); on LinkedIn's day its 20 basic searches
  bring the day to about 32. Deep search spends no credits.
- The global daily credit cap of the live search (`H2_LIVE_WEB_DAILY_CREDITS`) counts the Scout's discovery credits
  too: a day of discovery leaves less for visitors. The Scout itself checks only its own cap.
- 34 run records a week and 34 rows on the Sources page, grouped by family so they stay readable.
- The registrar reads its configuration outside `@ConfigurationProperties` (the exception to [0007](../foundation/0007-configuration-secrets-runtime-settings.md) above). Its entries are
  still checked at startup and in `DiscoveryCollectorRegistrarTest`.
- The Scout reads up to 40 lists a day instead of 10, and the focused lists bring niche tools. A list known only by its
  topics, under a plain heading, is not followed. A product site on plain http, on an IDN name or linked only through
  HTML (`<a href>`) is skipped. A category given by a heading stays after the list stops linking the resource, until
  the resource's categories are assigned again (its next upsert, or a backfill).
- A new news site or directory is one form in the back office, without a deploy; the next discovery run uses it, not
  within seconds. A wrong entry has a cost: a directory entry drops that site's product pages, a writing entry turns
  its pages into article candidates. The audit row says who added it and why; removing it undoes it at the next run.
  The places of a site (`/blog`, `/docs`, `/careers` …) and GitHub's own pages stay in code: they are rules about
  addresses, not about sites.
- Retention ([0010](../foundation/0010-data-retention.md)): list pages (ARTICLE) are deleted 180 days after
  `retrieved_at` (`H2_RETENTION_WEB_NEWS`); an accepted product stays as long as it is listed; a review item stays
  until it is decided, then 180 days. The search facts go with their record or item.
- Privacy: no user data in the searches. `discovery_host` holds the GitHub user ID of the administrator who added a
  host and the reason they wrote. The Privacy page lists both, with their retention. This change adds no data flow.
- List pages are indexed without review. Their claims are not facts in the catalog: they are `ARTICLE`s, and
  enrichment cites only what they say about themselves.
- An accepted candidate is in search at once, without a type check by a model; its description is the search excerpt
  (up to 2,400 characters) until enrichment runs.
- A category whose search keeps failing misses its pages for that week; its run record says why.
- Tavily is itself an AI product, and discovery never proposes its site. It can still come in through the curated list
  or a link in an awesome list (point 17 does not check it): the rule is about Tavily's index, not about Tavily.

### Known limits
- Each product that discovery finds (consumer tools, hosting, prompt libraries) waits for an admin: the weekly review load and the share accepted decide each category's `min-score` and the categories.
- Deep search in awesome lists skips product sites on plain http, on an IDN (punycode) name or linked only through HTML. A list linked under a plain heading whose name does not say "awesome" is not followed. A category given by a list heading stays until the resource's categories are assigned again. All seed lists are read every pass, even when the first ones fill the entry cap.
- No weekly alert watches the Scout's credits (`helper2_live_web_credits_total{feature=~"scout-discovery|scout-linkedin"}`).
- There is no host deny list and no cap on list pages per host per pass; vendor compare and alternatives pages are not dropped; Chrome Web Store, Firefox add-on and GPT links are not recognised; press pages keep long excerpts, and an excerpt can hold an email address; open candidates do not expire; any collector can be the source of a candidate.
- No replay evaluation on a recorded snapshot tunes each category's `min-score`, the list-title rule and the words, although the search facts are kept per record and candidate.
- A query that keeps failing keeps its category's pass open: only that query is searched again at each run, until it answers. A pass left open with candidates held back over the daily cap loses those of the searches already done (the resume cursor skips them). A failed attempt counts what its depth costs, so a 429 is counted too.
- Accepting a candidate that was listed meanwhile does not apply the admin's edits to the listed page.
- An accepted candidate keeps only its first finder's search facts in full in `discovery` (the others are in `foundBy`).
- A category's first day is its place in the list, so moving a category that never ran moves its first day.
- The Scout checks only its own daily credit cap, not the live search's global one.
- The Terms describe the labels the UI shows ("AI-written", "AI summary", "Extracted by AI"); "AI-estimated" is not among them. The health and legal notices are on the Sources rows and the admin candidate cards, not on the resource page of an accepted tool.

### STRIDE
| Threat | Risk | Control |
|---|---|---|
| Spoofing | A page pretends to be a product; a look-alike site or a list that pretends to be about AI; Tavily's own profile pages | Every product waits for a person; the URL is checked again on accept (`CanonicalUrl`), the type must be one a finder declares, tags must be tag-shaped, the source a known collector. Punycode, IP, user info and private names skipped; a nested list needs AI words, 50 stars, not archived or forked. Tavily's own pages are dropped. |
| Tampering | A hostile link (`javascript:`, `data:`, an open redirect, a shortener, an internal host); an admin adds a host that matches too much | https only, redirect patterns and shorteners skipped, private names and IPs skipped; we never fetch a candidate URL here, the evidence ladder checks it again before reading ([0014](0014-evidence-gated-enrichment.md)). Host checks, the 500 cap, one list per host, an audit row per change. A merge only joins tags and finders, never the URL or the title. |
| Repudiation | Not knowing why an item is in review, or who changed a host list | `foundBy` records each collector, the search or the list URL and section; every host change has an audit row with the admin, the time, the list and the reason. |
| Information disclosure | List text or personal data kept | Fixed queries with no user data; the favicon URL is text and never loaded; only the link, its words (≤ 100 characters) and the heading path (≤ 200) of a list entry; no README text. The admin ID is shown on the admin page only. |
| Denial of service / cost | Credits; a list graph that never ends, a huge README, a flood of links | One or two calls per category a week, a shared circuit breaker (`collector-discovery`), `max_results` 10, a daily credit cap; depth 2, 30 nested lists, a set against cycles, 15 min and 50 MB a run, 5 MB a README, 500 links a pass, 50 new items a day; 500 hosts, read with one query. |
| Elevation of privilege | — | Only an admin can accept or change a host list (back office, VPN, [0036](../identity/0036-identity-and-the-admin-host.md)). The admin page renders a candidate as text and links only `https://` URLs. The category filter is checked as a tag and is a bound parameter. |

- **SEO spam**: list pages are indexed without review. Links to other sites carry `rel="nofollow ugc"` on our pages, so
  a page that gets in gains no ranking from us. Control characters are removed from every web text.
- **Prompt injection** (OWASP LLM Top 10): no model reads a search result or a list here. Web text reaches a model in
  enrichment ([0014](0014-evidence-gated-enrichment.md)) and, as a search result, in the guide's choice step
  ([0031](../guide/0031-guide-pipeline.md)). Both treat it as untrusted text and check every quote. The classifier gets
  a section name, cleaned as in [0023](../search/0023-catalog-full-text-index.md). Categories from headings are made
  by code, so they carry no AI label ([0033](../guide/0033-marking-ai-written-text-research-trail.md)).
- **Harm (health, legal)**: a health or legal tool can be unsafe or wrong for a person's case. We do not judge it; the
  notices say so, and the Terms say we do not list tools whose main purpose is illegal or deceptive.

### AI architecture check
| # | Layer | State |
|---|---|---|
| 1 | Model | Covered: no model sorts results, picks categories or follows lists. The awesome-list classifier sees more entries (the nested lists'); its daily cap is unchanged. |
| 2 | Agent Framework | Deferred, safe: no agent. |
| 3 | Orchestration | Covered: one Scout step per category, staggered; a failed or capped pass stays open; deep search stops on its budgets and the next pass goes on. |
| 4 | Tool & API | Covered: Tavily search and the GitHub REST and GraphQL APIs, each under its collector's policy. |
| 5 | Memory | Deferred, safe: nothing about users. |
| 6 | Knowledge & RAG | Covered: more lists in the list index; more categories for retrieval filters. |
| 7 | Data | Covered: `discovery_host` in V1; review items and heading paths; provenance in `foundBy` and `source_record.extra`. |
| 8 | Browser & Computer Use | Deferred, safe: no page is fetched; product links are only stored for review. |
| 9 | Guardrails | Covered: URL rules and the shapes, Tavily's own pages dropped, `ProductLinks`, host checks, the tag cap, `require-keyword`. |
| 10 | Observability | Covered: run records, credit metrics, the deep-search metrics, `Helper2DiscoveryStale`. |
| 11 | Evaluation | Gap, deferred: the share of good candidates and category precision from headings are measured once the review has run some weeks and the category eval has labels ([0026](../search/0026-filters-facets-categories.md)). |
| 12 | Deployment | Covered: settings with safe defaults, no chart change. Rollback: `enabled: false` per category, depth 0 and 0 links a day. |
| 13 | Security & Identity | Covered: the host endpoints are ADMIN only on the back office, CSRF on writes; no new secret. |
| 14 | Human-in-the-Loop | Covered: every product and post waits for a person; a host list changes only by an admin, audited. |
| 15 | Cost & Reliability | Covered: the Scout's daily credit cap on the global counter, a shared circuit, deep search's time and size budgets. |

### Points for a lawyer
1. Is Tavily's written OK for scheduled discovery searches whose list pages we index still to confirm (see
   [sources.md](../../sources.md))?
2. Do GitHub's terms (Acceptable Use, API terms) allow reading up to 40 README files a day through the API, following
   links from list to list, to build a search index and a review list?
3. Is keeping a link to a third-party product site, with the link's words and the list's section heading, and showing
   it (after our review) as a catalog entry, allowed without the site owner's consent? Does it make us responsible for
   the linked content (DSA, Swiss law on links)?
4. Is a list's section heading used as a category a reuse of the list author's selection or arrangement (copyright, EU
   sui generis database right)?
5. Is keeping a hashed key of rejected candidate URLs with no time limit allowed (`candidate_decision`)? The key is
   pseudonymous: a LinkedIn post's URL names a person ([0010](../foundation/0010-data-retention.md)).

## Diagrams
Discovery: [flow](../../diagrams/flow-0013-tavily-discovery.svg) · [sequence](../../diagrams/sequence-0013-tavily-discovery.svg) ·
deep search in awesome lists: [flow](../../diagrams/flow-0013-awesome-deep-search.svg) · [sequence](../../diagrams/sequence-0013-awesome-deep-search.svg) ·
discovery hosts: [flow](../../diagrams/flow-0013-discovery-hosts.svg) · [sequence](../../diagrams/sequence-0013-discovery-hosts.svg)

## Backlog
- Decides: [F2.3](https://github.com/silvioincalza/helper2.ai/issues/33).
- Open issues that change it:
  - [S2.3.5](https://github.com/silvioincalza/helper2.ai/issues/197): kept and dropped candidates per `discovery_host` row, one-step move, public-suffix check.
