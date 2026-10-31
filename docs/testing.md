# Testing

## Run

```bash
# backend (needs a container runtime for Testcontainers; with Podman:)
export DOCKER_HOST=unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')
export TESTCONTAINERS_RYUK_DISABLED=true
cd backend && ./gradlew test            # unit + integration (Postgres 18, Redis 8 in containers)
./gradlew liveTest                      # opt-in smoke tests against real external APIs (@Tag("live"))
./gradlew evalRecord                    # opt-in: one live search per evaluation query, saved to backend/eval-data (ADR 0036)
./gradlew evalTest                      # offline replay and metrics over the recorded snapshots; skips without them
# alert rules (promtool from the Prometheus release)
promtool check rules deploy/monitoring/helper2-rules.yaml
promtool test rules deploy/monitoring/tests/*.yaml
# frontend (Node 24+)
cd frontend && npm test -- --run && npm run lint && npm run typecheck
```

31 Oct 2026 (local run): backend 877 tests, 0 failed, 1 skipped by design; frontend 303 passed, 7 skipped,
in 33 files. The 17 alert rules have promtool unit tests; CI runs them in the `monitoring` job.
`evalTest` and `evalRecord` are not part of `test`: see [docs/eval](eval/README.md).
The guide evaluation (`scripts/eval-guide.mjs`, default and adversarial sets) runs every night against staging in
[`guide-eval.yml`](../.github/workflows/guide-eval.yml) ([ADR 0030](adr/quality/0030-guide-evaluation.md)).

## What exists

| Area | Tests | Notes |
|---|---|---|
| Shared kernel (Kotlin) | CanonicalUrlTest, ResourceIdTest, DateFactTest, LanguageDetectorTest | URL rules, UUIDv5 stability, unknown/implausible dates, language detection and its memory cost |
| Search domain (Kotlin) | ResultMergerTest, RankingTest, FilteringTest, QueryAnalyzerTest, QueryTranslationTest, SearchResourcesTest, IntentClassTest, WebSearchQuotaTest, MeteredSourceTest, SourceHealthTest, ListSourcesTest | dedup, conflicts, npm "SEE LICENSE IN …" treated as an unknown license, date semantics, facets with unknown bucket, sorting, lexicon, translation (timeout, budget, disclosure), timeouts, failures, rate-limit cooldown, cache fresh/stale/outage, observer outage, per-source latency; Tavily store first, a live call only inside the asker's daily web searches; Sources page numbers: median response time of the last 20 calls, circuit of enabled sources, credits of the day on the metered source |
| Scout (Kotlin) | EvidenceValidatorTest, RunScoutTest, StartScoutRunTest, EnrichResourceTest, ScoutRunLockIT, JdbcEnrichmentStoreIT | hallucinated excerpts, unknown evidence ids, schema violations, model dates, source conflicts, relationships, summaries; connector isolation, checkpoints, backlog, LLM unavailable; an admin start recorded as STARTED, or BUSY when the run lock is taken; enrichment store on real Postgres; two replicas starting a run at once run it once, one scheduled run per period (ShedLock on real Postgres) |
| Connectors | GitHubSourceTest, HuggingFaceSourcesTest, NpmSourceTest, ArxivSourceTest, TokenRedirectTest, CollectorPropertiesTest, TransientSourceFailureTest, SourceCircuitIT, CollectorRetryIT, ClassificationLabelsTest, McpRegistryCollectorTest, HuggingFaceTrendingCollectorTest, TavilyCollectorTest, GitHubAwesomeCollectorTest, AwesomeListParserTest, RunIntervalTest | against **recorded real responses**; 403/429 mapping, timeouts, malformed JSON, token handling (no token sent after a redirect), collector intervals; each collector's settings validated at startup; a provider that is down opens its circuit, a collector rides out a 503 |
| Spring AI adapters | SpringAiGuideModelTest, SpringAiQueryTranslatorTest, SpringAiEnrichmentModelTest, SpringAiRepoClassifierTest, NvidiaEmbedderTest, ModelErrorsTest, PlainWordsTest | prompts state the limits the code enforces, candidate text and the user's need escaped, tagged and cut, provider errors vs invalid answers, truncated JSON; search syntax stripped from catalog queries; an open `llm-chat` or `llm-embed` circuit fails a call at once without reaching the provider (guide: no budget unit spent; translator: the query stays untranslated; embedder: the embedding fails), and only provider failures count for the circuit |
| Persistence | SchemaMigrationTest, CuratedSeedTest, CatalogSearchRankingIT, JdbcWebResultStoreIT | language-aware FTS, constraints, popularity trigger, ranking on real Postgres, seed ids = domain ids, stored Tavily answers |
| Data retention | CatalogPurgeExpiredDataIT, ScoutPurgeExpiredDataIT, IdentityPurgeExpiredDataIT | each module's daily purge on real Postgres: what goes after its period, what stays |
| API (Spring + Testcontainers) | SearchApiIT, ReportAndReviewIT, ReviewItemsApiIT, RecentApiIT, SecurityIT, AccountApiIT, AdminAuditApiIT, WebSearchQuotaApiIT, HttpEdgeIT, ForwardedHeadersNativeIT, ManagementPortIT, RedisDownIT | responses **validated against the OpenAPI schema**; partial results; Redis cache; recent resources; CSRF; headers; 401/403; rate limiting over real HTTP (a spoofed `X-Forwarded-For` opens no new bucket, behind the proxy only the appended address counts; an encoded path such as `/api/v1/%67uide` shares the guide budget); anonymous requests create no session, sign-in state is a Redis session; bookmarks and DELETE /me for a signed-in user; admin actions in the audit trail; web searches left per day; GET /sources with response time, circuits and the credits of the day, no internals; with Redis unreachable, search (cache and rate limits included) works and sign-in returns to the admin page; metrics and probes on the management port only |
| Scout end to end | ScoutPipelineIT | fixture MCP Registry → index → fake LLM → only evidenced claims published → review items → idempotent rerun; run now is 409 while another replica holds the run lock |
| AI guide (Kotlin) | GuideValidatorTest, AskGuideTest, AskGuideIntentsTest, AskGuideWebSearchTest, DailyBudgetTest, RedisCallCounterTest | verbatim-quote gate, constraint checks, injected text, prompt kept only when adapted from PROMPT candidates, kept picks counted by search rank band, prompt link filter (schemes without `//`, mixed case, `%XX`/HTML-entity/fullwidth/zero-width disguises, markdown and HTML links, protocol-relative, trailing punctuation; plain "Format:"-style prompts kept), reading-type filter, answer cache (hit, expiry, LRU, degraded answers not cached), comparison and explanation limits from one `AnswerLimits`, each step its own span tagged with the step and the intent, compared subjects searched at the same time, global daily model-call budget in Redis with in-memory fallback |
| AI guide end to end | GuideApiIT, GuideWithoutModelApiIT, GuideLimitsTest, GuideFirstUsefulTest | scripted ChatModel: fast then reasoning model, invented key and quote rejected, contract-valid response, cache hit makes no model call, provider error degrades, CSRF, per-client rate limit; no model → top search results; both guide endpoints share the guide slots, a full guide answers busy at once, a cancelled question gives its slot back; the stream times its first `retrieved` or `clarify` event once, with the intent, and a stream that ends before one is not timed |
| Architecture | ModularityTest | Spring Modulith `verify()`; ArchUnit: domain packages are framework-free and never depend on their web/cache/config/internal adapters |
| Platform | InMemoryRateLimiterTest, RedisRateLimiterIT, Resilience4jPoliciesTest, ResiliencePoliciesConfigIT, RedisOutboundThrottleIT, ObservedHttpRetryTest, QuotaKeysTest, RedisQuotaCounterIT, NvidiaOnlyTest | limit, wait time, window reset, independent keys, IPv6 by /64; one count shared by replicas in Redis, in-memory fallback while Redis is down; retry, bulkhead and circuit breaker by policy name, circuit state and opening time, every configured policy created at startup; daily quota keys without address or query text; startup stops on a configured model that is not an NVIDIA model |
| Identity | AdminAllowListTest, FailSoftSessionRepositoryTest | GitHub sign-in through the real user service: `github:<numeric id>` grants ADMIN, `github:<login>` does not; session reads fail soft while Redis is down |
| Setup guide | RunOptionRulesTest, SetupGuidesTest, SnippetParseBackTest, HttpToolMaterialsTest, RedisToolGuideCacheIT, ToolGuideApiIT | only safe run commands (package runners, one Docker image, no shells or inline code), snippets for the eight listed clients, every client snippet parses back to the same values, READMEs read with the setup guide's own GitHub token or anonymously, guide answers cached in Redis without document text, the guide endpoint takes no question |
| Search evaluation | RelevanceMetricsTest, SearchEvaluationTest, CutoffSweepTest, SnapshotTakerTest | nDCG@10, reciprocal rank and recall on known cases; the vector cutoff sweep; offline replay of recorded snapshots and the nDCG@10 gate (`evalTest`) |
| Live smoke | GitHubSourceLiveTest, HuggingFaceLiveTest, NpmSourceLiveTest, ArxivSourceLiveTest, TavilyLiveTest, McpRegistryCollectorLiveTest, AiDeadlinesCollectorLiveTest, GitHubAwesomeLiveTest | one per adapter, against the real API (the awesome-list collector with Nemotron classification); a test whose key is missing is skipped; runs only with `./gradlew liveTest`, once a week in the `live` workflow |
| Frontend | 303 Vitest + RTL tests (AccountPage.test.tsx and others) | pages, client, bookmarks, account deletion, i18n, layout; axe smoke checks in jsdom |
| Alert rules | deploy/monitoring/tests | promtool unit tests: each of the 17 rules fires on its condition and stays quiet otherwise |

## Known gaps (not covered by automated tests)

- **Browser end-to-end tests.** None yet; no tool is installed until the journeys are written (on the [roadmap](roadmap.md)). The guide journey is covered by `GuideApiIT` (API) and `HomeGuide.test.tsx` (UI).
- **Live source checks from production.** `liveTest` runs from a GitHub runner, not from the cluster's egress (on the [roadmap](roadmap.md)).
