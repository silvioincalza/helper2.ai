# Architecture

Helper2 is a modular monolith in two Spring Boot apps, the public app and the back office, over shared libraries
with Spring Modulith modules, hexagonal where business rules live. A React single-page app is served from its own
web image. PostgreSQL is the system of record and Redis a disposable cache. The decisions behind each part are in the [ADRs](adr/README.md), one per
feature, in the order a reader should learn them. Open work is in the
[backlog](https://github.com/silvioincalza/helper2.ai/issues): each ADR lists its open issues in its Backlog section.

## Software architecture

### Hexagonal: logic inside, adapters outside

![Hexagonal architecture](diagrams/hexagonal-architecture.svg)

The Kotlin domain (search, guide, setup, scout and the shared kernel) holds the rules and depends only on its ports,
the Kotlin interfaces on its edge. Java adapters implement those ports and talk to the outside: REST controllers and
schedules of both apps on the driving side; the public web, the NVIDIA Nemotron models, Redis and PostgreSQL on the
driven side.

### The whole system on one page

![The whole system](diagrams/architecture-full.svg)

People on the left, the browser and the edge, then the cluster with the two apps, their modules, the shared libraries
and the stores, and on the right what helper2 calls outside. Each group names the view that holds its detail.

### The same system, one view at a time

The views follow the C4 model: context, containers, components, then deployment.

**C4 level 1: system context**

| View | Shows |
|---|---|
| [System context](diagrams/architecture-system-context.svg) | helper2 as one box, the people who use it and the systems it talks to. |

**C4 level 2: containers**

| View | Shows |
|---|---|
| [Containers](diagrams/architecture-containers.svg) | What is deployed and runs on its own: the two web apps, the three deployables, the migrate Job, the embedder and the two stores. |

**C4 level 3: components**

| View | Shows |
|---|---|
| [Modules](diagrams/architecture-context.svg) | The modules as boxes, who calls helper2, and what it calls. |
| [Hexagonal layers](diagrams/hexagonal-architecture.svg) | The domain inside, its ports on the edge, the adapters around it. |
| [One search](diagrams/architecture-search.svg) | The search ports and every source, cache and translator adapter behind them. |
| [One guide question](diagrams/architecture-guide.svg) | AskGuide, the search it reuses, the evidence gate and the LLM layer's guards. |
| [How the index grows](diagrams/architecture-index.svg) | Scout, its collectors, enrichment and its gap round (README, model card), and how the catalog stores and embeds. |
| [Set it up](diagrams/architecture-setup.svg) | The setup module on a resource page: run options from the registry and the README, the tool guide, its budgets and caches. |
| [Review research](diagrams/architecture-review-research.svg) | The research module in the back office: its ports, the agent loop, the read-only tools and the live steps. |
| [Edge and shared services](diagrams/architecture-platform.svg) | Identity, rate limits, errors and the SPA, and the platform services every module uses, with their Redis keys and tables. |
| [Frontend](diagrams/architecture-frontend.svg) | The two web apps built from `frontend/src`: features and the ones they compose, the shared layers, the generated API client, and who may import whom. |

**Deployment**

| View | Shows |
|---|---|
| [Deployment](diagrams/infrastructure.svg) | The hosted helper2 on Kubernetes, the overview: how a request gets in, the applications, their stores and what they call outside. Four views hold the detail: [workloads](diagrams/infrastructure-workloads.svg), [secrets](diagrams/infrastructure-secrets.svg), [delivery](diagrams/infrastructure-delivery.svg), [backups and monitoring](diagrams/infrastructure-data.svg). |

**Domain and data**

| View | Shows |
|---|---|
| [Context map](diagrams/context-map.svg) | The 10 bounded contexts of the backlog, the modules that hold each one, and the direction of each dependency. |
| [Event storming](diagrams/event-storming.svg) | The big picture of the seven contexts the apps run: who does what, which events follow, which policies react, and the open stories. |
| [Data model](diagrams/data-model.svg) | Every PostgreSQL table with its columns, keys and delete rules, grouped by area. |

- Module boundaries are checked by `ModularityTest` (Spring Modulith + ArchUnit): [ADR 0002](adr/foundation/0002-modules-modular-monolith-hexagonal-where-the-rules-live.md).
- The API is contract-first: controller interfaces and frontend types are generated from `api/public.yaml`, `api/admin.yaml` and `api/common.yaml`;
  the shared part once, in the library `libs/core`: [ADR 0003](adr/foundation/0003-contract-first-rest.md).

### Context map

![Context map](diagrams/context-map.svg)

An arrow from A to B says that A uses B. Every context uses Foundation: its modules (`shared`, `platform`, `api`)
are open. The other arrows are the module dependencies that pass `ApplicationModules.verify()`; a module is reached
only through its base package or a named interface (`search.usecase`, `catalog.usecase`, `scout.usecase`,
`examples.usecase`, the search cards). Three contexts have no module of their own: Money and consent lives in
`site.monetisation`, Legal and compliance is the text pages over `GET /policy`, and Release and operations is the
chart and the workflows that deploy the three deployables.

### Event storming

![Big picture event storming](diagrams/event-storming.svg)

The big picture of what happens in the public app, the web site and the back office, one lane per context. Read a lane
from left to right: an actor (small yellow) runs a command (blue), a system or aggregate answers, a domain event
(orange) happens, and a policy (lilac) reacts. An arrow means "triggers". The other outcomes of a step sit under it.
The magenta notes are open backlog stories. Foundation, release and legal work are left out: they are not behaviour
of the apps. The colours are Brandolini's. Update it when a flow of the apps changes.

## Infrastructure

Staging and production (`helper2.ai`) run on one Kubernetes cluster, one Helm release per
namespace. The chart runs on any cluster ([ADR 0043](adr/operations/0043-runtime-topology-helm-chart-three-deployables.md), [deploy/k8s/README.md](../deploy/k8s/README.md)).

![Infrastructure](diagrams/infrastructure.svg)

The overview leaves out ports, secrets and policies. Each of these views answers one question:

- [Workloads](diagrams/infrastructure-workloads.svg): what runs in the namespace and what each workload may reach.
- [Secrets](diagrams/infrastructure-secrets.svg): who holds which password.
- [Delivery](diagrams/infrastructure-delivery.svg): how an image gets to the cluster, and the canary of a release.
- [Backups and monitoring](diagrams/infrastructure-data.svg): the dumps and where metrics and traces go.

## Where to read next

| To understand | Read |
|---|---|
| One search, end to end | [0022 federated search](adr/search/0022-federated-search.md) → [0025 ranking](adr/search/0025-ranking.md) → [0023 language](adr/search/0023-catalog-full-text-index.md) → [0024 vector search](adr/search/0024-vector-search.md) |
| One guide question, end to end | [0008 LLM layer](adr/foundation/0008-llm-integration.md) → [0031 evidence gate and reason check](adr/guide/0031-guide-pipeline.md) → [0032 cost guard](adr/guide/0032-guide-cost-guard-and-answer-cache.md) |
| How the index grows | [0011 Scout runner](adr/scout/0011-scout-runner.md) → [0014 enrichment](adr/scout/0014-evidence-gated-enrichment.md) → [0012 collectors](adr/scout/0012-scout-collectors-and-events.md) → [0021 resource identity](adr/scout/0021-resource-identity-one-resource-per-repository.md) |
| Configuration and secrets | [0007](adr/foundation/0007-configuration-secrets-runtime-settings.md) |
| Logs, traces and meters (full meter list) | [0009 observability](adr/foundation/0009-observability.md) |
| From commit to prod | [0044 CI/CD](adr/operations/0044-ci-cd-and-supply-chain.md) → [0045 release](adr/operations/0045-releases-and-versions.md) |

All diagrams, with a flowchart and a sequence diagram per feature: [diagrams/](diagrams/README.md).
