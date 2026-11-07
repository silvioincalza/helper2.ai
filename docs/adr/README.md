# Architecture Decision Records

One ADR per feature. Each category has its own folder, and the tables follow the order a reader should learn the
system: reading them top to bottom builds the whole system up step by step. The number 0021 is not used.
Every record is Accepted and describes the code as it is today, as if it was built that way from the start.
Open items and known limits are not kept in the records: each one links to [the roadmap](../roadmap.md).

Overview diagrams: [architecture views](../architecture.md#the-same-system-one-view-at-a-time) · [infrastructure](../diagrams/infrastructure.svg).
All diagrams: [docs/diagrams](../diagrams/README.md).

## Foundation — [`foundation/`](foundation/)

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0001](foundation/0001-stack-and-runtime.md) | Stack and runtime: Java 25 + Kotlin, Spring Boot 4.1, Spring AI 2.0, React SPA served by Spring, JVM image | — | [flow](../diagrams/flow-0001-stack-and-runtime.svg) · [sequence](../diagrams/sequence-0001-stack-and-runtime.svg) |
| [0002](foundation/0002-modular-monolith-hexagonal.md) | Modular monolith with Spring Modulith; hexagonal where business rules live; Kotlin domain, Java adapters | 0001 | [flow](../diagrams/flow-0002-modular-monolith-hexagonal.svg) · [sequence](../diagrams/sequence-0002-modular-monolith-hexagonal.svg) |
| [0003](foundation/0003-contract-first-rest-api.md) | Contract-first REST with OpenAPI 3.1; generated server interfaces and TypeScript types; problem+json | 0001, 0002 | [flow](../diagrams/flow-0003-contract-first-rest-api.svg) · [sequence](../diagrams/sequence-0003-contract-first-rest-api.svg) |
| [0004](foundation/0004-persistence-and-provenance.md) | PostgreSQL as system of record; ids from canonical URLs; source records; dates never invented; one schema migration, forward-only once data must be kept, + repeatable seeds; catalog writes off the request path; the catalog owns its tables; shared model answers in one table | 0001, 0002 | [flow](../diagrams/flow-0004-persistence-and-provenance.svg) · [sequence](../diagrams/sequence-0004-persistence-and-provenance.svg) |
| [0005](foundation/0005-redis-disposable-cache.md) | Redis as a disposable cache: source responses, fresh/stale windows, circuit breaker, counters | 0002, 0004, 0033 | [flow](../diagrams/flow-0005-redis-disposable-cache.svg) · [sequence](../diagrams/sequence-0005-redis-disposable-cache.svg) |
| [0006](foundation/0006-outbound-http.md) | Outbound HTTP: Apache HttpClient 5 behind RestClient; timeouts; safe redirects; bounded bodies; error mapping | 0002, 0005, 0033 | [flow](../diagrams/flow-0006-outbound-http.svg) · [sequence](../diagrams/sequence-0006-outbound-http.svg) |
| [0007](foundation/0007-configuration-and-secrets.md) | Configuration and secrets: every external variable uses the H2_ prefix; secrets only in OpenBao (helper2/kv ci/<env> app_env); profiles | 0001 | [flow](../diagrams/flow-0007-configuration-and-secrets.svg) · [sequence](../diagrams/sequence-0007-configuration-and-secrets.svg) |
| [0033](foundation/0033-resilience-policies.md) | Resilience policies (Resilience4j): circuit breakers, bulkheads and retries by name | 0002, 0005, 0006, 0008, 0026 | [flow](../diagrams/flow-0033-resilience-policies.svg) · [sequence](../diagrams/sequence-0033-resilience-policies.svg) |
| [0068](foundation/0068-backend-libraries-and-apps.md) | The backend as libraries and apps: one Gradle project per shared library, the app on top; dependencies point one way; convention plugins in build-logic | 0002, 0003, 0047 | [flow](../diagrams/flow-0068-backend-libraries-and-apps.svg) · — (build only, no runtime path) |

## Search — [`search/`](search/)

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0008](search/0008-federated-search.md) | Federated search over disclosed sources: modes, parallel fan-out, deadlines, cooldown, circuit breakers, partial results, source status, merge and conflicts | 0002, 0004, 0005, 0006, 0033 | [flow](../diagrams/flow-0008-federated-search.svg) · [sequence](../diagrams/sequence-0008-federated-search.svg) |
| [0009](search/0009-ranking.md) | Ranking: every query word first, synonyms, two-stage index query, reciprocal-rank fusion, bounded popularity | 0004, 0008 | [flow](../diagrams/flow-0009-ranking.svg) · [sequence](../diagrams/sequence-0009-ranking.svg) |
| [0010](search/0010-filters-facets-dates-timeline.md) | Filters, facets with Unknown, sort, stated dates, list and timeline view | 0004, 0008 | [flow](../diagrams/flow-0010-filters-facets-dates-timeline.svg) · [sequence](../diagrams/sequence-0010-filters-facets-dates-timeline.svg) |
| [0011](search/0011-language-aware-search.md) | Language-aware search: detected stemmer, query translation in parallel with sources | 0008, 0009 | [flow](../diagrams/flow-0011-language-aware-search.svg) · [sequence](../diagrams/sequence-0011-language-aware-search.svg) |
| [0012](search/0012-vector-search-pgvector.md) | Vector search: pgvector + NVIDIA embeddings, indexer, fused by rank | 0004, 0008, 0009 | [flow](../diagrams/flow-0012-vector-search-pgvector.svg) · [sequence](../diagrams/sequence-0012-vector-search-pgvector.svg) |
| [0013](search/0013-works-with-flags.md) | "Works with" flags derived from current source tags | 0004, 0008 | [flow](../diagrams/flow-0013-works-with-flags.svg) · [sequence](../diagrams/sequence-0013-works-with-flags.svg) |
| [0037](search/0037-tavily-store-and-daily-quota.md) | Tavily: store first, per-feature daily quota | 0005, 0008, 0017, 0023, 0033, 0034 | [flow](../diagrams/flow-0037-tavily-store-and-quota.svg) · [sequence](../diagrams/sequence-0037-tavily-store-and-quota.svg) |
| [0049](search/0049-home-examples.md) | Home examples: a pool in the database, a quality check (an expected result in the top 3 of the example's own search), a random pick in the browser | 0008, 0009, 0023, 0036, 0040, 0045 | [flow](../diagrams/flow-0049-home-examples.svg) · [sequence](../diagrams/sequence-0049-home-examples.svg) |
| [0053](search/0053-category-filter.md) | Categories as a search filter: CATEGORY labels assigned by code (finder, words, tags, type; no model) at index time and by a backfill, live results at merge time, facet counts in memory, GET /categories, the guide honours the filter | 0004, 0008, 0010, 0015, 0036, 0038, 0041, 0042, 0051 | [flow](../diagrams/flow-0053-category-filter.svg) · [sequence](../diagrams/sequence-0053-category-filter.svg) |
| [0055](search/0055-search-inside-awesome-lists.md) | Search inside awesome lists: a tsvector per entry line and per list, never the README text; list-only matches count half and say where they were found | 0004, 0009, 0011, 0018, 0020, 0034, 0042 | [flow](../diagrams/flow-0055-search-inside-awesome-lists.svg) · [sequence](../diagrams/sequence-0055-search-inside-awesome-lists.svg) |

## AI guide — [`guide/`](guide/)

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0014](guide/0014-llm-integration.md) | LLM integration: Spring AI behind ports, one OpenAI-compatible provider, prompts on the classpath, thinking off, timeouts, budget per attempt, degraded mode | 0001, 0002, 0005, 0007 | [flow](../diagrams/flow-0014-llm-integration.svg) · [sequence](../diagrams/sequence-0014-llm-integration.svg) |
| [0015](guide/0015-ai-guide-evidence-gate.md) | AI guide: plan → retrieve → choose → deterministic evidence gate; streamed over SSE | 0008, 0009, 0012, 0014 | [flow](../diagrams/flow-0015-ai-guide-evidence-gate.svg) · [sequence](../diagrams/sequence-0015-ai-guide-evidence-gate.svg) |
| [0016](guide/0016-guide-reason-check.md) | Guide reason check: a second model call checks each reason; `checked` event | 0014, 0015 | [flow](../diagrams/flow-0016-guide-reason-check.svg) · [sequence](../diagrams/sequence-0016-guide-reason-check.svg) |
| [0017](guide/0017-guide-cost-guard.md) | Guide cost guard: per-minute limit, per-client daily quota, global daily cap, answer cache with config hash, single-flight, concurrency limit and deadline | 0005, 0014, 0015, 0016 | [flow](../diagrams/flow-0017-guide-cost-guard.svg) · [sequence](../diagrams/sequence-0017-guide-cost-guard.svg) |
| [0031](guide/0031-setup-guide.md) | Setup guide: "Set it up" from source data, one snippet per AI client, checked steps from the README | 0002, 0004, 0015 | [flow](../diagrams/flow-0031-setup-guide.svg) · [sequence](../diagrams/sequence-0031-setup-guide.svg) |
| [0032](guide/0032-guide-intents.md) | Guide intents: understand the question, ask back when vague or ambiguous, retrieve and answer by intent | 0014, 0015, 0016, 0017, 0030 | [flow](../diagrams/flow-0032-guide-intents.svg) · [sequence](../diagrams/sequence-0032-guide-intents.svg) |
| [0038](guide/0038-ai-content-marking.md) | Marking AI-written text: labels on screen, metadata in the API and the page | 0003, 0014, 0015, 0019 | [flow](../diagrams/flow-0038-ai-content-marking.svg) · [sequence](../diagrams/sequence-0038-ai-content-marking.svg) |
| [0041](guide/0041-guide-answer-cache.md) | Guide answer cache: normalised keys, answers in the shared cache, reuse for a near-identical question read the same way | 0004, 0012, 0017, 0030, 0032, 0034, 0037, 0038 | [flow](../diagrams/flow-0041-guide-answer-cache.svg) · [sequence](../diagrams/sequence-0041-guide-answer-cache.svg) |
| [0044](guide/0044-research-trail.md) | Research trail: the queries the guide ran and the suggestions its checks removed, by code; amends 0038 | 0015, 0016, 0038, 0041 | [flow](../diagrams/flow-0044-research-trail.svg) · [sequence](../diagrams/sequence-0044-research-trail.svg) |

## Scout and catalog features — [`scout/`](scout/)

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0018](scout/0018-scout-runner.md) | AI Resource Scout runner: schedule, intervals, cursors, backlog, failure isolation, cluster-wide run lock | 0004, 0006, 0014 | [flow](../diagrams/flow-0018-scout-runner.svg) · [sequence](../diagrams/sequence-0018-scout-runner.svg) |
| [0019](scout/0019-evidence-gated-enrichment.md) | Evidence-gated enrichment and review queue (value must appear in the excerpt; UNCHANGED skip; daily cap) | 0004, 0014, 0018 | [flow](../diagrams/flow-0019-evidence-gated-enrichment.svg) · [sequence](../diagrams/sequence-0019-evidence-gated-enrichment.svg) |
| [0020](scout/0020-scout-collectors.md) | Scout collectors: MCP Registry, HF trending, HF provider orgs, Tavily discovery, courses, awesome lists with classifier, ai-deadlines | 0006, 0014, 0018 | [flow](../diagrams/flow-0020-scout-collectors.svg) · [sequence](../diagrams/sequence-0020-scout-collectors.svg) |
| [0022](scout/0022-next-events.md) | Next events: stated event days, ai-deadlines and curated industry and community events | 0004, 0010, 0018, 0020 | [flow](../diagrams/flow-0022-next-events.svg) · [sequence](../diagrams/sequence-0022-next-events.svg) |
| [0043](scout/0043-enrichment-gap-round.md) | Enrichment gap round: read the README or model card once when a run leaves fields unproven | 0018, 0019, 0020, 0034 | [flow](../diagrams/flow-0043-enrichment-gap-round.svg) · [sequence](../diagrams/sequence-0043-enrichment-gap-round.svg) |
| [0051](scout/0051-tavily-discovery.md) | Tavily discovery: one collector per category of the AI world (34 in 7 families, BeanRegistrar), lists indexed, products reviewed and merged, credits capped | 0007, 0018, 0019, 0020, 0033, 0037, 0042 | [flow](../diagrams/flow-0051-tavily-discovery.svg) · [sequence](../diagrams/sequence-0051-tavily-discovery.svg) |
| [0056](scout/0056-evidence-ladder.md) | Evidence ladder: fill every field from the records, the README, then the product's own pages (Tavily Extract), facts only with a quote that states them, estimates labelled | 0019, 0033, 0037, 0038, 0043, 0051 | [flow](../diagrams/flow-0056-evidence-ladder.svg) · [sequence](../diagrams/sequence-0056-evidence-ladder.svg) |
| [0058](scout/0058-awesome-deep-search.md) | Deep search in awesome lists: nested lists followed (depth 2, 30 a run, cycle guard, time and size budget), links to product sites checked by code and sent to review (50 new a day), section headings as one more input of the category matcher | 0006, 0007, 0018, 0020, 0026, 0033, 0034, 0038, 0051, 0053, 0055, 0056 | [flow](../diagrams/flow-0058-awesome-deep-search.svg) · [sequence](../diagrams/sequence-0058-awesome-deep-search.svg) |
| [0061](scout/0061-linkedin-via-tavily.md) | LinkedIn via Tavily: public articles, posts, company pages and events found through Tavily, sorted by URL path, all sent to review; credits on the Scout's daily cap; each collector proposes only its own types | 0018, 0020, 0022, 0033, 0034, 0037, 0051 | [flow](../diagrams/flow-0061-linkedin-via-tavily.svg) · [sequence](../diagrams/sequence-0061-linkedin-via-tavily.svg) |
| [0062](scout/0062-writing-about-a-resource.md) | Writing about a resource: an article, a news item or a paper keeps only audience, tech level and use case, names what it is about (ABOUT, at most 3); discovery keeps posts about products as ARTICLE candidates | 0019, 0051, 0056, 0061 | [flow](../diagrams/flow-0062-writing-about-a-resource.svg) · [sequence](../diagrams/sequence-0062-writing-about-a-resource.svg) |
| [0063](scout/0063-discovery-hosts.md) | Discovery hosts: the writing sites and directories discovery knows by name live in a table the admin manages on the Settings page; read once per run, fail closed, host names checked, every change audited | 0023, 0045, 0051, 0058, 0060, 0062 | [flow](../diagrams/flow-0063-discovery-hosts.svg) · [sequence](../diagrams/sequence-0063-discovery-hosts.svg) |
| [0064](edge/0064-relationship-target-in-review.md) | Relationship target in the review list: point a candidate at a catalog resource, add the resource (name, https address, type; one per address) or rename it; the target becomes CURATED, the kind stays AI-written; a later run keeps the change; accepting verifies it | 0019, 0023, 0038, 0045, 0062 | [flow](../diagrams/flow-0064-relationship-target-in-review.svg) · [sequence](../diagrams/sequence-0064-relationship-target-in-review.svg) |
| [0065](edge/0065-candidate-edit-in-review.md) | Candidate edit in the review list: an admin fixes an open resource candidate's title, type (any candidate type) and tags before accepting it; a later finder keeps the edit; no lost update | 0045, 0051, 0058, 0061, 0062 | [flow](../diagrams/flow-0065-candidate-edit-in-review.svg) · [sequence](../diagrams/sequence-0065-candidate-edit-in-review.svg) |
| [0067](scout/0067-live-web-events-scout.md) | Live web events scout: weekly Tavily searches for AI hackathons, meetups, conferences and workshops; a model reads each page's days with a quote, code checks the quote is on the page and states those days within six months; labelled as AI, never over a source's days; off until Tavily confirms | 0014, 0018, 0020, 0022, 0033, 0037, 0038, 0051, 0056 | [flow](../diagrams/flow-0067-live-web-events-scout.svg) · [sequence](../diagrams/sequence-0067-live-web-events-scout.svg) |
| [0066](edge/0066-curated-field.md) | Curated field: an admin sets a resource's own type, pricing, licence, open source, tech level or audience, or drops what the AI said; the value wins over every source, run and seed of events, and in search too | 0004, 0019, 0022, 0045, 0056, 0062 | [flow](../diagrams/flow-0066-curated-field.svg) · [sequence](../diagrams/sequence-0066-curated-field.svg) |

## Edge and identity — [`edge/`](edge/)

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0023](edge/0023-identity-and-admin.md) | Identity: anonymous-first; GitHub sign-in; ADMIN by numeric id; bookmarks and account deletion for signed-in users; admin audit trail | 0003, 0004, 0005, 0007, 0034 | [flow](../diagrams/flow-0023-identity-and-admin.svg) · [sequence](../diagrams/sequence-0023-identity-and-admin.svg) |
| [0035](edge/0035-admin-host.md) | Admin host: a second port for the back office, ADMIN only there, sign-in on both hosts | 0023, 0024, 0027 | [flow](../diagrams/flow-0035-admin-host.svg) · [sequence](../diagrams/sequence-0035-admin-host.svg) |
| [0045](edge/0045-back-office.md) | Back office: a thin admin module, one page per part, an overview of what needs a person, budgets read from the shared counter, the evidence of a review item read through its link, bulk decisions item by item | 0002, 0017, 0018, 0023, 0035, 0037, 0038 | [flow](../diagrams/flow-0045-back-office.svg) · [sequence](../diagrams/sequence-0045-back-office.svg) |
| [0024](edge/0024-http-edge-protection.md) | HTTP edge: rate limits (IPv6 /64), CSRF, CSP, forwarded headers, management port, SPA fallback and caching | 0001, 0005, 0007, 0023, 0033 | [flow](../diagrams/flow-0024-http-edge-protection.svg) · [sequence](../diagrams/sequence-0024-http-edge-protection.svg) |

## Frontend — [`frontend/`](frontend/)

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0025](frontend/0025-frontend-one-box.md) | Frontend: one box, state in the URL, guide stream, accessibility, theme (system/light/dark) | 0001, 0003, 0008, 0015 | [flow](../diagrams/flow-0025-frontend-one-box.svg) · [sequence](../diagrams/sequence-0025-frontend-one-box.svg) |
| [0039](frontend/0039-frontend-recent-answers-cache.md) | Frontend recent-answers cache: back and forward without a new request | 0025 | [flow](../diagrams/flow-0039-frontend-recent-answers-cache.svg) · [sequence](../diagrams/sequence-0039-frontend-recent-answers-cache.svg) |
| [0040](frontend/0040-frontend-design.md) | Frontend design: home page, results with preview, guide picks bar, design system | 0015, 0025, 0038, 0039 | [flow](../diagrams/flow-0040-frontend-design.svg) · [sequence](../diagrams/sequence-0040-frontend-design.svg) |
| [0042](frontend/0042-redesign-design-system-v4.md) | Redesign: design system version 4 (Geist fonts, one set of v4 tokens, header, footer, Home with radar, pinned box and the two lists, motion rule, numbers from data; page-by-page move) | 0015, 0017, 0025, 0038, 0040 | [flow](../diagrams/flow-0042-redesign.svg) · [sequence](../diagrams/sequence-0042-redesign.svg) |
| [0050](frontend/0050-pages-for-crawlers.md) | Pages for crawlers: Spring writes the head and a short body for client routes, real 404s and 301s, one index-worthy rule, sitemap.xml and robots.txt | 0001, 0024, 0025, 0038, 0040 | [flow](../diagrams/flow-0050-pages-for-crawlers.svg) · [sequence](../diagrams/sequence-0050-pages-for-crawlers.svg) |
| [0057](frontend/0057-monetisation.md) | Monetisation: gifts (GitHub Sponsors, Stripe), Google Analytics only after consent, one EthicalAds slot above the results that also shows while a search runs; CSP per part that is on; footer "No tracking ads." | 0024, 0025, 0040, 0042 | [flow](../diagrams/flow-0057-monetisation.svg) · [sequence](../diagrams/sequence-0057-monetisation.svg) |
| [0059](frontend/0059-adsense.md) | Google AdSense on resource pages: site check tag and /ads.txt; Google's consent tool for AdSense and GA4 (GA4 after consent where the rules apply, without a question elsewhere); a nonce CSP on the whole public site while AdSense is on; no footer claim about ads | 0024, 0050, 0057 | [flow](../diagrams/flow-0059-adsense.svg) · [sequence](../diagrams/sequence-0059-adsense.svg) |

## Operations — [`operations/`](operations/)

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0026](operations/0026-observability.md) | Observability: structured logs, OpenTelemetry traces, Prometheus meters, Grafana dashboard, alert rules | 0001, 0008, 0014, 0033 | [flow](../diagrams/flow-0026-observability.svg) · [sequence](../diagrams/sequence-0026-observability.svg) |
| [0027](operations/0027-runtime-topology.md) | Runtime topology: Helm chart, three replicas on prod, Cloudflare and ingress-nginx in front, per-workload network policies, Redis auth, backups | 0001, 0004, 0005, 0007, 0018, 0023, 0024 | [flow](../diagrams/flow-0027-runtime-topology.svg) · [sequence](../diagrams/sequence-0027-runtime-topology.svg) |
| [0028](operations/0028-ci-cd.md) | CI/CD: GitHub Actions, PR checks, OpenBao OIDC, staging on main, approved prod on a release | 0007, 0027 | [flow](../diagrams/flow-0028-ci-cd.svg) · [sequence](../diagrams/sequence-0028-ci-cd.svg) |
| [0029](operations/0029-release-versioning.md) | Release and versioning: semantic versions, immutable tags, release workflow | 0001, 0028 | [flow](../diagrams/flow-0029-release-versioning.svg) · [sequence](../diagrams/sequence-0029-release-versioning.svg) |
| [0034](operations/0034-data-retention.md) | Data retention: a period and a mechanism for every store; daily purges per module | 0004, 0005, 0023, 0026 | [flow](../diagrams/flow-0034-data-retention.svg) · [sequence](../diagrams/sequence-0034-data-retention.svg) |
| [0046](operations/0046-supply-chain.md) | Supply chain: base images pinned by digest, an SBOM per image, a Grype scan, report-only first | 0027, 0028, 0029 | [flow](../diagrams/flow-0046-supply-chain.svg) |
| [0048](operations/0048-release-branches-and-version-tags.md) | Release branches and version tags on images: patch-only fixes from release/*, prod runs X.Y.Z, no rebuild | 0028, 0029, 0046 | [flow](../diagrams/flow-0048-release-branches-and-version-tags.svg) · [sequence](../diagrams/sequence-0048-release-branches-and-version-tags.svg) |
| [0052](operations/0052-policy-endpoint.md) | Policy endpoint: GET /api/v1/policy gives the limits and retention periods the text pages state, from the configuration; one policy value per owning module, a thin policy module | 0003, 0007, 0017, 0024, 0034, 0037 | [flow](../diagrams/flow-0052-policy-endpoint.svg) · [sequence](../diagrams/sequence-0052-policy-endpoint.svg) |
| [0060](operations/0060-runtime-settings.md) | Runtime settings: six spending limits change from the back office without a deploy; an allow-list with a maximum in code, append-only in Postgres, a snapshot per replica refreshed every 20 s, audited and undoable | 0005, 0007, 0017, 0023, 0034, 0037, 0043, 0045 | [flow](../diagrams/flow-0060-runtime-settings.svg) · [sequence](../diagrams/sequence-0060-runtime-settings.svg) |

## Quality — [`quality/`](quality/)

| # | Decision | Builds on | Diagrams |
|---|---|---|---|
| [0030](quality/0030-guide-evaluation.md) | Guide evaluation: fixed question set, script, published results | 0015, 0016, 0017 | [flow](../diagrams/flow-0030-guide-evaluation.svg) · [sequence](../diagrams/sequence-0030-guide-evaluation.svg) |
| [0036](quality/0036-search-evaluation-harness.md) | Search evaluation harness: frozen snapshots, pooled graded judgements, offline replay | 0008, 0009, 0012, 0030 | [flow](../diagrams/flow-0036-search-evaluation-harness.svg) · [sequence](../diagrams/sequence-0036-search-evaluation-harness.svg) |
| [0047](quality/0047-code-format.md) | Code format: Google style for Java and Kotlin (google-java-format, ktfmt) through Spotless, Prettier for the frontend, checked in CI | 0028 | [flow](../diagrams/flow-0047-code-format.svg) |
| [0054](quality/0054-dead-code-and-dependency-audit.md) | Dead code and dependency audit: knip in strict mode (whole project, then production code and its dependencies) and npm audit (High or Critical in production packages fails) in the frontend CI job | 0028, 0046, 0047 | [flow](../diagrams/flow-0054-dead-code-and-dependency-audit.svg) |

## Writing a new ADR

- Take the next free number and put the file in its category folder. One feature or one decision per record.
- Sections: Context, Decision, Consequences, Alternatives considered, Code, Diagrams, Roadmap.
- Write in the present tense, as if the code was built this way from the start: no history, no before and after.
- Consequences say what the decision gives. Known limits and open work go to [the roadmap](../roadmap.md), not into the record.
- A decision that changes an older one rewrites the older record in the same pull request, so every record matches the code.
- A record that a newer one replaces as a whole gets one line under its title, "Superseded by NNNN", and stays.
