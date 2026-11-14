# 0030 — Hacker News as a live source

**Depends on:** [0022](0022-federated-search.md), [0025](0025-ranking.md), [0029](0029-source-query-terms.md).

## Context

The graded queries (50, 9 Nov 2026) miss official announcements. For "openai new model launch" the official post
(openai.com/index/gpt-6-astra) was in no run. Live web returns news sites, not the announcement. On Hacker News the
same post is a story with 2,279 points (9 Nov 2026).

A research pass (9 Nov 2026) compared more sources. Most are later steps or out (see Alternatives).

## Decision

1. **Hacker News** (`hacker-news`, `HackerNewsSource`). `GET https://hn.algolia.com/api/v1/search` with
   `query` = the query's content words ([0029](0029-source-query-terms.md)), `tags=story`, `hitsPerPage` = three times
   the limit (at most 50), so the AI filter still leaves enough; the first `limit` hits are kept. A query with no words
   makes no call. With a date window, `numericFilters=created_at_i>={first day, epoch seconds, UTC}` asks only for
   newer stories, so the descriptor says `filtersByDate`. A hit is the page the story links, never the discussion.
   Type NEWS, published = the story's `created_at`. No key.
2. **AI filter before ranks.** A story without a link (Ask HN) and a story whose title names no AI subject are left out
   before ranks are given, so ranks stay consecutive. `Classification.isAiRelated` knows AI words and model names
   (DeepSeek, Qwen, Gemma, Nemotron, Mixtral, NVIDIA among them), with digits right after a name ("Qwen3", "Llama3.3").
   The word list is shared with GitHub, npm and live web.
3. **Wiring.** A live source like npm: settings under `helper2.sources.hacker-news` (`H2_SOURCE_HACKER_NEWS_ENABLED`,
   timeout 2.5 s, cache 30 min), the same circuit breaker and bulkhead as the other live sources, both apps. The
   Sources page lists it from its descriptor.
4. **On by default, one switch.** `H2_SOURCE_HACKER_NEWS_ENABLED` defaults to `true`; `false` switches the source
   off. The ablation of [0046](../operations/0046-evaluation.md) is recorded with the source on; if it costs quality for
   a query group, routing leaves it out for that group.
5. **No popularity yet.** Story points would be a good tie-break, but a new `PopularityKind` changes the public API
   contract (`api/common.yaml`), the stored catalog and the result card. It is an open point.

Maven Central is not a source: Sonatype's terms list commercial use without permission as abuse; JVM libraries come from
the package catalog collector (GitHub + deps.dev, [0018](../scout/0018-package-catalog.md)).

### Code

- `backend/libs/sources/src/main/java/ai/helper2/sources/hackernews/HackerNewsSource.java`
- `backend/libs/sources/src/main/java/ai/helper2/sources/support/HttpClients.java` (`fetchSize`, `firstHits`, `plainWords`)
- `backend/libs/sources/src/main/java/ai/helper2/sources/support/Classification.java` (`AI_TERMS`)
- `backend/libs/sources/src/main/java/ai/helper2/sources/config/LiveSourcesConfiguration.java`
- `backend/libs/platform/src/main/resources/helper2-shared.yaml` (settings, resilience instances)
- Tests: `HackerNewsSourceTest`, `HackerNewsSourceLiveTest`

## Alternatives considered

- **OpenAlex** for papers with a citation sort (CC0, about $0.001 a search, free key with $1 a day): finds the
  Shazeer 2017 MoE paper, LoRA and QLoRA at the top. Open ([#255](https://github.com/silvioincalza/helper2.ai/issues/255)), after this measurement.
- **Semantic Scholar**: best paper relevance, but needs a key, and its data can be CC BY-NC. Open, with a lawyer
  check first.
- **Libraries.io, deps.dev, ecosyste.ms** as live sources: no useful free-text search, or share-alike data. Out.
- **PyPI**: no search API; a daily BigQuery snapshot through the Scout is the clean way. Separate decision.
- **Product Hunt, Ollama library, Glama**: their terms forbid this use. Out.

## Consequences

### Measurement

Not measured yet: the snapshots of 7 Nov 2026 have no hits from the source, and a replay cannot add them. The next
recording (`./gradlew evalRecord` on a fresh snapshot set, with the source switched on) includes them; then the
per-source ablation of [0046](../operations/0046-evaluation.md) gives its marginal Recall@20 and ΔnDCG@10, and the new
pooled results are graded. If the source costs quality for a query group, routing leaves it out for that group.

### Privacy and terms

- While the source is on, the visitor's search words (content words only) also go to Algolia (Hacker News
  search). The Privacy page names it, "when switched on", with the other providers that are not certified under the
  Data Privacy Framework; no IP address or account data is sent.
- Terms: the HN search API has no terms page; the Y Combinator / Hacker News terms apply. Only title, link and date are
  kept.

#### Points for a lawyer
1. Algolia as a new recipient on the Privacy page, and the basis (Art. 49(1)(b) GDPR, Art. 17(1)(b) FADP).
2. Y Combinator / Hacker News terms for showing title, link and date from the Algolia search API.

### STRIDE

- **Spoofing / tampering:** HTTPS to the host, redirects off (observed client, [0006](../foundation/0006-outbound-calls-and-resilience.md)).
  A story links any page: the link is shown as the source says, like a web result, and the merger's canonical URL
  rules apply.
- **Repudiation:** each call has a span and the `http.client.requests` meter; drops are counted (`no-link`, `not-ai`).
- **Information disclosure:** only the content words leave; no user data.
- **Denial of service:** a slow or failing source opens its circuit and is reported FAILED or TIMEOUT; the search goes
  on without it.
- **Elevation of privilege:** none: read-only public API, no key.

### AI architecture check

- Covered: Tool & API (one read-only adapter), Knowledge & RAG (more retrieval for the guide), Observability (spans,
  meters, drop counters), Cost & Reliability (no cost, circuit breaker, cache 30 min), Evaluation (measurement plan,
  recorded with the source on), Deployment (one switch, on by default).
- Not touched: Model, Agent Framework, Orchestration, Memory, Data (no schema change), Browser & Computer Use,
  Guardrails, Security & Identity (no key), Human-in-the-Loop.

### Open points

- A new snapshot set with the source on is not recorded and graded; the ablation then keeps the source or drops it.
- Hacker News points are not a popularity metric (it changes the API contract).
- The shared AI word list is not measured on a labelled set (`docs/eval/classifier-labels.tsv` has no rows).
- OpenAlex is not a live paper source; Semantic Scholar needs a lawyer check first.

## Diagrams

[flow](../../diagrams/flow-0030-hacker-news.svg) ·
[sequence](../../diagrams/sequence-0030-hacker-news.svg)

## Backlog

- Features: [F3.9](https://github.com/silvioincalza/helper2.ai/issues/55) Hacker News as a live source.
- Open issues that change this decision:
  - [#217](https://github.com/silvioincalza/helper2.ai/issues/217) S2.12.1 `Classification.isAiRelated` measured and then applied to `github-topic-leaders`
  - [#255](https://github.com/silvioincalza/helper2.ai/issues/255) S3.13.1 Papers ranked by citations
  - [#348](https://github.com/silvioincalza/helper2.ai/issues/348) S10.4.2 The first ten results of the new snapshot set graded
