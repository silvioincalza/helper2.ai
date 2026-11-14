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
