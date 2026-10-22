# Diagrams

D2 sources and their rendered SVGs. The SVG is committed, so GitHub shows it without a build step.
Each feature ADR has a flowchart (the decisions in the code) and, where there is a real call path, a sequence diagram.
Every `.d2` file names the code it describes in its first lines. When that code changes, update the diagram in the same pull request.

## Overview

| Diagram | Shows |
|---|---|
| [software-architecture](software-architecture.svg) | Software architecture of helper2: the Spring Modulith modules, their ports (pink hexagons) and the adapters that implement them, the two stores, and every external service the code calls. |
| [infrastructure](infrastructure.svg) | Runtime infrastructure of the hosted helper2 on Kubernetes: the Cloudflare tunnel, the two namespaces and their workloads, what the network policy lets each workload reach (egress), Redis auth, OpenBao and Harbor for delivery, Alloy -> Prometheus / Tempo for observability, and the prod backup with its optional off-site copy to Cloudflare R2 (off today). |

## Per ADR

| ADR | Flowchart | Sequence | Shows |
|---|---|---|---|
| [0004](../adr/0004-persistence-and-provenance.md) | [flow](flow-0004-persistence-and-provenance.svg) | [sequence](sequence-0004-persistence-and-provenance.svg) | how one search reads discovery dates and records what it saw, off the request path. |
| [0005](../adr/0005-redis-disposable-cache.md) | [flow](flow-0005-redis-disposable-cache.svg) | [sequence](sequence-0005-redis-disposable-cache.svg) | how one LIVE source is answered with the Redis cache: circuit breaker, fresh window, live call, stale window and the CACHE_UNAVAILABLE notice. |
| [0006](../adr/0006-outbound-http.md) | [flow](flow-0006-outbound-http.svg) | [sequence](sequence-0006-outbound-http.svg) | one outbound source call, from the pooled Apache client to the outcome the search reports. |
| [0007](../adr/0007-configuration-and-secrets.md) | [flow](flow-0007-configuration-and-secrets.svg) | [sequence](sequence-0007-configuration-and-secrets.svg) | how a secret travels from OpenBao to the app's environment, and how the app reads it. |
| [0008](../adr/0008-federated-search.md) | [flow](flow-0008-federated-search.svg) | [sequence](sequence-0008-federated-search.svg) | what SearchResources does with each source in one search, then merge and coverage. |
| [0009](../adr/0009-ranking.md) | [flow](flow-0009-ranking.svg) | [sequence](sequence-0009-ranking.svg) | how one query is ranked, from query analysis through the two-stage index query to the final fusion. |
| [0010](../adr/0010-filters-facets-dates-timeline.md) | [flow](flow-0010-filters-facets-dates-timeline.svg) | [sequence](sequence-0010-filters-facets-dates-timeline.svg) | from ranked results to the page the user sees: filters with Unknown, facet counts, sort, page, and the timeline view in the browser. |
| [0011](../adr/0011-language-aware-search.md) | [flow](flow-0011-language-aware-search.svg) | [sequence](sequence-0011-language-aware-search.svg) | language detection and query translation in one search. |
| [0012](../adr/0012-vector-search-pgvector.md) | [flow](flow-0012-vector-search-pgvector.svg) | [sequence](sequence-0012-vector-search-pgvector.svg) | the two loops of vector search: the indexer that keeps resource_embedding current, and the semantic source that answers one search. |
| [0013](../adr/0013-works-with-flags.md) | [flow](flow-0013-works-with-flags.svg) | [sequence](sequence-0013-works-with-flags.svg) | how Compatibility.of turns one merged result into "works with" flags. |
| [0014](../adr/0014-llm-integration.md) | [flow](flow-0014-llm-integration.svg) | [sequence](sequence-0014-llm-integration.svg) | one model call through the shared LLM layer, from the caller to a usable answer or a fallback. |
| [0015](../adr/0015-ai-guide-evidence-gate.md) | [flow](flow-0015-ai-guide-evidence-gate.svg) | [sequence](sequence-0015-ai-guide-evidence-gate.svg) | one fresh guide question in AskGuide.answer, from the request to the final status. |
| [0016](../adr/0016-guide-reason-check.md) | [flow](flow-0016-guide-reason-check.svg) | [sequence](sequence-0016-guide-reason-check.svg) | the reason check that runs after GuideValidator, and the verdict each kept pick gets. |
| [0017](../adr/0017-guide-cost-guard.md) | [flow](flow-0017-guide-cost-guard.svg) | [sequence](sequence-0017-guide-cost-guard.svg) | every guard a guide question passes before it may spend model calls, in code order. |
| [0018](../adr/0018-scout-runner.md) | [flow](flow-0018-scout-runner.svg) | [sequence](sequence-0018-scout-runner.svg) | one Scout run (RunScout.tryRunAll), from the trigger to the run summaries. |
| [0019](../adr/0019-evidence-gated-enrichment.md) | [flow](flow-0019-evidence-gated-enrichment.svg) | [sequence](sequence-0019-evidence-gated-enrichment.svg) | EnrichResource.enrich for one resource, from loading its source text to stored assertions and review items. |
| [0020](../adr/0020-scout-collectors.md) | [flow](flow-0020-scout-collectors.svg) | [sequence](sequence-0020-scout-collectors.svg) | what each Scout collector does in collect(), after RunScout has called it. |
| [0021](../adr/0021-model-families.md) | [flow](flow-0021-model-families.svg) | [sequence](sequence-0021-model-families.svg) | how curated model-family specs get into the database, and how GET /api/v1/model-families builds its answer. |
| [0022](../adr/0022-next-events.md) | [flow](flow-0022-next-events.svg) | [sequence](sequence-0022-next-events.svg) | where an event's dates come from and which events GET /api/v1/events/upcoming lists. |
| [0023](../adr/0023-identity-and-admin.md) | [flow](flow-0023-identity-and-admin.svg) | [sequence](sequence-0023-identity-and-admin.svg) | who may call what, how a sign-in becomes ADMIN, and where bookmarks are kept. |
| [0024](../adr/0024-http-edge-protection.md) | [flow](flow-0024-http-edge-protection.svg) | [sequence](sequence-0024-http-edge-protection.svg) | the checks a request meets at the HTTP edge of the app, in order. |
| [0025](../adr/0025-frontend-one-box.md) | [flow](flow-0025-frontend-one-box.svg) | [sequence](sequence-0025-frontend-one-box.svg) | the one box. |
| [0026](../adr/0026-observability.md) | [flow](flow-0026-observability.svg) | [sequence](sequence-0026-observability.svg) | where each kind of telemetry goes, and the switches that decide it. |
| [0028](../adr/0028-ci-cd.md) | [flow](flow-0028-ci-cd.svg) | [sequence](sequence-0028-ci-cd.svg) | from a pull request to prod. |
| [0029](../adr/0029-release-versioning.md) | [flow](flow-0029-release-versioning.svg) | — | the release workflow, from a pushed v* tag to the published GitHub release. |
| [0030](../adr/0030-guide-evaluation.md) | [flow](flow-0030-guide-evaluation.svg) | — | scripts/eval-guide.mjs, from the fixed query set to docs/evaluation.md. |

## Colors

Shared dark theme in [style.d2](style.d2), imported by every diagram with `...@style`:
cyan = our components, green = NVIDIA Nemotron and calls to it, violet = data stores and caches,
amber = external services, pink hexagon = port (hexagonal architecture). An animated line marks the main path.
Flowcharts use diamonds for decisions, white ovals for normal ends and pink ovals for error ends.

## Render

CI re-renders every diagram with d2 v0.7.1 and fails if a committed SVG differs.

```bash
go install oss.terrastruct.com/d2@v0.7.1
cd docs/diagrams
for f in *.d2; do [ "$f" = style.d2 ] && continue; d2 fmt "$f" && d2 "$f" "${f%.d2}.svg"; done
```
