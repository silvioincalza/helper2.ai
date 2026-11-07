# 0058 — Deep search in awesome lists: nested lists, product links for review, section headings as categories

**Status.** Accepted. **Builds on:** [0006](../foundation/0006-outbound-http.md), [0007](../foundation/0007-configuration-and-secrets.md), [0018](0018-scout-runner.md), [0020](0020-scout-collectors.md), [0026](../operations/0026-observability.md), [0033](../foundation/0033-resilience-policies.md), [0051](0051-tavily-discovery.md), [0053](../search/0053-category-filter.md), [0055](../search/0055-search-inside-awesome-lists.md), [0056](0056-evidence-ladder.md), [0034](../operations/0034-data-retention.md), [0038](../guide/0038-ai-content-marking.md)

## Context
The awesome-list collector ([0020](0020-scout-collectors.md) §5) reads the README of the top 10 AI awesome lists and
keeps the GitHub repositories they link. [0055](../search/0055-search-inside-awesome-lists.md) made the words around
each entry searchable. Three things were still thrown away:
- **Lists of lists.** Big lists link to smaller, focused lists ("Awesome RAG", "Awesome MCP servers"). Those are often
  the best source for a niche, and we never read them.
- **Product sites.** Many entries link to a product's own site, not to GitHub (a hosted vector database, a voice
  studio). We kept only GitHub links, so commercial tools were missing.
- **Section headings.** A list sorts its entries under headings such as "Vector databases" or "Text-to-speech". That
  is a person's classification, and our categories ([0053](../search/0053-category-filter.md)) did not use it.

I want the Scout to read deeper, without new storage, without a model, and with the same limits and review as
discovery ([0051](0051-tavily-discovery.md)).

## Decision
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
     [0055](../search/0055-search-inside-awesome-lists.md) indexes its words. A list that was read is not also an
     entry to classify. Its entries join the pass after the seeds' entries, under the same `max-entries-per-pass`
     (1000).
2. **Limits per run.** Reading stops when `max-run-time` (15 min) or `max-readme-bytes-per-run` (50 MB of README) is
   spent; the lists left wait for the next pass. Every call goes through the collector's resilience policy
   (`collector-github-awesome`, [0033](../foundation/0033-resilience-policies.md)) and its GitHub client
   ([0006](../foundation/0006-outbound-http.md)): one README at most 5 MB in 60 s. A rate limit on a seed stops the
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
4. **Section headings become categories.** The category matcher ([0053](../search/0053-category-filter.md)) gets the
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
6. **Configuration.** Under `helper2.scout.github-awesome` (`CollectorProperties.GitHubAwesome`, [0007](../foundation/0007-configuration-and-secrets.md)):
   `H2_SCOUT_AWESOME_MAX_RUN_TIME`, `H2_SCOUT_AWESOME_MAX_README_BYTES`, `H2_SCOUT_AWESOME_NESTED_DEPTH`,
   `H2_SCOUT_AWESOME_NESTED_LISTS`, `H2_SCOUT_AWESOME_LINKS_PER_DAY`. Defaults in `application.yaml`. Depth 0 turns
   nested lists off; 0 links a day turns product links off.

No new table, no migration, no new dependency.

## Consequences
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

## STRIDE
| Threat | Risk | Control |
|---|---|---|
| Spoofing | A look-alike site or a list that pretends to be about AI | Punycode, IP, user info and private names are skipped; a nested list needs AI words, 50 stars, not archived or forked; product links are never indexed without a person. |
| Tampering | A hostile link: `javascript:`, `data:`, an open redirect, a shortener, an internal host for SSRF | https only, redirect patterns and shorteners skipped, private names and IPs skipped. We never fetch a candidate URL here; the evidence ladder checks it again before reading ([0056](0056-evidence-ladder.md)). |
| Repudiation | Not knowing why an item is in review | foundBy records the collector, the list URL and the section; the reason line names the finders. |
| Information disclosure | List text or personal data kept | Only the link, its words (≤ 100 characters) and the heading path (≤ 200) are kept; no snippet, no README text. Retention of review items is unchanged ([0034](../operations/0034-data-retention.md)). |
| Denial of service | A list graph that never ends, a huge README, a flood of links | Depth 2, 30 nested lists, a set against cycles, 15 min and 50 MB per run, 5 MB per README, 500 links a pass, 50 new items a day. |
| Elevation of privilege | — | No new endpoint or role; the write path is the Scout's. |

OWASP LLM Top 10: no model reads the new inputs. The classifier still gets the section name, cleaned as in
[0055](../search/0055-search-inside-awesome-lists.md). Categories from headings are code-made, not AI-made, so they
carry no AI label ([0038](../guide/0038-ai-content-marking.md)).

## AI architecture check
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
| 11 | Evaluation | Gap, deferred: category precision from headings is measured by the category eval ([0053](../search/0053-category-filter.md)) once its labels exist. |
| 12 | Deployment | Covered: settings with safe defaults, no chart change, no migration. Rollback: depth 0 and 0 links a day. |
| 13 | Security & Identity | Covered: no new endpoint, no new secret. |
| 14 | Human-in-the-Loop | Covered: every product link waits for a person; at most 50 new items a day. |
| 15 | Cost & Reliability | Covered: no credits spent; GitHub calls bounded per run; time and size budget. |

## Points for a lawyer
1. Do GitHub's terms (Acceptable Use, API terms) allow reading up to 40 README files a day through the API, following
   links from list to list, to build a search index and a review list?
2. Is keeping a link to a third-party product site, with the link's words and the list's section heading, and showing
   it (after our review) as a catalog entry, allowed without the site owner's consent? Does it make us responsible for
   the linked content (DSA, Swiss law on links)?
3. Is a list's section heading used as a category a reuse of the list author's selection or arrangement (copyright,
   EU sui generis database right)?

## Alternatives considered
- **Follow every GitHub link, then decide by facts** — finds lists known only by their topics, but one facts call per
  linked repository (thousands a day). Rejected: name and section find most lists for 30 calls.
- **Index product links straight away** — faster, but a list can link anything; discovery already sends products to
  review. Rejected: same rule for both.
- **A model maps headings to categories** — better recall on odd headings, but a call per heading, values that need an
  AI label, and a model reading untrusted text. Rejected: code first, measured by the category eval.
- **A daily cap counted in Redis** (like the classifier's budget) — but the review items are already in PostgreSQL, and
  counting them there needs no second store and survives a cache flush.

## Code
- `backend/apps/helper2/src/main/java/ai/helper2/sources/github/AwesomeCrawl.java` (nested lists, budgets), `ProductLinks.java`
  (link checks), `AwesomeMetrics.java`, `AwesomeListParser.java` (`Link`, `Entry.pointsAtList`),
  `GitHubAwesomeCollector.java` (`Deep`, `candidates`, `candidate`, `repoFacts`).
- `backend/apps/helper2/src/main/java/ai/helper2/sources/tavily/DiscoveryPages.java` (`productSite`, now shared),
  `sources/config/CollectorProperties.java`, `SourcesConfiguration.java`; `application.yaml`.
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/Categories.kt` (`CategoryInput.headings`),
  `scout/Ports.kt` (`CollectedBatch.newCandidatesPerDay`, `CandidateReview.propose` with a daily cap),
  `scout/usecase/RunScout.kt`.
- `backend/apps/helper2/src/main/java/ai/helper2/scout/internal/ReviewQueueCandidateReview.java`, `ResourceCandidateJson.java`
  (`awesome` in foundBy), `catalog/ReviewQueue.java` (`candidatesOpenedToday`),
  `catalog/usecase/IndexAwesomeLists.java`, `catalog/internal/AwesomeListIndex.java`, `ResourceStore.java`
  (`assignCategories`).
- `frontend/src/i18n/locales/en.json` (the Privacy and Terms pages).
- Tests: `AwesomeCrawlTest`, `ProductLinksTest`, `GitHubAwesomeDeepSearchTest`, `AwesomeListParserTest`,
  `CategoryMatcherTest`, `CollectorPropertiesTest`, `AwesomeListSearchIT`, `AwesomeDeepSearchIT`.

## Roadmap
See [the roadmap](../../roadmap.md): the lawyer points above; follow lists known only by their topics; read HTML links
in READMEs.

## Diagrams
[flow](../../diagrams/flow-0058-awesome-deep-search.svg) · [sequence](../../diagrams/sequence-0058-awesome-deep-search.svg)
