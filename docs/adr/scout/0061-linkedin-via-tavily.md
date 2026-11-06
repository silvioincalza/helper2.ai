# 0061 — LinkedIn via Tavily: public articles, posts, company pages and events, all reviewed

**Status.** Accepted. **Builds on:** [0018](0018-scout-runner.md), [0020](0020-scout-collectors.md), [0022](0022-next-events.md), [0033](../foundation/0033-resilience-policies.md), [0034](../operations/0034-data-retention.md), [0037](../search/0037-tavily-store-and-daily-quota.md), [0051](0051-tavily-discovery.md)

## Context
Much of the AI world is announced on LinkedIn first: a company launches a product, a lab posts a model, a meetup
opens its sign-up. The Scout does not see any of it.

LinkedIn gives us no allowed way in. Its User Agreement forbids scraping, crawlers and bots. Its API has no search:
it manages your own profile and company page, and most of it needs partner approval. So we never call LinkedIn.

Tavily already indexes public LinkedIn pages. A Tavily search restricted to `linkedin.com` finds them, under the same
terms as our other Tavily searches.

LinkedIn pages are about people more than any other source: a post has an author, and the author's name is in the
page title and in the URL.

## Decision
**Names (6 Nov 2026).** The collector is "LinkedIn" in the product; the live web search behind it is the adapter of [0037](../search/0037-tavily-store-and-daily-quota.md).

1. **Collector `linkedin-scout`** (`LinkedInCollector`). One Tavily search per fixed query, every 7 days
   (`H2_SCOUT_LINKEDIN_INTERVAL`), with `include_domains: ["linkedin.com"]`, `topic: general`,
   `search_depth: basic` (1 credit), `max_results: 10`, `include_usage`, no raw content, no images, no answer, and
   only new pages: `start_date` = the previous complete pass, or `time_range: month` on the first. It is built as the
   discovery collectors are ([0051](0051-tavily-discovery.md)): a skipped pass says why ("Not due yet: next pass on
   …", or the credit cap). The queries are config (`helper2.scout.linkedin.queries`): 20 of them, for
   launches of models, tools and MCP servers, company announcements, and events.
2. **The URL path decides what a page is** (code, no model). The query string is tracking and is dropped.

   | Path | Becomes | Review label |
   |---|---|---|
   | `/pulse/{slug}` | ARTICLE | LinkedIn article |
   | `/posts/{slug}`, `/feed/update/{id}`, `/company/{name}/posts/{slug}` | NEWS | LinkedIn post |
   | `/company/{name}`, `/company/{name}/about`, `/showcase/{name}` | PRODUCT | LinkedIn company page |
   | `/events/{slug}` | EVENT | LinkedIn event |

   Anything else is dropped: personal profiles (`/in/`), jobs, learning, schools, topic pages, other hosts. There is no
   score or AI check: the queries are about AI, and a doubtful page is for the admin to decide.
3. **Every kept page waits for a person.** It is a `RESOURCE_CANDIDATE` review item, as in
   [0051](0051-tavily-discovery.md) §8, with the tag `linkedin`. The batch has no hits: nothing from LinkedIn reaches
   the catalog without an admin accepting it.
4. **No author in the title.**
   - "Jane Doe on LinkedIn: text" keeps the text;
   - the site and what follows it go (" | LinkedIn", " - LinkedIn"), and so do the segments after the first " | "
     (an article's author, its comment count);
   - a post whose title names only its author ("Jane Doe's Post") takes the start of its text, cut at a word at 120
     characters;
   - an article whose title names only its author is dropped.

   The excerpt is cut at a word at 300 characters, shorter than other web excerpts (400). There is no author field.
   A post's URL and text can still name people: that is the page.
5. **Each collector proposes only its own types.** Accepting a candidate checks that its type is one a collector that
   found it declares (`SourceDescriptor.resourceTypes`, the first finder or any in `foundBy`), inside the candidate
   types (products, plus ARTICLE, NEWS and EVENT). `linkedin-scout` declares ARTICLE, NEWS, PRODUCT and EVENT; a
   discovery category its product type and ARTICLE. A candidate two categories merged keeps the most specific type
   ([0051](0051-tavily-discovery.md) §8), which only the second may declare: the check takes every finder.
6. **Event days.** A LinkedIn event page is not the event's own site, so its days are not stated days
   ([0022](0022-next-events.md) §1). An accepted LinkedIn event has no days: it is found by search and never listed
   in Next events. To list it, an admin adds the event to the curated list with the days from its own site.
7. **`linkedin.com` stays excluded from Tavily discovery** ([0051](0051-tavily-discovery.md) §4), so this collector
   is the only way LinkedIn pages come in, and no LinkedIn candidate is merged with a discovery one.
8. **Failed searches.** As in discovery: a search that fails alone is logged and skipped, the others, already paid
   for, are kept. When half or more fail, the pass fails and its checkpoint stays, so the next daily run repeats it.
9. **Credits are counted and capped with discovery's.** Every search adds its credits (Tavily's `usage.credits`, or 1)
   to the day's global credit counter and to the Scout's own counter (`ScoutTavilyCredits`,
   [0051](0051-tavily-discovery.md) §10), with the metric `helper2.live-web.credits{feature="scout-linkedin"}`. Past the
   Scout's daily cap (`H2_SCOUT_DAILY_CREDITS`, 40, shared with the discovery categories) the pass is
   SKIPPED ("Daily Tavily credit cap of the Scout reached") and tried again the next day.
10. **Retention and titles.** `linkedin-scout` is a web source (`WebSources`, [0051](0051-tavily-discovery.md)
    §12): its titles are cleaned as page titles, and its NEWS and ARTICLE records go with the web retention.
11. **Alert.** `Helper2ScoutCollectorStale` fires when `linkedin-scout` has had no success for more than 8 days (its
    7-day interval plus a day), as for `course-scout`.

## Consequences
- The review list gets LinkedIn launches and events every week. No LinkedIn page enters the catalog without a person.
- Credits: 20 searches × 1 credit = 20 a week, ≈ 86 a month. With the other Scout collectors, about 506 of the plan's
  14,000 credits a month. All 20 fall on one day: with that day's discovery searches (about 12 credits) it stays
  under the cap of 40. If discovery grows, LinkedIn's day is the first to be skipped for the cap.
- Retention ([0034](../operations/0034-data-retention.md)): accepted articles and posts are deleted 180 days after
  `retrieved_at` (`H2_RETENTION_WEB_NEWS`), like other web news; an accepted company page or event stays as long as
  it is listed; a review item stays until it is decided, then 180 days.
- The Privacy page names LinkedIn pages as a source of public data, says that titles and excerpts can name people,
  and how to object.
- Search can filter LinkedIn pages by the `linkedin` tag.

## Security and privacy (STRIDE)
- **Spoofing / tampering**: anyone can post on LinkedIn. Mitigation: every page waits for a person; on accept the URL
  is checked again, the type must be one the collector declares, the tag must be tag-shaped.
- **Information disclosure**: the queries are fixed config, with no user data. The pages are public, but they name
  people: no author field, names removed from titles, short excerpts, 180-day retention for posts and articles,
  removal on request.
- **Denial of service / cost**: 20 calls a week, a circuit breaker and retry (`collector-linkedin-scout`),
  `max_results` 10, the Scout's daily credit cap.
- **Elevation**: only an admin can accept (back office, VPN, [0035](../edge/0035-admin-host.md)); the admin page
  renders the candidate as text and links only `https://` URLs.
- **Prompt injection**: an accepted post's text reaches a model in enrichment and in the guide, as untrusted text, as
  in [0051](0051-tavily-discovery.md).
- **To confirm with a lawyer before release**: the legal basis for post authors' names (legitimate interest, GDPR
  Art. 6(1)(f) and Swiss nFADP Art. 31), the Art. 14 notice through the Privacy page, and that Tavily's written OK
  covers pages Tavily indexed from LinkedIn.

## Alternatives considered
- **LinkedIn API** — no search; the APIs manage your own pages and need partner approval.
- **Scrape LinkedIn** — forbidden by the User Agreement; LinkedIn enforces it in court.
- **A live search source** — a credit on every search that misses the stored set, and posts shown to users before
  anyone checked them.
- **Add LinkedIn to Tavily discovery** — discovery sorts by product-site rules (home page, one level below it);
  LinkedIn pages never match them, and discovery indexes list pages without review.
- **A model sorts the pages** — the path already says what a page is; a model call per result adds cost and reads
  untrusted text before anything is checked.
- **Drop events and company pages** — simpler, but events are a main reason people use LinkedIn for AI, and a
  company page is often the first trace of a new product.

## Code
- `backend/src/main/java/ai/helper2/sources/tavily/LinkedInCollector.java` — the searches, the path rules, titles and
  excerpts.
- `backend/src/main/java/ai/helper2/sources/config/CollectorProperties.java` (`LinkedIn`), `SourcesConfiguration.java`;
  the queries in `application.yaml` (`helper2.scout.linkedin`).
- `backend/src/main/java/ai/helper2/sources/tavily/ScoutTavilyCredits.java` — the credits, with the feature
  `scout-linkedin`.
- `backend/src/main/kotlin/ai/helper2/scout/usecase/IndexAcceptedCandidate.kt` — the type check per finder;
  `ResourceCandidateJson` reads the finders.
- `backend/src/main/kotlin/ai/helper2/shared/WebSources.kt` — `linkedin-scout` in `FIXED` (titles, retention).
- `deploy/monitoring/helper2-rules.yaml` — `Helper2ScoutCollectorStale`, `Helper2LiveWebCreditsHigh`.
- Tests: `LinkedInCollectorTest`, `IndexAcceptedCandidateTest`, `ResourceCandidateJsonTest`,
  `SourcesPropertiesBindingTest`, `CatalogPurgeExpiredDataIT`, the rule tests.
- Diagrams: [flow](../../diagrams/flow-0061-linkedin-via-tavily.svg) · [sequence](../../diagrams/sequence-0061-linkedin-via-tavily.svg).
