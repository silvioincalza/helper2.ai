# Architecture Decision Records

One ADR per feature, grouped by topic in the order a reader should learn the system. Reading the tables top to
bottom builds the whole system up step by step. The numbers 0021 and 0035 are not used. Every record is Accepted and
describes the code on this branch. Open items are not kept in the records: each one links to [the roadmap](../roadmap.md).

Overview diagrams: [software architecture](../diagrams/software-architecture.svg) · [infrastructure](../diagrams/infrastructure.svg).
All diagrams: [docs/diagrams](../diagrams/README.md).

## Foundation

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0001](0001-stack-and-runtime.md) | Stack and runtime: Java 25 + Kotlin, Spring Boot 4.1, Spring AI 2.0, React SPA served by Spring, JVM image | — | [flow](../diagrams/flow-0001-stack-and-runtime.svg) · [sequence](../diagrams/sequence-0001-stack-and-runtime.svg) |
| [0002](0002-modular-monolith-hexagonal.md) | Modular monolith with Spring Modulith; hexagonal where business rules live; Kotlin domain, Java adapters | 0001 | [flow](../diagrams/flow-0002-modular-monolith-hexagonal.svg) · [sequence](../diagrams/sequence-0002-modular-monolith-hexagonal.svg) |
| [0003](0003-contract-first-rest-api.md) | Contract-first REST with OpenAPI 3.1; generated server interfaces and TypeScript types; problem+json | 0001, 0002 | [flow](../diagrams/flow-0003-contract-first-rest-api.svg) · [sequence](../diagrams/sequence-0003-contract-first-rest-api.svg) |
| [0004](0004-persistence-and-provenance.md) | PostgreSQL as system of record; ids from canonical URLs; source records; dates never invented; one schema migration + repeatable seeds; catalog writes off the request path; the catalog owns its tables | 0001, 0002 | [flow](../diagrams/flow-0004-persistence-and-provenance.svg) · [sequence](../diagrams/sequence-0004-persistence-and-provenance.svg) |
| [0005](0005-redis-disposable-cache.md) | Redis as a disposable cache: source responses, fresh/stale windows, circuit breaker, counters, guide answers | 0002, 0004, 0033 | [flow](../diagrams/flow-0005-redis-disposable-cache.svg) · [sequence](../diagrams/sequence-0005-redis-disposable-cache.svg) |
| [0006](0006-outbound-http.md) | Outbound HTTP: Apache HttpClient 5 behind RestClient; timeouts; safe redirects; bounded bodies; error mapping | 0002, 0005, 0033 | [flow](../diagrams/flow-0006-outbound-http.svg) · [sequence](../diagrams/sequence-0006-outbound-http.svg) |
| [0007](0007-configuration-and-secrets.md) | Configuration and secrets: every external variable uses the H2_ prefix; secrets only in OpenBao (helper2/kv ci/<env> app_env); profiles | 0001 | [flow](../diagrams/flow-0007-configuration-and-secrets.svg) · [sequence](../diagrams/sequence-0007-configuration-and-secrets.svg) |
| [0033](0033-resilience-policies.md) | Resilience policies (Resilience4j): circuit breakers, bulkheads and retries by name | 0002, 0005, 0006, 0008, 0026 | [flow](../diagrams/flow-0033-resilience-policies.svg) · [sequence](../diagrams/sequence-0033-resilience-policies.svg) |

## Search

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0008](0008-federated-search.md) | Federated search over disclosed sources: modes, parallel fan-out, deadlines, cooldown, circuit breakers, partial results, source status, merge and conflicts | 0002, 0004, 0005, 0006, 0033 | [flow](../diagrams/flow-0008-federated-search.svg) · [sequence](../diagrams/sequence-0008-federated-search.svg) |
| [0009](0009-ranking.md) | Ranking: every query word first, synonyms, two-stage index query, reciprocal-rank fusion, bounded popularity | 0004, 0008 | [flow](../diagrams/flow-0009-ranking.svg) · [sequence](../diagrams/sequence-0009-ranking.svg) |
| [0010](0010-filters-facets-dates-timeline.md) | Filters, facets with Unknown, sort, stated dates, list and timeline view | 0004, 0008 | [flow](../diagrams/flow-0010-filters-facets-dates-timeline.svg) · [sequence](../diagrams/sequence-0010-filters-facets-dates-timeline.svg) |
| [0011](0011-language-aware-search.md) | Language-aware search: detected stemmer, query translation in parallel with sources | 0008, 0009 | [flow](../diagrams/flow-0011-language-aware-search.svg) · [sequence](../diagrams/sequence-0011-language-aware-search.svg) |
| [0012](0012-vector-search-pgvector.md) | Vector search: pgvector + NVIDIA embeddings, indexer, fused by rank | 0004, 0008, 0009 | [flow](../diagrams/flow-0012-vector-search-pgvector.svg) · [sequence](../diagrams/sequence-0012-vector-search-pgvector.svg) |
| [0013](0013-works-with-flags.md) | "Works with" flags derived from current source tags | 0004, 0008 | [flow](../diagrams/flow-0013-works-with-flags.svg) · [sequence](../diagrams/sequence-0013-works-with-flags.svg) |
| [0037](0037-tavily-store-and-daily-quota.md) | Tavily: store first, per-feature daily quota | 0005, 0008, 0017, 0023, 0033, 0034 | [flow](../diagrams/flow-0037-tavily-store-and-quota.svg) · [sequence](../diagrams/sequence-0037-tavily-store-and-quota.svg) |

## AI guide

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0014](0014-llm-integration.md) | LLM integration: Spring AI behind ports, one OpenAI-compatible provider, prompts on the classpath, thinking off, timeouts, budget per attempt, degraded mode | 0001, 0002, 0005, 0007 | [flow](../diagrams/flow-0014-llm-integration.svg) · [sequence](../diagrams/sequence-0014-llm-integration.svg) |
| [0015](0015-ai-guide-evidence-gate.md) | AI guide: plan → retrieve → choose → deterministic evidence gate; streamed over SSE | 0008, 0009, 0012, 0014 | [flow](../diagrams/flow-0015-ai-guide-evidence-gate.svg) · [sequence](../diagrams/sequence-0015-ai-guide-evidence-gate.svg) |
| [0016](0016-guide-reason-check.md) | Guide reason check: a second model call checks each reason; `checked` event | 0014, 0015 | [flow](../diagrams/flow-0016-guide-reason-check.svg) · [sequence](../diagrams/sequence-0016-guide-reason-check.svg) |
| [0017](0017-guide-cost-guard.md) | Guide cost guard: per-minute limit, per-client daily quota, global daily cap, answer cache with config hash, single-flight, concurrency limit and deadline | 0005, 0014, 0015, 0016 | [flow](../diagrams/flow-0017-guide-cost-guard.svg) · [sequence](../diagrams/sequence-0017-guide-cost-guard.svg) |
| [0031](0031-setup-guide.md) | Setup guide: "Guide me" from source data, one snippet per AI client, checked steps from the README | 0002, 0004, 0015 | [flow](../diagrams/flow-0031-setup-guide.svg) · [sequence](../diagrams/sequence-0031-setup-guide.svg) |
| [0032](0032-guide-intents.md) | Guide intents: understand the question, ask back when vague, retrieve and answer by intent | 0014, 0015, 0016, 0017, 0030 | [flow](../diagrams/flow-0032-guide-intents.svg) · [sequence](../diagrams/sequence-0032-guide-intents.svg) |
| [0038](0038-ai-content-marking.md) | Marking AI-written text: labels on screen, metadata in the API and the page | 0003, 0014, 0015, 0019 | [flow](../diagrams/flow-0038-ai-content-marking.svg) · [sequence](../diagrams/sequence-0038-ai-content-marking.svg) |

## Scout and catalog features

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0018](0018-scout-runner.md) | AI Resource Scout runner: schedule, intervals, cursors, backlog, failure isolation, cluster-wide run lock | 0004, 0006, 0014 | [flow](../diagrams/flow-0018-scout-runner.svg) · [sequence](../diagrams/sequence-0018-scout-runner.svg) |
| [0019](0019-evidence-gated-enrichment.md) | Evidence-gated enrichment and review queue (value must appear in the excerpt; UNCHANGED skip; daily cap) | 0004, 0014, 0018 | [flow](../diagrams/flow-0019-evidence-gated-enrichment.svg) · [sequence](../diagrams/sequence-0019-evidence-gated-enrichment.svg) |
| [0020](0020-scout-collectors.md) | Scout collectors: MCP Registry, HF trending, HF provider orgs, Tavily discovery, awesome lists with classifier, ai-deadlines | 0006, 0014, 0018 | [flow](../diagrams/flow-0020-scout-collectors.svg) · [sequence](../diagrams/sequence-0020-scout-collectors.svg) |
| [0022](0022-next-events.md) | Next events: stated event days, ai-deadlines and curated industry and community events | 0004, 0010, 0018, 0020 | [flow](../diagrams/flow-0022-next-events.svg) · [sequence](../diagrams/sequence-0022-next-events.svg) |

## Edge, frontend and operations

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0023](0023-identity-and-admin.md) | Identity: anonymous-first; GitHub sign-in; ADMIN by numeric id; bookmarks and account deletion for signed-in users; admin audit trail | 0003, 0004, 0005, 0007, 0034 | [flow](../diagrams/flow-0023-identity-and-admin.svg) · [sequence](../diagrams/sequence-0023-identity-and-admin.svg) |
| [0024](0024-http-edge-protection.md) | HTTP edge: rate limits (IPv6 /64), CSRF, CSP, forwarded headers, management port, SPA fallback and caching | 0001, 0005, 0007, 0023, 0033 | [flow](../diagrams/flow-0024-http-edge-protection.svg) · [sequence](../diagrams/sequence-0024-http-edge-protection.svg) |
| [0025](0025-frontend-one-box.md) | Frontend: one page, one box, Radar design system, guide progress, accessibility, theme (system/light/dark) | 0001, 0003, 0008, 0015 | [flow](../diagrams/flow-0025-frontend-one-box.svg) · [sequence](../diagrams/sequence-0025-frontend-one-box.svg) |
| [0039](0039-frontend-recent-answers-cache.md) | Frontend recent-answers cache: 50 answers for 5 minutes in `useApi`, back and forward without a request, scroll restored | 0025 | [flow](../diagrams/flow-0039-frontend-recent-answers-cache.svg) · [sequence](../diagrams/sequence-0039-frontend-recent-answers-cache.svg) |
| [0026](0026-observability.md) | Observability: structured logs, OpenTelemetry traces, Prometheus meters, Grafana dashboard, alert rules | 0001, 0008, 0014, 0033 | [flow](../diagrams/flow-0026-observability.svg) · [sequence](../diagrams/sequence-0026-observability.svg) |
| [0027](0027-runtime-topology.md) | Runtime topology: Helm chart, three replicas on prod, Cloudflare tunnel, per-workload network policies, Redis auth, backups | 0001, 0004, 0005, 0007, 0018, 0023, 0024 | [flow](../diagrams/flow-0027-runtime-topology.svg) · [sequence](../diagrams/sequence-0027-runtime-topology.svg) |
| [0028](0028-ci-cd.md) | CI/CD: GitHub Actions, PR checks, OpenBao OIDC, staging then approved prod | 0007, 0027 | [flow](../diagrams/flow-0028-ci-cd.svg) · [sequence](../diagrams/sequence-0028-ci-cd.svg) |
| [0029](0029-release-versioning.md) | Release and versioning: semantic versions, immutable tags, release workflow | 0001, 0028 | [flow](../diagrams/flow-0029-release-versioning.svg) · [sequence](../diagrams/sequence-0029-release-versioning.svg) |
| [0034](0034-data-retention.md) | Data retention: a period and a mechanism for every store; daily purges per module | 0004, 0005, 0023, 0026 | [flow](../diagrams/flow-0034-data-retention.svg) · [sequence](../diagrams/sequence-0034-data-retention.svg) |

## Quality

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0030](0030-guide-evaluation.md) | Guide evaluation: fixed question set, script, published results | 0015, 0016, 0017 | [flow](../diagrams/flow-0030-guide-evaluation.svg) · [sequence](../diagrams/sequence-0030-guide-evaluation.svg) |
| [0036](0036-search-evaluation-harness.md) | Search evaluation harness: frozen snapshots, pooled graded judgements, offline replay | 0008, 0009, 0012, 0030 | [flow](../diagrams/flow-0036-search-evaluation-harness.svg) · [sequence](../diagrams/sequence-0036-search-evaluation-harness.svg) |

## Writing a new ADR

- Take the next free number. One feature or one decision per record.
- Sections: Context, Decision, Consequences, Alternatives considered, Code, Diagrams, Roadmap.
- A decision that replaces an older one updates the older record's status to "Superseded by NNNN" in the same pull request.
- Open work goes to [the roadmap](../roadmap.md), not into the record.
