# Architecture Decision Records

One ADR per feature, in the order a reader should learn the system. Each record builds on the earlier ones,
so reading 0001 to 0030 in order builds the whole system up step by step. Every record is Accepted (27 Oct 2026) and describes the code
on this branch. Open items are not kept in the records: each one links to [the roadmap](../roadmap.md).

Overview diagrams: [software architecture](../diagrams/software-architecture.svg) · [infrastructure](../diagrams/infrastructure.svg).
All diagrams: [docs/diagrams](../diagrams/README.md).

## Foundation

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0001](0001-stack-and-runtime.md) | Stack and runtime: Java 25 + Kotlin, Spring Boot 4.1, Spring AI 2.0, React SPA served by Spring, JVM image | — | — |
| [0002](0002-modular-monolith-hexagonal.md) | Modular monolith with Spring Modulith; hexagonal where business rules live; Kotlin domain, Java adapters | 0001 | — |
| [0003](0003-contract-first-rest-api.md) | Contract-first REST with OpenAPI 3.1; generated server interfaces and TypeScript types; problem+json | 0001, 0002 | — |
| [0004](0004-persistence-and-provenance.md) | PostgreSQL as system of record; ids from canonical URLs; source records; dates never invented; one schema migration + repeatable seeds; catalog writes off the request path | 0001, 0002 | [flow](../diagrams/flow-0004-persistence-and-provenance.svg) · [sequence](../diagrams/sequence-0004-persistence-and-provenance.svg) |
| [0005](0005-redis-disposable-cache.md) | Redis as a disposable cache: source responses, fresh/stale windows, circuit breaker, counters, guide answers | 0002, 0004 | [flow](../diagrams/flow-0005-redis-disposable-cache.svg) · [sequence](../diagrams/sequence-0005-redis-disposable-cache.svg) |
| [0006](0006-outbound-http.md) | Outbound HTTP: Apache HttpClient 5 behind RestClient; timeouts; no HTTPS→HTTP; error mapping | 0002, 0005 | [flow](../diagrams/flow-0006-outbound-http.svg) · [sequence](../diagrams/sequence-0006-outbound-http.svg) |
| [0007](0007-configuration-and-secrets.md) | Configuration and secrets: every external variable uses the H2_ prefix; secrets only in OpenBao (helper2/kv ci/<env> app_env); profiles | 0001 | [flow](../diagrams/flow-0007-configuration-and-secrets.svg) · [sequence](../diagrams/sequence-0007-configuration-and-secrets.svg) |

## Search

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0008](0008-federated-search.md) | Federated search over disclosed sources: modes, parallel fan-out, deadlines, cooldown, partial results, source status, merge and conflicts | 0002, 0004, 0005, 0006 | [flow](../diagrams/flow-0008-federated-search.svg) · [sequence](../diagrams/sequence-0008-federated-search.svg) |
| [0009](0009-ranking.md) | Ranking: every query word first, synonyms, two-stage index query, reciprocal-rank fusion, bounded popularity | 0004, 0008 | [flow](../diagrams/flow-0009-ranking.svg) · [sequence](../diagrams/sequence-0009-ranking.svg) |
| [0010](0010-filters-facets-dates-timeline.md) | Filters, facets with Unknown, sort, stated dates, list and timeline view | 0004, 0008 | [flow](../diagrams/flow-0010-filters-facets-dates-timeline.svg) · [sequence](../diagrams/sequence-0010-filters-facets-dates-timeline.svg) |
| [0011](0011-language-aware-search.md) | Language-aware search: detected stemmer, query translation in parallel with sources | 0008, 0009 | [flow](../diagrams/flow-0011-language-aware-search.svg) · [sequence](../diagrams/sequence-0011-language-aware-search.svg) |
| [0012](0012-vector-search-pgvector.md) | Vector search: pgvector + NVIDIA embeddings, indexer, fused by rank | 0004, 0008, 0009 | [flow](../diagrams/flow-0012-vector-search-pgvector.svg) · [sequence](../diagrams/sequence-0012-vector-search-pgvector.svg) |
| [0013](0013-works-with-flags.md) | "Works with" flags derived from current source tags | 0004, 0008 | [flow](../diagrams/flow-0013-works-with-flags.svg) · [sequence](../diagrams/sequence-0013-works-with-flags.svg) |

## AI guide

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0014](0014-llm-integration.md) | LLM integration: Spring AI behind ports, one OpenAI-compatible provider, prompts on the classpath, thinking off, timeouts, budget per attempt, degraded mode | 0001, 0002, 0005, 0007 | [flow](../diagrams/flow-0014-llm-integration.svg) · [sequence](../diagrams/sequence-0014-llm-integration.svg) |
| [0015](0015-ai-guide-evidence-gate.md) | AI guide: plan → retrieve → choose → deterministic evidence gate; streamed over SSE | 0008, 0009, 0012, 0014 | [flow](../diagrams/flow-0015-ai-guide-evidence-gate.svg) · [sequence](../diagrams/sequence-0015-ai-guide-evidence-gate.svg) |
| [0016](0016-guide-reason-check.md) | Guide reason check: a second model call checks each reason; `checked` event | 0014, 0015 | [flow](../diagrams/flow-0016-guide-reason-check.svg) · [sequence](../diagrams/sequence-0016-guide-reason-check.svg) |
| [0017](0017-guide-cost-guard.md) | Guide cost guard: per-minute limit, per-client daily quota, global daily cap, answer cache with config hash, single-flight, concurrency limit and deadline | 0005, 0014, 0015, 0016 | [flow](../diagrams/flow-0017-guide-cost-guard.svg) · [sequence](../diagrams/sequence-0017-guide-cost-guard.svg) |
| [0031](0031-setup-guide.md) | Setup guide: "Guide me" from source data (MCP Registry, README config), one snippet per AI client, checked README steps and questions | 0002, 0004, 0015 | — |

## Scout and catalog features

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0018](0018-scout-runner.md) | AI Resource Scout runner: schedule, intervals, cursors, backlog, failure isolation, cluster-wide run lock | 0004, 0006, 0014 | [flow](../diagrams/flow-0018-scout-runner.svg) · [sequence](../diagrams/sequence-0018-scout-runner.svg) |
| [0019](0019-evidence-gated-enrichment.md) | Evidence-gated enrichment and review queue (value must appear in the excerpt; UNCHANGED skip; daily cap) | 0004, 0014, 0018 | [flow](../diagrams/flow-0019-evidence-gated-enrichment.svg) · [sequence](../diagrams/sequence-0019-evidence-gated-enrichment.svg) |
| [0020](0020-scout-collectors.md) | Scout collectors: MCP Registry, HF trending, HF provider orgs, Tavily discovery, awesome lists with classifier, ai-deadlines | 0006, 0014, 0018 | [flow](../diagrams/flow-0020-scout-collectors.svg) · [sequence](../diagrams/sequence-0020-scout-collectors.svg) |
| [0021](0021-model-families.md) | Compare AI models: curated model families with cited specs | 0003, 0004, 0020 | [flow](../diagrams/flow-0021-model-families.svg) · [sequence](../diagrams/sequence-0021-model-families.svg) |
| [0022](0022-next-events.md) | Next events: stated event days, ai-deadlines and curated industry events | 0004, 0010, 0018, 0020 | [flow](../diagrams/flow-0022-next-events.svg) · [sequence](../diagrams/sequence-0022-next-events.svg) |

## Edge, frontend and operations

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0023](0023-identity-and-admin.md) | Identity: anonymous-first; GitHub sign-in only for /admin; ADMIN by numeric id; bookmarks | 0003, 0004, 0005, 0007 | [flow](../diagrams/flow-0023-identity-and-admin.svg) · [sequence](../diagrams/sequence-0023-identity-and-admin.svg) |
| [0024](0024-http-edge-protection.md) | HTTP edge: rate limits (IPv6 /64), CSRF, CSP, forwarded headers, management port, SPA fallback and caching | 0001, 0005, 0007, 0023 | [flow](../diagrams/flow-0024-http-edge-protection.svg) · [sequence](../diagrams/sequence-0024-http-edge-protection.svg) |
| [0025](0025-frontend-one-box.md) | Frontend: one page, one box, Radar design system, guide progress, accessibility, theme (system/light/dark) | 0001, 0003, 0008, 0015 | [flow](../diagrams/flow-0025-frontend-one-box.svg) · [sequence](../diagrams/sequence-0025-frontend-one-box.svg) |
| [0026](0026-observability.md) | Observability: structured logs, OpenTelemetry traces, Prometheus meters, Grafana dashboard | 0001, 0008, 0014 | [flow](../diagrams/flow-0026-observability.svg) · [sequence](../diagrams/sequence-0026-observability.svg) |
| [0027](0027-runtime-topology.md) | Runtime topology: Helm chart, three replicas on prod, Cloudflare tunnel, per-workload network policies, ServiceAccount without token, Redis auth, backups | 0001, 0004, 0005, 0007, 0018, 0023, 0024 | — |
| [0028](0028-ci-cd.md) | CI/CD: GitHub Actions, PR checks, OpenBao OIDC, staging then approved prod | 0007, 0027 | [flow](../diagrams/flow-0028-ci-cd.svg) · [sequence](../diagrams/sequence-0028-ci-cd.svg) |
| [0029](0029-release-versioning.md) | Release and versioning: semantic versions, immutable tags, release workflow | 0001, 0028 | [flow](../diagrams/flow-0029-release-versioning.svg) |

## Quality

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0030](0030-guide-evaluation.md) | Guide evaluation: fixed question set, script, published results | 0015, 0016, 0017 | [flow](../diagrams/flow-0030-guide-evaluation.svg) |

## Writing a new ADR

- Take the next free number. One feature or one decision per record.
- Sections: Context, Decision, Consequences, Alternatives considered, Code, Diagrams, Roadmap.
- A decision that replaces an older one updates the older record's status to "Superseded by NNNN" in the same pull request.
- Open work goes to [the roadmap](../roadmap.md), not into the record.
