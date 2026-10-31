# Architecture

Helper2 is a modular monolith: one Spring Boot application with Spring Modulith modules, hexagonal where
business rules live, a React single-page app served by the same process, PostgreSQL as the system of record
and Redis as a disposable cache. The decisions behind each part are in the [ADRs](adr/README.md), one per
feature, in the order a reader should learn them. Open work is in the [roadmap](roadmap.md).

## Software architecture

### Hexagonal: logic inside, adapters outside

![Hexagonal architecture](diagrams/hexagonal-architecture.svg)

The Kotlin domain (search, guide, scout) holds the rules and depends only on its ports, the Kotlin interfaces on its
edge. Java adapters implement those ports and talk to the outside: REST controllers on the driving side; sources,
Redis, PostgreSQL and the NVIDIA models on the driven side.

### The same system, one view at a time

| View | Shows |
|---|---|
| [Context](diagrams/architecture-context.svg) | The modules as boxes, who calls helper2, and what it calls. |
| [One search](diagrams/architecture-search.svg) | The search ports and every source, cache and translator adapter behind them. |
| [One guide question](diagrams/architecture-guide.svg) | AskGuide, the search it reuses, the evidence gate and the LLM layer's guards. |
| [How the index grows](diagrams/architecture-index.svg) | Scout, its collectors and enrichment, and how the catalog stores and embeds. |

### Everything in one diagram

![Software architecture](diagrams/software-architecture.svg)

- Module boundaries are checked by `ModularityTest` (Spring Modulith + ArchUnit): [ADR 0002](adr/foundation/0002-modular-monolith-hexagonal.md).
- The API is contract-first: controller interfaces and frontend types are generated from `api/openapi.yaml`:
  [ADR 0003](adr/foundation/0003-contract-first-rest-api.md).

## Infrastructure

Staging and production (`helper2.ai`) run on one Kubernetes cluster, one Helm release per
namespace. The chart runs on any cluster ([ADR 0027](adr/operations/0027-runtime-topology.md), [deploy/k8s/README.md](../deploy/k8s/README.md)).

![Infrastructure](diagrams/infrastructure.svg)

## Where to read next

| To understand | Read |
|---|---|
| One search, end to end | [0008 federated search](adr/search/0008-federated-search.md) → [0009 ranking](adr/search/0009-ranking.md) → [0011 language](adr/search/0011-language-aware-search.md) → [0012 vector search](adr/search/0012-vector-search-pgvector.md) |
| One guide question, end to end | [0014 LLM layer](adr/guide/0014-llm-integration.md) → [0015 evidence gate](adr/guide/0015-ai-guide-evidence-gate.md) → [0016 reason check](adr/guide/0016-guide-reason-check.md) → [0017 cost guard](adr/guide/0017-guide-cost-guard.md) |
| How the index grows | [0018 Scout runner](adr/scout/0018-scout-runner.md) → [0019 enrichment](adr/scout/0019-evidence-gated-enrichment.md) → [0020 collectors](adr/scout/0020-scout-collectors.md) |
| Configuration and secrets | [0007](adr/foundation/0007-configuration-and-secrets.md) |
| Logs, traces and meters (full meter list) | [0026 observability](adr/operations/0026-observability.md) |
| From commit to prod | [0028 CI/CD](adr/operations/0028-ci-cd.md) → [0029 release](adr/operations/0029-release-versioning.md) |

All diagrams, with a flowchart and a sequence diagram per feature: [diagrams/](diagrams/README.md).
