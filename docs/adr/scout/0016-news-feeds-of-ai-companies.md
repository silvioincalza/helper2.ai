# 0016 — News feeds of AI companies

**Depends on:** [0006](../foundation/0006-outbound-calls-and-resilience.md), [0010](../foundation/0010-data-retention.md), [0011](0011-scout-runner.md), [0012](0012-scout-collectors-and-events.md), [0014](0014-evidence-gated-enrichment.md)

## Context
Every news record in the index comes from a web search (Tavily: `live-web-scout`, `linkedin-scout`). Those are pages
about an announcement, written by news sites, not the announcement. The News page was removed for this reason.

Most AI companies publish their own news as an RSS or Atom feed: a new model, a new API, a new product, on the day.
A feed gives the title, the link, the date and a short summary. No page has to be fetched, and no AI model is needed
to collect a post.

Eight official feeds answer (checked 9 Nov 2026): OpenAI, Google DeepMind, Google Research, Google (AI), Hugging Face,
Mistral AI, NVIDIA and AWS Machine Learning. Anthropic, Meta AI and Microsoft AI publish no feed.

## Decision
1. **One collector per feed.** The feeds are one YAML list, `helper2.feeds` in `helper2-shared.yaml`. Each
   entry has an `id`, a `publisher`, a `short-name` (at most 12 characters), the feed `url` and the `homepage`, both
   https, and optional `require-words` and `enabled`. Each feed is a `NewsFeedCollector` with the source id
   `feed-<id>`: its own cursor, run record and row on the Sources page (`NewsFeedCollectorRegistrar` in the back
   office, `ScoutSourceDescriptors.NewsFeeds` in the public app). Startup fails on an id that is not lower-case words
   with hyphens or is longer than 35 characters, a short name over 12 characters, a URL that is not https, or two feeds
   with one id (`FeedProperties`).
2. **Read once a day.** `helper2.scout.news-feeds.interval` (`H2_SCOUT_NEWS_FEEDS_INTERVAL`, default 1 day). A pass
   asks for the feed with `Accept: application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8`,
   reads at most 100 posts (and no more than the run budget) and returns a checkpoint, no cursor.
3. **RSS 2.0 and Atom, read with Jackson XML.** `FeedReader` reads both into `FeedItem` (title, link, date,
   summary) with `jackson-dataformat-xml`, the same library family as the JSON reading. The body is read as bytes: a
   charset in the HTTP `Content-Type` decodes it; without one, the XML declaration sets the encoding (a UTF-8 feed
   served as `application/xml` would otherwise be read as ISO-8859-1). DTDs and external entities are
   off in the XML input factory, so a feed cannot make the reader open a file or a URL (XXE). Jackson's tree drops
   namespace prefixes, so `<media:description>` (an image's alt text) would collide with the post's `<description>`:
   the reader reads through `PrefixedForeignNames`, which keeps the prefix of every element outside RSS and Atom. A
   summary that only repeats one of the post's categories (the Google Research feed does this) is no summary. A post needs a title and
   an http(s) link; a relative link is read against the feed URL. RSS dates are RFC 1123 or RFC 822 with a named zone,
   Atom dates ISO 8601; Atom takes `published`, else `updated`. An unreadable date is unknown. Tags are removed from
   the title and the summary, entities decoded, and the summary cut at a word before 400 characters. A body that is not
   RSS or Atom is a malformed response, and the run fails.
4. **Indexed as NEWS, without review.** A post is a NEWS hit of its feed, published = the post's date, the link and
   the canonical URL as the feed states them. The feed is the company's own channel, like the MCP Registry for MCP
   servers, so no person reviews a post first.
   After that, a post goes through the evidence-gated enrichment like every NEWS record
   ([0014](0014-evidence-gated-enrichment.md)): who it is for, its level, and ABOUT links to the model or product it
   is about, within the enrichment caps per run and per day.
5. **Required words for a feed with mixed topics.** A feed with `require-words` keeps only posts whose title or
   summary has one of them. A word matches a whole word; a word that ends with `*` matches the start of a word
   (`agent*` matches "agents" and "agentic"; `ai` does not match "aim"). Only the NVIDIA feed has them. A post left out
   is counted (`helper2.source.items.dropped{reason=off-topic}`).
6. **On by default, one switch.** `H2_SOURCE_NEWS_FEEDS_ENABLED` defaults to `true`, for every feed; `false` switches
   all of them off. One feed can be switched off with `enabled: false` in the YAML. The lawyer points below stay open
   and do not hold the feeds back.
7. **Retention.** A feed record is kept 180 days after its feed last listed it, the same period as web news
   (`WEB_NEWS`, [0010](../foundation/0010-data-retention.md)): `JdbcCatalogRetention.deleteWebNewsRetrievedBefore`
   also takes the source ids `feed-*` (`NewsFeeds.LIKE`). A feed lists its newest posts, and each pass refreshes their
   `retrieved_at`, so a post goes 180 days after it left the feed.
8. **Resilience.** All feeds share the policy `collector-news-feeds` (circuit breaker and retry of the `collector`
   base config, [0006](../foundation/0006-outbound-calls-and-resilience.md)): a transient failure is retried, a 4xx is
   not. The observed client caps a response at 2 MB (the OpenAI feed is about 0.8 MB with 1,370 posts).

### Code
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/feeds/FeedReader.java`, `FeedItem.java`,
  `NewsFeedCollector.java`
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/config/scout/NewsFeedCollectorRegistrar.java`,
  `CollectorsConfiguration.java`
- `backend/apps/public/src/main/java/ai/helper2/sources/config/scout/ScoutSourceDescriptors.java` (`NewsFeeds`)
- `backend/libs/core/src/main/java/ai/helper2/sources/config/FeedProperties.java`, `CollectorDescriptors.java`
  (`newsFeed`), `CollectorProperties.java` (`NewsFeeds`)
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/NewsFeeds.kt`
- `backend/libs/platform/src/main/java/ai/helper2/platform/http/XmlConverters.java`, `NoXmlConvertersConfiguration.java`,
  `ObservedHttp.java`
- `backend/libs/core/src/main/java/ai/helper2/catalog/internal/JdbcCatalogRetention.java`
- `backend/libs/platform/src/main/resources/helper2-shared.yaml` (feeds, settings, resilience instances)
- Tests: `FeedReaderTest`, `NewsFeedCollectorTest`, `NewsFeedCollectorRegistrarTest`, `NoXmlConvertersTest`,
  `CatalogPurgeExpiredDataIT`, `ResiliencePoliciesConfigIT`

## Alternatives considered
- **Rome 2.1.0** for RSS and Atom: a full feed model, but its last release is from March 2023 and it brings JDOM2.
- **A StAX reader of our own**: no new library, but more code to keep than a Jackson tree read.
- **Sitemaps** (crawler-commons): a sitemap gives URLs only; each page then needs a fetch and enrichment, so more cost
  and more legal questions for less value. A later step.
- **Review list for every post**: safe, but a person would have to accept the companies' own news one by one.

## Consequences
- News from the source itself, the same day, without Tavily credits and without an AI model.
- `jackson-dataformat-xml` on the class path would make Spring read and write XML over HTTP: the admin API would
  answer `application/xml` when a client asks for it, and a client would parse an XML reply. XML is for the feed reader
  only, so `XmlConverters` removes every converter for `xml` or `+xml` types from the server
  (`ServerHttpMessageConvertersCustomizer`), from Boot's clients (`ClientHttpMessageConvertersCustomizer`) and from the
  sources' client builder (`ObservedHttp.observed`), in both apps (`NoXmlConvertersConfiguration`). `FeedReader` uses
  its own `XmlMapper` and reads the body as bytes, so it needs no converter.
- The first 100 posts of a long feed are read; older posts in it are never indexed. The OpenAI feed has 1,370.
- A company without a feed is not covered. A third-party feed made by scraping (for example
  `github.com/Olshansk/rss-feeds`) is not used: the content is scraped and its licence is unclear.

### STRIDE
- **Spoofing:** HTTPS only (`FeedProperties` refuses other URLs), redirects off (observed client).
- **Tampering:** a feed is data, never instructions; the enrichment reads it as untrusted material ([0014](0014-evidence-gated-enrichment.md)). XML DTDs and external entities
  are off, so a feed cannot read local files or call other hosts.
- **Repudiation:** each call has a span and the `http.client.requests` meter; each run has its record; drops are
  counted.
- **Information disclosure:** no user data is sent; only a public URL is asked.
- **Denial of service:** the 2 MB response cap, the 100-post cap, the circuit breaker; a failing feed fails only its
  own run.
- **Elevation of privilege:** none: read-only public feeds, no key.

### AI architecture check
- Covered: Tool & API (one read-only adapter per feed), Knowledge & RAG (fresher news in the index for search and the
  guide), Data (retention in [0010](../foundation/0010-data-retention.md), no schema change), Guardrails (XXE off; no XML over HTTP; enrichment as for all NEWS), Observability (spans, run records, drop counter), Cost & Reliability (no cost, daily interval,
  shared circuit), Deployment (one switch, on by default).
- Not touched: Model, Agent Framework, Orchestration (the Scout runner as it is), Memory, Browser & Computer Use,
  Evaluation (gap: news queries are not measured on the staging catalog with the feeds on), Security &
  Identity (no key), Human-in-the-Loop (no review: official channel).

### Known limits
- News queries are not measured on the staging catalog with the feeds on.
- A company without a feed (Anthropic, Meta AI, Microsoft AI) is not covered.
- A lawyer point that fails switches its feed off (`enabled: false`).

### Privacy and terms
- No visitor data leaves: the Scout asks for a public feed from the cluster.
- The Privacy page states the retention of feed posts: "Posts from the news feeds of AI companies, with their title,
  link, date and the feed's short summary: 180 days after their feed last listed them."
- We keep the title, the link, the date and the feed's own short summary; the card links to the original post. No
  full text, no images.

#### Points for a lawyer
1. Each company's terms for showing the title, the link, the date and the feed's summary of its posts: OpenAI,
   Google (DeepMind, Research, The Keyword), Hugging Face, Mistral AI, NVIDIA, AWS.
2. Whether the summary, up to 400 characters, is a short quote that needs no licence, under Swiss and EU copyright law.

## Diagrams
[flow](../../diagrams/flow-0016-news-feeds.svg) ·
[sequence](../../diagrams/sequence-0016-news-feeds.svg)

## Backlog
- Decides: [F2.6](https://github.com/silvioincalza/helper2.ai/issues/36).
- Open issues that change it: none.
