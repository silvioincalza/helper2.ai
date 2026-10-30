# Diagrams

D2 sources and their rendered SVGs. The SVG is committed, so GitHub shows it without a build step.
Each ADR has a flowchart (the decisions in the code) and a sequence diagram (the call path).
Every `.d2` file names the code it describes in its first lines. When that code changes, update the diagram in the same pull request.

## Overview

| Diagram | Shows |
|---|---|
| [hexagonal-architecture](hexagonal-architecture.svg) | The classic hexagon: business logic (Kotlin domain) inside, ports on its edge, adapters around it, driving side left and driven side right. Hand-drawn SVG: edit the file itself. |
| [architecture-context](architecture-context.svg) | Context view: the modules as boxes, who calls helper2 and what it calls. |
| [architecture-search](architecture-search.svg) | One search: the search ports and the adapters behind them. |
| [architecture-guide](architecture-guide.svg) | One guide question: AskGuide, the search it reuses, the evidence gate, the LLM layer. |
| [architecture-index](architecture-index.svg) | How the index grows: Scout, collectors, enrichment, catalog storage and embeddings. |
| [software-architecture](software-architecture.svg) | Software architecture of helper2: the Spring Modulith modules, their ports (pink hexagons) and the adapters that implement them, the two stores, and every external service the code calls. |
| [infrastructure](infrastructure.svg) | Runtime infrastructure of the hosted helper2 on Kubernetes: the Cloudflare tunnel, the two namespaces and their workloads, what the network policy lets each workload reach (egress), Redis auth, OpenBao and Harbor for delivery, Alloy -> Prometheus / Tempo for observability, and the prod backup with its optional off-site copy to Cloudflare R2 (off today). |

## Per ADR

| ADR | Flowchart | Sequence | Shows |
|---|---|---|---|
| [0001](../adr/0001-stack-and-runtime.md) | [flow](flow-0001-stack-and-runtime.svg) | [sequence](sequence-0001-stack-and-runtime.svg) | the one image, from the three Dockerfile stages to how SinglePageApp and the controllers answer a request, hosted and in local development. |
| [0002](../adr/0002-modular-monolith-hexagonal.md) | [flow](flow-0002-modular-monolith-hexagonal.svg) | [sequence](sequence-0002-modular-monolith-hexagonal.svg) | the dependency rules ModularityTest checks, the module dependencies that pass them, and one search through the ports. |
| [0003](../adr/0003-contract-first-rest-api.md) | [flow](flow-0003-contract-first-rest-api.svg) | [sequence](sequence-0003-contract-first-rest-api.svg) | a contract change through the generated Java and TypeScript types to the contract tests, and one request answered with problem+json. |
| [0004](../adr/0004-persistence-and-provenance.md) | [flow](flow-0004-persistence-and-provenance.svg) | [sequence](sequence-0004-persistence-and-provenance.svg) | how one search reads discovery dates and records what it saw, off the request path. |
| [0005](../adr/0005-redis-disposable-cache.md) | [flow](flow-0005-redis-disposable-cache.svg) | [sequence](sequence-0005-redis-disposable-cache.svg) | how one LIVE source is answered with the Redis cache: the redis circuit, fresh window, live call under the source's policy, stale window and the CACHE_UNAVAILABLE notice; a Redis outage and its recovery. |
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
| [0018](../adr/0018-scout-runner.md) | [flow](flow-0018-scout-runner.svg) | [sequence](sequence-0018-scout-runner.svg) | one Scout run (RunScout.tryRunScheduled or RunScout.tryStart), from the trigger to the run summaries. |
| [0019](../adr/0019-evidence-gated-enrichment.md) | [flow](flow-0019-evidence-gated-enrichment.svg) | [sequence](sequence-0019-evidence-gated-enrichment.svg) | EnrichResource.enrich for one resource, from loading its source text to stored assertions and review items. |
| [0020](../adr/0020-scout-collectors.md) | [flow](flow-0020-scout-collectors.svg) | [sequence](sequence-0020-scout-collectors.svg) | what each Scout collector does in collect(), after RunScout has called it. |
| [0022](../adr/0022-next-events.md) | [flow](flow-0022-next-events.svg) | [sequence](sequence-0022-next-events.svg) | where an event's dates come from, which events GET /api/v1/events/upcoming lists, and which of them the home Events board shows. |
| [0023](../adr/0023-identity-and-admin.md) | [flow](flow-0023-identity-and-admin.svg) | [sequence](sequence-0023-identity-and-admin.svg) | who may call what, how a sign-in becomes ADMIN, the bookmark use cases, account deletion (DELETE /me) and the admin audit trail. |
| [0024](../adr/0024-http-edge-protection.md) | [flow](flow-0024-http-edge-protection.svg) | [sequence](sequence-0024-http-edge-protection.svg) | the checks a request meets at the HTTP edge of the app, in order. |
| [0025](../adr/0025-frontend-one-box.md) | [flow](flow-0025-frontend-one-box.svg) | [sequence](sequence-0025-frontend-one-box.svg) | the one box. |
| [0026](../adr/0026-observability.md) | [flow](flow-0026-observability.svg) | [sequence](sequence-0026-observability.svg) | where each kind of telemetry goes, the switches that decide it, the Grafana dashboard and the alert rules. |
| [0027](../adr/0027-runtime-topology.md) | [flow](flow-0027-runtime-topology.svg) | [sequence](sequence-0027-runtime-topology.svg) | what one Helm render decides from its values, and the runtime paths: a request through the tunnel, probes and scrape on 8081, the nightly backup. |
| [0028](../adr/0028-ci-cd.md) | [flow](flow-0028-ci-cd.svg) | [sequence](sequence-0028-ci-cd.svg) | from a pull request to prod. |
| [0029](../adr/0029-release-versioning.md) | [flow](flow-0029-release-versioning.svg) | [sequence](sequence-0029-release-versioning.svg) | the release workflow, from a pushed v* tag to the published GitHub release. |
| [0030](../adr/0030-guide-evaluation.md) | [flow](flow-0030-guide-evaluation.svg) | [sequence](sequence-0030-guide-evaluation.svg) | scripts/eval-guide.mjs, from the fixed query set to docs/evaluation.md. |
| [0031](../adr/0031-setup-guide.md) | [flow](flow-0031-setup-guide.svg) | [sequence](sequence-0031-setup-guide.svg) | "Guide me" on a resource page: setup options and client snippets from source data, and the step-by-step guide checked against the documents. |
| [0032](../adr/0032-guide-intents.md) | [flow](flow-0032-guide-intents.svg) | [sequence](sequence-0032-guide-intents.svg) | how AskGuide reads a question and answers by intent, and the clarification round trip. |
| [0033](../adr/0033-resilience-policies.md) | [flow](flow-0033-resilience-policies.svg) | [sequence](sequence-0033-resilience-policies.svg) | one Resilience.call through retry, bulkhead and circuit breaker; a collector riding out a 503 and a source circuit opening. |
| [0034](../adr/0034-data-retention.md) | [flow](flow-0034-data-retention.svg) | [sequence](sequence-0034-data-retention.svg) | the daily purges per module under their cluster locks, and the catalog rules in order. |
| [0036](../adr/0036-search-evaluation-harness.md) | [flow](flow-0036-search-evaluation-harness.svg) | [sequence](sequence-0036-search-evaluation-harness.svg) | the search evaluation harness: snapshots recorded once, offline replay, report and the nDCG@10 gate. |
| [0037](../adr/0037-tavily-store-and-daily-quota.md) | [flow](flow-0037-tavily-store-and-quota.svg) | [sequence](sequence-0037-tavily-store-and-quota.svg) | what one search does with Tavily: the stored answer first, a live call only inside the asker's daily web searches, and the same search from another visitor answered from the store. |
| [0038](../adr/0038-ai-content-marking.md) | [flow](flow-0038-ai-content-marking.svg) | [sequence](sequence-0038-ai-content-marking.svg) | the aiContent marker of a guide answer and the data-ai-* attributes in the page. |
| [0039](../adr/0039-frontend-recent-answers-cache.md) | [flow](flow-0039-frontend-recent-answers-cache.svg) | [sequence](sequence-0039-frontend-recent-answers-cache.svg) | when useApi shows a recent answer and when it asks the server, and a back move without a request. |
| [0040](../adr/0040-frontend-design-v2.md) | [flow](flow-0040-frontend-design-v2.svg) | [sequence](sequence-0040-frontend-design-v2.svg) | when a home board moves and holds still; the results page: the list at once, the reserved picks bar, badges in place with no re-order, and the selected result in the URL. |

## Colors

Shared light theme in [style.d2](style.d2), imported by every diagram with `...@style`. It uses the website's
light colours (`frontend/src/styles/tokens.css`): warm paper background, indigo ink, one saffron accent.
Indigo on paper = our components, green = NVIDIA Nemotron, lavender = data stores and caches,
amber = external services, saffron hexagon = port (hexagonal architecture).
Animated lines: saffron for the main path, green for calls to the model.
Flowcharts use saffron diamonds for decisions, paper ovals for normal ends and red ovals for error ends.

## Render

CI re-renders every diagram with d2 v0.9.0 and fails if a committed SVG differs.
Another d2 version draws different SVGs, so render only with v0.9.0:

```bash
brew install d2   # macOS, when Homebrew has v0.9.0; `d2 --version` must print v0.9.0
# or, anywhere with Go 1.27+ (d2 moved to github.com/d2lang/d2; the version must be stamped):
go install -ldflags "-X github.com/d2lang/d2/lib/version.Version=v0.9.0" github.com/d2lang/d2@v0.9.0
docs/diagrams/render.sh
```

`render.sh` stops if `d2` is another version. CI runs the same script.
