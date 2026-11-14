# Architecture Decision Records

One record per decision, numbered in the order the system is built. Each record states the decision in force and has
the same sections: Context, Decision, Alternatives considered, Consequences, Diagrams, Backlog.

Folders, one per context: `foundation`, `scout`, `search`, `guide`, `identity`, `backoffice`, `frontend`, `money`,
`operations`.

| # | Title | Folder | Features | Depends on |
|---|---|---|---|---|
| [0001](foundation/0001-stack-and-runtime.md) | Stack and runtime | [`foundation`](foundation/) | [F1.1](https://github.com/silvioincalza/helper2.ai/issues/20) | — |
| [0002](foundation/0002-modules-modular-monolith-hexagonal-where-the-rules-live.md) | Modules: modular monolith, hexagonal where the rules live | [`foundation`](foundation/) | [F1.1](https://github.com/silvioincalza/helper2.ai/issues/20) | 0001, 0003, 0043, 0047 |
| [0003](foundation/0003-contract-first-rest.md) | Contract-first REST | [`foundation`](foundation/) | [F1.2](https://github.com/silvioincalza/helper2.ai/issues/21) | 0001, 0002 |
| [0004](foundation/0004-persistence-and-the-schema.md) | Persistence and the schema | [`foundation`](foundation/) | [F1.3](https://github.com/silvioincalza/helper2.ai/issues/22) | 0001, 0002, 0005, 0007, 0036, 0043 |
| [0005](foundation/0005-redis-as-a-disposable-cache.md) | Redis as a disposable cache | [`foundation`](foundation/) | [F1.4](https://github.com/silvioincalza/helper2.ai/issues/23) | 0002, 0004, 0006 |
| [0006](foundation/0006-outbound-calls-and-resilience.md) | Outbound calls and resilience | [`foundation`](foundation/) | [F1.5](https://github.com/silvioincalza/helper2.ai/issues/24) | 0002, 0005, 0008, 0009, 0022, 0032 |
| [0007](foundation/0007-configuration-secrets-runtime-settings.md) | Configuration, secrets, runtime settings | [`foundation`](foundation/) | [F1.6](https://github.com/silvioincalza/helper2.ai/issues/25), [F6.4](https://github.com/silvioincalza/helper2.ai/issues/84) | 0001, 0004, 0005, 0010, 0014, 0027, 0032, 0036, 0038 |
| [0008](foundation/0008-llm-integration.md) | LLM integration | [`foundation`](foundation/) | [F1.7](https://github.com/silvioincalza/helper2.ai/issues/26) | 0001, 0002, 0005, 0007 |
| [0009](foundation/0009-observability.md) | Observability | [`foundation`](foundation/) | [F1.8](https://github.com/silvioincalza/helper2.ai/issues/27) | 0001, 0006, 0008, 0022 |
| [0010](foundation/0010-data-retention.md) | Data retention | [`foundation`](foundation/) | [F1.9](https://github.com/silvioincalza/helper2.ai/issues/28) | 0004, 0005, 0009, 0036 |
| [0011](scout/0011-scout-runner.md) | Scout runner | [`scout`](scout/) | [F2.1](https://github.com/silvioincalza/helper2.ai/issues/31) | 0004, 0006, 0008 |
| [0012](scout/0012-scout-collectors-and-events.md) | Scout collectors and events | [`scout`](scout/) | [F2.2](https://github.com/silvioincalza/helper2.ai/issues/32) | 0004, 0006, 0008, 0010, 0011, 0013, 0014, 0026, 0027, 0033 |
| [0013](scout/0013-discovery.md) | Discovery | [`scout`](scout/) | [F2.3](https://github.com/silvioincalza/helper2.ai/issues/33) | 0006, 0007, 0009, 0010, 0011, 0012, 0014, 0023, 0026, 0027, 0033, 0036, 0040 |
| [0014](scout/0014-evidence-gated-enrichment.md) | Evidence-gated enrichment | [`scout`](scout/) | [F2.4](https://github.com/silvioincalza/helper2.ai/issues/34) | 0004, 0006, 0008, 0010, 0011, 0012, 0013, 0027, 0033 |
| [0015](scout/0015-reference-projects.md) | Reference projects | [`scout`](scout/) | [F2.5](https://github.com/silvioincalza/helper2.ai/issues/35) | 0006, 0011, 0012, 0013, 0046 |
| [0016](scout/0016-news-feeds-of-ai-companies.md) | News feeds of AI companies | [`scout`](scout/) | [F2.6](https://github.com/silvioincalza/helper2.ai/issues/36) | 0006, 0010, 0011, 0012, 0014 |
| [0017](scout/0017-pypi-through-bigquery.md) | PyPI through BigQuery | [`scout`](scout/) | [F2.7](https://github.com/silvioincalza/helper2.ai/issues/37) | 0006, 0011, 0012, 0015 |
| [0018](scout/0018-ai-libraries-and-sdks-per-language.md) | AI libraries and SDKs per language | [`scout`](scout/) | [F2.8](https://github.com/silvioincalza/helper2.ai/issues/38) | 0004, 0006, 0011, 0013, 0015, 0023, 0032, 0038, 0046 |
| [0019](scout/0019-known-targets-vendors-and-services.md) | Known targets: vendors and services | [`scout`](scout/) | [F2.9](https://github.com/silvioincalza/helper2.ai/issues/39) | 0004, 0011, 0014, 0015, 0033, 0038 |
| [0020](scout/0020-resource-vitality.md) | Resource vitality | [`scout`](scout/) | [F2.11](https://github.com/silvioincalza/helper2.ai/issues/41) | 0012, 0022, 0025 |
| [0021](scout/0021-resource-identity-one-resource-per-repository.md) | Resource identity: one resource per repository | [`scout`](scout/) | [F2.10](https://github.com/silvioincalza/helper2.ai/issues/40) | 0004, 0022 |
| [0022](search/0022-federated-search.md) | Federated search | [`search`](search/) | [F3.1](https://github.com/silvioincalza/helper2.ai/issues/47), [F3.10](https://github.com/silvioincalza/helper2.ai/issues/56) | 0002, 0004, 0005, 0006 |
| [0023](search/0023-catalog-full-text-index.md) | Catalog full-text index | [`search`](search/) | [F3.2](https://github.com/silvioincalza/helper2.ai/issues/48) | 0004, 0008, 0010, 0011, 0012, 0022, 0025, 0040 |
| [0024](search/0024-vector-search.md) | Vector search | [`search`](search/) | [F3.3](https://github.com/silvioincalza/helper2.ai/issues/49) | 0004, 0022, 0025 |
| [0025](search/0025-ranking.md) | Ranking | [`search`](search/) | [F3.4](https://github.com/silvioincalza/helper2.ai/issues/50) | 0004, 0022 |
| [0026](search/0026-filters-facets-categories.md) | Filters, facets, categories | [`search`](search/) | [F3.5](https://github.com/silvioincalza/helper2.ai/issues/51) | 0004, 0013, 0022, 0031, 0032, 0033, 0040, 0046 |
| [0027](search/0027-live-web-store-first-daily-quota.md) | Live web: store first, daily quota | [`search`](search/) | [F3.6](https://github.com/silvioincalza/helper2.ai/issues/52) | 0005, 0006, 0010, 0022, 0032, 0036 |
| [0028](search/0028-home-examples.md) | Home examples | [`search`](search/) | [F3.7](https://github.com/silvioincalza/helper2.ai/issues/53) | 0022, 0025, 0036, 0038, 0040, 0046 |
| [0029](search/0029-sources-search-content-words.md) | Sources search content words | [`search`](search/) | [F3.8](https://github.com/silvioincalza/helper2.ai/issues/54) | 0022, 0023, 0025, 0046 |
| [0030](search/0030-hacker-news-as-a-live-source.md) | Hacker News as a live source | [`search`](search/) | [F3.9](https://github.com/silvioincalza/helper2.ai/issues/55) | 0022, 0025, 0029 |
| [0031](guide/0031-guide-pipeline.md) | Guide pipeline | [`guide`](guide/) | [F4.1](https://github.com/silvioincalza/helper2.ai/issues/64) | 0008, 0009, 0012, 0014, 0022, 0024, 0025, 0027, 0032, 0033, 0034, 0046 |
| [0032](guide/0032-guide-cost-guard-and-answer-cache.md) | Guide cost guard and answer cache | [`guide`](guide/) | [F4.2](https://github.com/silvioincalza/helper2.ai/issues/65), [F4.6](https://github.com/silvioincalza/helper2.ai/issues/69) | 0004, 0005, 0006, 0007, 0008, 0010, 0024, 0026, 0027, 0031, 0033, 0037, 0046 |
| [0033](guide/0033-marking-ai-written-text-research-trail.md) | Marking AI-written text, research trail | [`guide`](guide/) | [F4.3](https://github.com/silvioincalza/helper2.ai/issues/66) | 0003, 0008, 0014, 0023, 0031, 0032, 0034, 0039 |
| [0034](guide/0034-setup-guide.md) | Setup guide | [`guide`](guide/) | [F4.4](https://github.com/silvioincalza/helper2.ai/issues/67) | 0002, 0004, 0031 |
| [0035](guide/0035-guide-model-stream.md) | Guide model stream | [`guide`](guide/) | [F4.5](https://github.com/silvioincalza/helper2.ai/issues/68) | 0006, 0008, 0031, 0032, 0033 |
| [0036](identity/0036-identity-and-the-admin-host.md) | Identity and the admin host | [`identity`](identity/) | [F5.1](https://github.com/silvioincalza/helper2.ai/issues/73), [F6.1](https://github.com/silvioincalza/helper2.ai/issues/81) | 0003, 0004, 0005, 0007, 0010, 0037, 0043 |
| [0037](identity/0037-http-edge.md) | HTTP edge | [`identity`](identity/) | [F5.4](https://github.com/silvioincalza/helper2.ai/issues/76) | 0001, 0005, 0006, 0007, 0036 |
| [0038](backoffice/0038-back-office-and-review.md) | Back office and review | [`backoffice`](backoffice/) | [F6.2](https://github.com/silvioincalza/helper2.ai/issues/82), [F6.3](https://github.com/silvioincalza/helper2.ai/issues/83) | 0002, 0004, 0007, 0011, 0012, 0013, 0014, 0027, 0028, 0032, 0033, 0036, 0043 |
| [0039](frontend/0039-frontend-one-box-state-in-the-url.md) | Frontend: one box, state in the URL | [`frontend`](frontend/) | [F7.1](https://github.com/silvioincalza/helper2.ai/issues/87) | 0001, 0003, 0005, 0022, 0027, 0031, 0032, 0040 |
| [0040](frontend/0040-design-system.md) | Design system | [`frontend`](frontend/) | [F7.2](https://github.com/silvioincalza/helper2.ai/issues/88) | 0031, 0032, 0033, 0036, 0039, 0041, 0042 |
| [0041](frontend/0041-site-pages-for-crawlers-policy-endpoint.md) | Site: pages for crawlers, policy endpoint | [`frontend`](frontend/) | [F7.3](https://github.com/silvioincalza/helper2.ai/issues/89) | 0001, 0002, 0003, 0007, 0010, 0027, 0032, 0033, 0037, 0039, 0040 |
| [0042](money/0042-monetisation.md) | Monetisation | [`money`](money/) | [F8.1](https://github.com/silvioincalza/helper2.ai/issues/95), [F8.2](https://github.com/silvioincalza/helper2.ai/issues/96), [F8.3](https://github.com/silvioincalza/helper2.ai/issues/97) | 0033, 0037, 0039, 0040, 0041, 0043 |
| [0043](operations/0043-runtime-topology-helm-chart-three-deployables.md) | Runtime topology: Helm chart, three deployables | [`operations`](operations/) | [F6.1](https://github.com/silvioincalza/helper2.ai/issues/81), [F10.1](https://github.com/silvioincalza/helper2.ai/issues/107) | 0001, 0002, 0003, 0004, 0005, 0007, 0009, 0011, 0036, 0037, 0038, 0041, 0042, 0044 |
| [0044](operations/0044-ci-cd-and-supply-chain.md) | CI/CD and supply chain | [`operations`](operations/) | [F10.2](https://github.com/silvioincalza/helper2.ai/issues/108) | 0007, 0009, 0043, 0045, 0047 |
| [0045](operations/0045-releases-and-versions.md) | Releases and versions | [`operations`](operations/) | [F10.3](https://github.com/silvioincalza/helper2.ai/issues/109) | 0001, 0004, 0010, 0044 |
| [0046](operations/0046-evaluation-guide-and-search.md) | Evaluation: guide and search | [`operations`](operations/) | [F10.4](https://github.com/silvioincalza/helper2.ai/issues/110) | 0008, 0022, 0024, 0025, 0031, 0032, 0034 |
| [0047](operations/0047-code-format-and-dead-code-audit.md) | Code format and dead-code audit | [`operations`](operations/) | [F10.5](https://github.com/silvioincalza/helper2.ai/issues/111) | 0044 |
