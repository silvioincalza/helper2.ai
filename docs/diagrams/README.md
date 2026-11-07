# Diagrams

D2 sources and their rendered SVGs. The SVG is committed, so GitHub shows it without a build step.
Each ADR has a flowchart (the decisions in the code) and a sequence diagram (the call path).
Every `.d2` file names the code it describes in its first lines. When that code changes, update the diagram in the same pull request.

## Overview

| Diagram | Shows |
|---|---|
| [hexagonal-architecture](hexagonal-architecture.svg) | The classic hexagon: business logic (Kotlin domain) inside, ports on its edge, adapters around it, driving side left and driven side right. Hand-drawn SVG: edit the file itself. |
| [architecture-context](architecture-context.svg) | Context view: the modules as boxes (examples included), who calls helper2 and what it calls. |
| [architecture-search](architecture-search.svg) | One search: the search ports and the adapters behind them. |
| [architecture-guide](architecture-guide.svg) | One guide question: AskGuide, the search it reuses, the evidence gate, the LLM layer. |
| [architecture-index](architecture-index.svg) | How the index grows: Scout, collectors, enrichment and its gap round, catalog storage and embeddings. |
| [architecture-setup](architecture-setup.svg) | "Set it up" on a resource page: the setup ports, the registry, README and tool-guide adapters, the fetch budget and the shared cache. |
| [architecture-platform](architecture-platform.svg) | The edge and the shared services: identity, rate limits, errors, the SPA, and platform llm, http, resilience, quota, shared cache and locks, with their Redis keys and tables. |
| [data-model](data-model.svg) | Data model: every PostgreSQL table from the versioned migrations with its columns and keys, grouped by area. Solid lines are foreign keys labelled with their ON DELETE rule (ADR 0035); dashed lines are links the code keeps without one (bookmark snapshot, Tavily hit ids, guide answer key). |
| [infrastructure](infrastructure.svg) | Runtime infrastructure of the hosted helper2 on Kubernetes: Cloudflare and ingress-nginx, the public and the admin host, the two namespaces and their workloads, what the network policy lets each workload reach (egress), the migrate Job, each app's database role and Redis user, the two Secrets, OpenBao and Harbor for delivery, Alloy -> Prometheus / Tempo for observability, and the prod backup with its optional off-site copy to Cloudflare R2 (off today). |

## Per ADR

| ADR | Flowchart | Sequence | Shows |
|---|---|---|---|
| [0001](../adr/foundation/0001-stack-and-runtime.md) | [flow](flow-0001-stack-and-runtime.svg) | [sequence](sequence-0001-stack-and-runtime.svg) | one image per deployable, from the Dockerfile stages to how the web pods, the shell and the controllers answer a request, hosted and in local development. |
| [0002](../adr/foundation/0002-modular-monolith-libraries-and-apps.md) was 0002 | [flow](flow-0002-modular-monolith-hexagonal.svg) | [sequence](sequence-0002-modular-monolith-hexagonal.svg) | the dependency rules ModularityTest checks, the module dependencies that pass them, and one search through the ports. |
| [0002](../adr/foundation/0002-modular-monolith-libraries-and-apps.md) was 0068 | [flow](flow-0002-backend-libraries-and-apps.svg) | — (build only, no runtime path) | where a piece of backend code goes (an app or a library), and the build checks that keep the direction. |
| [0003](../adr/foundation/0003-contract-first-rest-api.md) | [flow](flow-0003-contract-first-rest-api.svg) | [sequence](sequence-0003-contract-first-rest-api.svg) | a contract change through the generated Java and TypeScript types to the contract tests, and one request answered with problem+json. |
| [0004](../adr/foundation/0004-persistence-schema-and-app-roles.md) was 0004 | [flow](flow-0004-persistence-and-provenance.svg) | [sequence](sequence-0004-persistence-and-provenance.svg) | how one search reads discovery dates and records what it saw, off the request path. |
| [0004](../adr/foundation/0004-persistence-schema-and-app-roles.md) was 0070 | [flow](flow-0004-app-roles.svg) | [sequence](sequence-0004-app-roles.svg) | who migrates (the migrate Job, as the owner), how an app waits for the schema, and what each app's role and Redis user reach; a deploy with a new migration from app.env to both apps serving. |
| [0005](../adr/foundation/0005-redis-disposable-cache.md) | [flow](flow-0005-redis-disposable-cache.svg) | [sequence](sequence-0005-redis-disposable-cache.svg) | how one LIVE source is answered with the Redis cache: the redis circuit, fresh window, live call under the source's policy, stale window and the CACHE_UNAVAILABLE notice; a Redis outage and its recovery. |
| [0006](../adr/foundation/0006-outbound-calls-and-resilience.md) was 0006 | [flow](flow-0006-outbound-http.svg) | [sequence](sequence-0006-outbound-http.svg) | one outbound source call, from the pooled Apache client to the outcome the search reports. |
| [0006](../adr/foundation/0006-outbound-calls-and-resilience.md) was 0033 | [flow](flow-0006-resilience-policies.svg) | [sequence](sequence-0006-resilience-policies.svg) | one Resilience.call through retry, bulkhead and circuit breaker; a collector riding out a 503 and a source circuit opening. |
| [0007](../adr/foundation/0007-configuration-secrets-and-settings.md) was 0007 | [flow](flow-0007-configuration-and-secrets.svg) | [sequence](sequence-0007-configuration-and-secrets.svg) | how a secret travels from OpenBao to the app's environment, and how the app reads it. |
| [0007](../adr/foundation/0007-configuration-secrets-and-settings.md) was 0060 | [flow](flow-0007-runtime-settings.svg) | [sequence](sequence-0007-runtime-settings.svg) | a spending limit changed from the Settings page: the checks, the append-only row and its audit, and every replica's snapshot refresh. |
| [0008](../adr/foundation/0008-llm-integration.md) | [flow](flow-0008-llm-integration.svg) | [sequence](sequence-0008-llm-integration.svg) | one model call through the shared LLM layer, from the caller to a usable answer or a fallback. |
| [0009](../adr/search/0009-federated-search.md) | [flow](flow-0009-federated-search.svg) | [sequence](sequence-0009-federated-search.svg) | what SearchResources does with each source in one search, then merge and coverage. |
| [0010](../adr/search/0010-catalog-full-text-index.md) was 0011 | [flow](flow-0010-language-aware-search.svg) | [sequence](sequence-0010-language-aware-search.svg) | language detection and query translation in one search. |
| [0010](../adr/search/0010-catalog-full-text-index.md) was 0055 | [flow](flow-0010-search-inside-awesome-lists.svg) | [sequence](sequence-0010-search-inside-awesome-lists.svg) | how an awesome list's words become tsvectors (cleaning, caps, one transaction per list) and how a search finds a tool only by them, at half weight, with "Found in". |
| [0011](../adr/search/0011-vector-search-pgvector.md) | [flow](flow-0011-vector-search-pgvector.svg) | [sequence](sequence-0011-vector-search-pgvector.svg) | the two loops of vector search: the indexer that keeps resource_embedding current, and the semantic source that answers one search. |
| [0012](../adr/search/0012-ranking.md) | [flow](flow-0012-ranking.svg) | [sequence](sequence-0012-ranking.svg) | how one query is ranked, from query analysis through the two-stage index query to the final fusion. |
| [0013](../adr/search/0013-filters-facets-and-categories.md) was 0010 | [flow](flow-0013-filters-facets-dates-timeline.svg) | [sequence](sequence-0013-filters-facets-dates-timeline.svg) | from ranked results to the page the user sees: filters with Unknown, facet counts, sort, page, and the timeline view in the browser. |
| [0013](../adr/search/0013-filters-facets-and-categories.md) was 0013 | [flow](flow-0013-works-with-flags.svg) | [sequence](sequence-0013-works-with-flags.svg) | how Compatibility.of turns one merged result into "works with" flags. |
| [0013](../adr/search/0013-filters-facets-and-categories.md) was 0053 | [flow](flow-0013-category-filter.svg) | [sequence](sequence-0013-category-filter.svg) | how a resource gets its categories by code (finder, words, tags, type) and how a search filters and counts them, and a person filtering, opening a result and asking the guide on the filtered page. |
| [0014](../adr/search/0014-tavily-store-and-daily-quota.md) | [flow](flow-0014-tavily-store-and-quota.svg) | [sequence](sequence-0014-tavily-store-and-quota.svg) | what one search does with Tavily: the stored answer first, a live call only inside the asker's daily web searches, and the same search from another visitor answered from the store. |
| [0015](../adr/search/0015-home-examples.md) | [flow](flow-0015-home-examples.svg) | [sequence](sequence-0015-home-examples.svg) | how an example gets on the home page: the rules of an admin write, the quality check (top 3 of its own search, no web), and the pick in the browser. |
| [0016](../adr/guide/0016-guide-pipeline.md) was 0015 | [flow](flow-0016-ai-guide-evidence-gate.svg) | [sequence](sequence-0016-ai-guide-evidence-gate.svg) | one fresh guide question in AskGuide.answer, from the request to the final status. |
| [0016](../adr/guide/0016-guide-pipeline.md) was 0016 | [flow](flow-0016-guide-reason-check.svg) | [sequence](sequence-0016-guide-reason-check.svg) | the reason check that runs after GuideValidator, and the verdict each kept pick gets. |
| [0016](../adr/guide/0016-guide-pipeline.md) was 0032 | [flow](flow-0016-guide-intents.svg) | [sequence](sequence-0016-guide-intents.svg) | how AskGuide reads a question and answers by intent, and the clarification round trip. |
| [0017](../adr/guide/0017-guide-cost-guard-and-answer-cache.md) was 0017 | [flow](flow-0017-guide-cost-guard.svg) | [sequence](sequence-0017-guide-cost-guard.svg) | every guard a guide question passes before it may spend model calls, in code order. |
| [0017](../adr/guide/0017-guide-cost-guard-and-answer-cache.md) was 0041 | [flow](flow-0017-guide-answer-cache.svg) | [sequence](sequence-0017-guide-answer-cache.svg) | how a guide question finds a cached answer: exact key, then a near-identical question read the same way. |
| [0018](../adr/guide/0018-ai-content-marking-and-research-trail.md) was 0038 | [flow](flow-0018-ai-content-marking.svg) | [sequence](sequence-0018-ai-content-marking.svg) | the aiContent marker of a guide answer and the data-ai-* attributes in the page. |
| [0018](../adr/guide/0018-ai-content-marking-and-research-trail.md) was 0044 | [flow](flow-0018-research-trail.svg) | [sequence](sequence-0018-research-trail.svg) | which queries and removed suggestions a guide answer lists in its research trail, and what a reused answer keeps. |
| [0019](../adr/guide/0019-setup-guide.md) | [flow](flow-0019-setup-guide.svg) | [sequence](sequence-0019-setup-guide.svg) | "Set it up" on a resource page: setup options and client snippets from source data, and the step-by-step guide checked against the documents. |
| [0020](../adr/scout/0020-scout-runner.md) | [flow](flow-0020-scout-runner.svg) | [sequence](sequence-0020-scout-runner.svg) | one Scout run (RunScout.tryRunScheduled or RunScout.tryStart), from the trigger to the run summaries. |
| [0021](../adr/scout/0021-collectors-and-events.md) was 0020 | [flow](flow-0021-scout-collectors.svg) | [sequence](sequence-0021-scout-collectors.svg) | what each Scout collector does in collect(), after RunScout has called it. |
| [0021](../adr/scout/0021-collectors-and-events.md) was 0022 | [flow](flow-0021-next-events.svg) | [sequence](sequence-0021-next-events.svg) | where an event's dates come from and which events GET /api/v1/events/upcoming lists. |
| [0021](../adr/scout/0021-collectors-and-events.md) was 0061 | [flow](flow-0021-linkedin-via-tavily.svg) | [sequence](sequence-0021-linkedin-via-tavily.svg) | how one LinkedIn result found through Tavily becomes a review candidate or nothing, and an admin accepting it. |
| [0021](../adr/scout/0021-collectors-and-events.md) was 0067 | [flow](flow-0021-live-web-events-scout.svg) | [sequence](sequence-0021-live-web-events-scout.svg) | one weekly pass of the events scout: searches, Extract, the model's reading, the code gate, the merge with a source's days, and the AI label. |
| [0022](../adr/scout/0022-discovery.md) was 0051 | [flow](flow-0022-tavily-discovery.svg) | [sequence](sequence-0022-tavily-discovery.svg) | how one category's discovery result becomes an ARTICLE, a review candidate (new or merged) or nothing, and an admin accepting a candidate. |
| [0022](../adr/scout/0022-discovery.md) was 0058 | [flow](flow-0022-awesome-deep-search.svg) | [sequence](sequence-0022-awesome-deep-search.svg) | how a pass follows nested awesome lists within its budgets, which links to product sites become review items under the daily cap, and how section headings give categories. |
| [0022](../adr/scout/0022-discovery.md) was 0063 | [flow](flow-0022-discovery-hosts.svg) | [sequence](sequence-0022-discovery-hosts.svg) | an admin adds a writing site or a directory: the host checks, the row and its audit; the next discovery run reading the lists. |
| [0023](../adr/scout/0023-evidence-gated-enrichment.md) was 0019 | [flow](flow-0023-evidence-gated-enrichment.svg) | [sequence](sequence-0023-evidence-gated-enrichment.svg) | EnrichResource.enrich for one resource, from loading its source text to stored assertions and review items. |
| [0023](../adr/scout/0023-evidence-gated-enrichment.md) was 0043 | [flow](flow-0023-enrichment-gap-round.svg) | [sequence](sequence-0023-enrichment-gap-round.svg) | the gap round inside EnrichResource.attempt: when it reads a README or model card, and which run is saved. |
| [0023](../adr/scout/0023-evidence-gated-enrichment.md) was 0056 | [flow](flow-0023-evidence-ladder.svg) | [sequence](sequence-0023-evidence-ladder.svg) | the evidence ladder inside EnrichResource.attempt: when it reads the README and the product's own pages, the URL checks and budgets, and which run is saved. |
| [0023](../adr/scout/0023-evidence-gated-enrichment.md) was 0062 | [flow](flow-0023-writing-about-a-resource.svg) | [sequence](sequence-0023-writing-about-a-resource.svg) | what an enrichment keeps for an article and how it names what the article is about; a discovery post becoming an ARTICLE candidate. |
| [0024](../adr/edge/0024-identity-and-admin-host.md) was 0023 | [flow](flow-0024-identity-and-admin.svg) | [sequence](sequence-0024-identity-and-admin.svg) | who may call what, how a sign-in becomes ADMIN, the bookmark use cases, account deletion (DELETE /me) and the admin audit trail. |
| [0024](../adr/edge/0024-identity-and-admin-host.md) was 0035 | [flow](flow-0024-admin-host.svg) | [sequence](sequence-0024-admin-host.svg) | which app and host answer the back office, and how a sign-in gets ADMIN only on the admin port. |
| [0025](../adr/edge/0025-http-edge-protection.md) | [flow](flow-0025-http-edge-protection.svg) | [sequence](sequence-0025-http-edge-protection.svg) | the checks a request meets at the HTTP edge of the app, in order. |
| [0026](../adr/edge/0026-back-office-and-review.md) was 0045 | [flow](flow-0026-back-office.svg) | [sequence](sequence-0026-back-office.svg) | the back office overview: one computation a minute, counts from PostgreSQL and Redis, a count followed into the filtered queue, a review item's evidence read through its link, and a bulk decision item by item; the read-only Settings page from each module's typed records. |
| [0026](../adr/edge/0026-back-office-and-review.md) was 0064 | [flow](flow-0026-relationship-target-in-review.svg) | [sequence](sequence-0026-relationship-target-in-review.svg) | how an admin points a relationship candidate at a resource, adds it, or renames it, and what a refusal leaves. |
| [0026](../adr/edge/0026-back-office-and-review.md) was 0065 | [flow](flow-0026-candidate-edit-in-review.svg) | [sequence](sequence-0026-candidate-edit-in-review.svg) | how an admin edits a resource candidate, what a later finder does with the edit, and accepting it. |
| [0026](../adr/edge/0026-back-office-and-review.md) was 0066 | [flow](flow-0026-curated-field.svg) | [sequence](sequence-0026-curated-field.svg) | how an admin sets or drops a field of a resource, and what later records, runs and the page do with it. |
| [0027](../adr/frontend/0027-frontend-one-box.md) was 0025 | [flow](flow-0027-frontend-one-box.svg) | [sequence](sequence-0027-frontend-one-box.svg) | the one box. |
| [0027](../adr/frontend/0027-frontend-one-box.md) was 0039 | [flow](flow-0027-frontend-recent-answers-cache.svg) | [sequence](sequence-0027-frontend-recent-answers-cache.svg) | when useApi shows a recent answer and when it asks the server, and a back move without a request. |
| [0028](../adr/frontend/0028-design-system.md) was 0040 | [flow](flow-0028-frontend-design.svg) | [sequence](sequence-0028-frontend-design.svg) | the results page: the list at once, the reserved picks bar, badges in place with no re-order, and the selected result in the URL. |
| [0028](../adr/frontend/0028-design-system.md) was 0042 | [flow](flow-0028-redesign.svg) | [sequence](sequence-0028-redesign.svg) | the redesign: Home with the radar, the pinned box, the two tables or the phone tabs, the folding title on phones; then a search, and the guide on its own for a question. |
| [0029](../adr/frontend/0029-site-pages-and-policy.md) was 0050 | [flow](flow-0029-pages-for-crawlers.svg) | [sequence](sequence-0029-pages-for-crawlers.svg) | what Spring answers for each client route (status, robots, head), and a crawler and a person opening a resource page. |
| [0029](../adr/frontend/0029-site-pages-and-policy.md) was 0052 | [flow](flow-0029-policy-endpoint.svg) | [sequence](sequence-0029-policy-endpoint.svg) | where each number of GET /api/v1/policy comes from, and a text page reading them. |
| [0030](../adr/frontend/0030-monetisation.md) was 0057 | [flow](flow-0030-monetisation.svg) | [sequence](sequence-0030-monetisation.svg) | what a public page loads for analytics and the ad slot, and a visitor who accepts and searches twice. |
| [0030](../adr/frontend/0030-monetisation.md) was 0059 | [flow](flow-0030-adsense.svg) | [sequence](sequence-0030-adsense.svg) | what a public page loads for AdSense and Google's consent tool, and Google's site check followed by a visit to a resource page. |
| [0031](../adr/operations/0031-observability.md) | [flow](flow-0031-observability.svg) | [sequence](sequence-0031-observability.svg) | where each kind of telemetry goes, the switches that decide it, the Grafana dashboard and the alert rules. |
| [0032](../adr/operations/0032-runtime-topology.md) was 0027 | [flow](flow-0032-runtime-topology.svg) | [sequence](sequence-0032-runtime-topology.svg) | what one Helm render decides from its values, and the runtime paths: a request through Cloudflare and ingress-nginx, probes and scrape on 8081, the nightly backup. |
| [0032](../adr/operations/0032-runtime-topology.md) was 0069 | [flow](flow-0032-three-deployables.svg) | [sequence](sequence-0032-three-deployables.svg) | which workload answers a request (the web files by path) and which app runs a job, what a change rebuilds, the public pod's shell from the web image; a public page, an admin write and a Scout run end to end. |
| [0033](../adr/operations/0033-ci-cd-and-supply-chain.md) was 0028 | [flow](flow-0033-ci-cd.svg) | [sequence](sequence-0033-ci-cd.svg) | from a pull request to prod. |
| [0033](../adr/operations/0033-ci-cd-and-supply-chain.md) was 0046 | [flow](flow-0033-supply-chain.svg) | — (CI only, no runtime path) | the SBOM and the Grype scan on main, on a release and every week. |
| [0033](../adr/operations/0033-ci-cd-and-supply-chain.md) was 0071 | [flow](flow-0033-ci-cd-per-component-and-image-signing.svg) | [sequence](sequence-0033-ci-cd-per-component-and-image-signing.svg) | which CI jobs and image builds a change runs, an unchanged image tagged on its digest, signing with the OpenBao Transit key, the check before a deploy; a web-only push from build to the staging deploy by digest. |
| [0034](../adr/operations/0034-releases-and-versions.md) was 0029 | [flow](flow-0034-release-versioning.svg) | [sequence](sequence-0034-release-versioning.svg) | the release workflow, from a pushed v* tag to the published GitHub release. |
| [0034](../adr/operations/0034-releases-and-versions.md) was 0048 | [flow](flow-0034-release-branches-and-version-tags.svg) | [sequence](sequence-0034-release-branches-and-version-tags.svg) | which push builds an image and with which tag, and a fix released from a release branch to prod under its version. |
| [0035](../adr/operations/0035-data-retention.md) | [flow](flow-0035-data-retention.svg) | [sequence](sequence-0035-data-retention.svg) | the daily purges per module under their cluster locks, and the catalog rules in order. |
| [0036](../adr/quality/0036-evaluation.md) was 0030 | [flow](flow-0036-guide-evaluation.svg) | [sequence](sequence-0036-guide-evaluation.svg) | scripts/eval-guide.mjs, from the fixed query set to docs/evaluation.md. |
| [0036](../adr/quality/0036-evaluation.md) was 0036 | [flow](flow-0036-search-evaluation-harness.svg) | [sequence](sequence-0036-search-evaluation-harness.svg) | the search evaluation harness: snapshots recorded once, offline replay, report and the nDCG@10 gate. |
| [0037](../adr/quality/0037-code-format-and-dead-code-audit.md) was 0047 | [flow](flow-0037-code-format.svg) | — (build and CI only, no runtime path) | which files Spotless and Prettier format with which formatter, and the spotlessCheck and format:check gates in CI. |
| [0037](../adr/quality/0037-code-format-and-dead-code-audit.md) was 0054 | [flow](flow-0037-dead-code-and-dependency-audit.svg) | — (build and CI only, no runtime path) | the two knip runs (whole project, then production code and its dependencies) and the npm audit gate in the frontend CI job. |

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
