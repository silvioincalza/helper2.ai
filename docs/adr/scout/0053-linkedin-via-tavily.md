# 0053 — LinkedIn via Tavily: public articles, posts, company pages and events, all reviewed

**Status.** Accepted. **Builds on:** [0018](0018-scout-runner.md), [0020](0020-scout-collectors.md), [0022](0022-next-events.md), [0033](../foundation/0033-resilience-policies.md), [0034](../operations/0034-data-retention.md), [0051](0051-tavily-discovery.md)

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
1. **Collector `linkedin-scout`** (`LinkedInCollector`). One Tavily search per fixed query, every 7 days
   (`H2_SCOUT_LINKEDIN_INTERVAL`), with `include_domains: ["linkedin.com"]`, `topic: general`,
   `search_depth: basic` (1 credit), `max_results: 10`, and only new pages: `start_date` = the previous complete pass,
   or `time_range: month` on the first. The queries are config (`helper2.scout.linkedin.queries`): 20 of them, for
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
5. **Each collector proposes only its own types.** Accepting a candidate checks that its type is one its collector
   declares (`SourceDescriptor.resourceTypes`), inside the candidate types (products, plus ARTICLE, NEWS and EVENT).
   `linkedin-scout` declares ARTICLE, NEWS, PRODUCT and EVENT; `tavily-discovery` its product types and ARTICLE.
6. **Event days.** A LinkedIn event page is not the event's own site, so its days are not stated days
   ([0022](0022-next-events.md) §1). An accepted LinkedIn event has no days: it is found by search and never listed
   in Next events. To list it, an admin adds the event to the curated list with the days from its own site.
7. **`linkedin.com` stays excluded from Tavily discovery** ([0051](0051-tavily-discovery.md) §4), so this collector
   is the only way LinkedIn pages come in.
8. **Failed searches.** A failed search fails the pass, and its checkpoint stays: the next daily run repeats the
   pass. That costs at most 20 credits.

## Consequences
- The review list gets LinkedIn launches and events every week. No LinkedIn page enters the catalog without a person.
- Credits: 20 searches × 1 credit × ~4.3 passes ≈ 86 credits a month. With the other Scout collectors, about 506 of
  the plan's 14,000 credits a month. Like `tavily-scout` and `course-scout`, they are not counted on the Scout's daily
  credit cap, which only the discovery categories use ([0051](0051-tavily-discovery.md) §10).
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
  `max_results` 10.
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
- `backend/src/main/kotlin/ai/helper2/scout/usecase/IndexAcceptedCandidate.kt` — the type check per collector.
- `backend/src/main/java/ai/helper2/catalog/internal/CatalogRetention.java` — `linkedin-scout` in `WEB_NEWS_SOURCES`.
- Tests: `LinkedInCollectorTest`, `IndexAcceptedCandidateTest`, `SourcesPropertiesBindingTest`.
- Diagrams: [flow](../../diagrams/flow-0053-linkedin-via-tavily.svg) · [sequence](../../diagrams/sequence-0053-linkedin-via-tavily.svg).
