# Testing

## Run

```bash
# backend (needs a container runtime for Testcontainers; with Podman:)
export DOCKER_HOST=unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')
export TESTCONTAINERS_RYUK_DISABLED=true
cd backend && ./gradlew test            # unit + integration (Postgres 18, Redis 8 in containers)
./gradlew liveTest                      # opt-in smoke tests against real external APIs (@Tag("live"))
# frontend (Node 24+)
cd frontend && npm test -- --run && npm run lint && npm run typecheck
```

28 Oct 2026 at `feeb02dd` (local run): backend 544 tests, 0 failed, 0 skipped; frontend 167 passed, 12 skipped, in 20 files.

## What exists

| Area | Tests | Notes |
|---|---|---|
| Shared kernel (Kotlin) | CanonicalUrlTest, ResourceIdTest, DateFactTest, LanguageDetectorTest | URL rules, UUIDv5 stability, unknown/implausible dates, language detection and its memory cost |
| Search domain (Kotlin) | ResultMergerTest, RankingTest, FilteringTest, QueryAnalyzerTest, QueryTranslationTest, SearchResourcesTest | dedup, conflicts, npm "SEE LICENSE IN …" treated as an unknown license, date semantics, facets with unknown bucket, sorting, lexicon, translation (timeout, budget, disclosure), timeouts, failures, rate-limit cooldown, cache fresh/stale/outage, observer outage, per-source latency |
| Scout (Kotlin) | EvidenceValidatorTest, RunScoutTest, ScoutRunLockIT | hallucinated excerpts, unknown evidence ids, schema violations, model dates, source conflicts, relationships, summaries; connector isolation, checkpoints, backlog, LLM unavailable; two replicas starting a run at once run it once, one scheduled run per period (ShedLock on real Postgres) |
| Connectors | GitHubSourceTest, OtherSourcesTest, McpRegistryCollectorTest, HuggingFaceTrendingCollectorTest, TavilyCollectorTest, GitHubAwesomeCollectorTest, AwesomeListParserTest, RunIntervalTest | against **recorded real responses**; 403/429 mapping, timeouts, malformed JSON, token handling, collector intervals |
| Spring AI adapters | SpringAiGuideModelTest, SpringAiEnrichmentModelTest, SpringAiRepoClassifierTest, PlainWordsTest | prompts state the limits the code enforces, candidate text and the user's need escaped, tagged and cut, provider errors vs invalid answers, truncated JSON; search syntax stripped from catalog queries |
| Persistence | SchemaMigrationTest, CuratedSeedTest, CatalogSearchRankingIT | language-aware FTS, constraints, popularity trigger, ranking on real Postgres, seed ids = domain ids |
| API (Spring + Testcontainers) | SearchApiIT, ReportAndReviewIT, RecentApiIT, SecurityIT, HttpEdgeIT, ForwardedHeadersNativeIT, ManagementPortIT, RedisDownIT | responses **validated against the OpenAPI schema**; partial results; Redis cache; recent resources; CSRF; headers; 401/403; rate limiting over real HTTP (a spoofed `X-Forwarded-For` opens no new bucket, behind the proxy only the appended address counts; an encoded path such as `/api/v1/%67uide` shares the guide budget); anonymous requests create no session, sign-in state is a Redis session; with Redis unreachable, search (cache and rate limits included) works and sign-in returns to the admin page; metrics and probes on the management port only |
| Scout end to end | ScoutPipelineIT | fixture MCP Registry → index → fake LLM → only evidenced claims published → review items → idempotent rerun; run now is 409 while another replica holds the run lock |
| AI guide (Kotlin) | GuideValidatorTest, AskGuideTest, GuideBudgetTest, RedisCallCounterTest | verbatim-quote gate, constraint checks, injected text, prompt kept only when adapted from PROMPT candidates, kept picks counted by search rank band, prompt link filter (schemes without `//`, mixed case, `%XX`/HTML-entity/fullwidth/zero-width disguises, markdown and HTML links, protocol-relative, trailing punctuation; plain "Format:"-style prompts kept), reading-type filter, answer cache (hit, expiry, LRU, degraded answers not cached), global daily model-call budget in Redis with in-memory fallback |
| AI guide end to end | GuideApiIT, GuideWithoutModelApiIT | scripted ChatModel: fast then reasoning model, invented key and quote rejected, contract-valid response, cache hit makes no model call, provider error degrades, CSRF, per-client rate limit; no model → top search results |
| Architecture | ModularityTest | Spring Modulith `verify()`; ArchUnit: domain packages are framework-free and never depend on their web/cache/config/internal adapters |
| Platform | InMemoryRateLimiterTest, RedisRateLimiterIT | limit, wait time, window reset, independent keys, IPv6 by /64; one count shared by replicas in Redis, in-memory fallback while Redis is down |
| Identity | AdminAllowListTest | GitHub sign-in through the real user service: `github:<numeric id>` grants ADMIN, `github:<login>` does not |
| Live smoke | GitHubAwesomeLiveTest | real GitHub + Nemotron classification; runs only with `./gradlew liveTest` |
| Frontend | 167 Vitest + RTL tests | pages, client, bookmarks, i18n, layout; axe smoke checks in jsdom |

## Known gaps (not covered by automated tests)

- **Browser end-to-end tests.** None yet; no tool is installed until the journeys are written (on the [roadmap](roadmap.md)). The guide journey is covered by `GuideApiIT` (API) and `HomeGuide.test.tsx` (UI).
- **Live source smoke tests.** Search connectors are tested against recorded real responses only; the one live test covers the awesome-list collector.
