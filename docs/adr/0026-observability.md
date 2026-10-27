# 0026 — Observability: structured logs, OpenTelemetry traces, Prometheus meters, Grafana dashboard

**Status.** Accepted (2026-10-27). **Builds on:** [0001](0001-stack-and-runtime.md), [0008](0008-federated-search.md), [0014](0014-llm-integration.md)

## Context
One search calls up to nine sources in parallel; one guide question calls a model several times. When an answer is
slow or partial, the owner must see which source, which model step or which budget caused it. The cluster already runs
Grafana Alloy, Prometheus and Tempo. Telemetry must not carry query text or user data.

## Decision
1. **Logs.** Spring Boot structured logging on stdout, format `H2_LOG_FORMAT` (`logstash` JavaScript Object Notation (JSON); the `dev` profile uses
   plain text). Trace and span ids are in each line. No second copy over OpenTelemetry Protocol (OTLP).
2. **Traces.** `spring-boot-starter-opentelemetry`. Export only when `H2_TRACING_EXPORT=true`; sampling
   `H2_TRACING_SAMPLING` (1.0); endpoint `H2_OTLP_TRACES_ENDPOINT` (the chart: Alloy at
   `http://alloy.observability.svc:4318/v1/traces`, which forwards to Tempo). Resource attributes: `service.name=helper2`,
   `deployment.environment.name=H2_ENVIRONMENT`.
3. **Context across threads.** The search executor is wrapped with `ContextExecutorService`, so each source call runs
   in the request's trace. Source and embedding clients share `ObservedHttp` ([0006](0006-outbound-http.md)):
   `http.client.requests`, a client span and trace headers, with a bounded Uniform Resource Identifier (URI) tag
   and no query string. Every model call (guide steps, translation, enrichment, classifier with step `classify`) is
   observed by `ModelCallMetrics` (span and timer `helper2.guide.model.call`).
   `ObservabilityConfiguration` drops observations of `/actuator/**` (probes and scrapes).
4. **Meters** (Prometheus, pulled from `/actuator/prometheus` on the management port, [0024](0024-http-edge-protection.md);
   OTLP metric push is off). Common tags `application=helper2`, `environment`. Histogram buckets for
   `http.server.requests`, `http.client.requests`, `helper2.scout.run`, `helper2.guide.model.call`,
   `helper2.search.latency`, `helper2.source.latency`. Our meters:
   - search: `helper2.search.requests{partial}`, `helper2.search.latency`, `helper2.source.requests{source,outcome,served_from}`,
     `helper2.source.latency{source}`, `helper2.cache.events{event}`;
   - catalog: `helper2.catalog.observations{outcome}`, `helper2.catalog.observations.queued`,
     `helper2.embedding.backlog`, `helper2.embedding.failures{reason}`, `helper2.embedding.last.success`;
   - guide and models: `helper2.guide.ask{outcome}`, `helper2.guide.questions{outcome}`, `helper2.guide.cache{result}`,
     `helper2.guide.model.call{step,model,outcome}`, `helper2.guide.tokens{step,type}`, `helper2.guide.budget.remaining`,
     `helper2.search.translation.budget.remaining`;
   - Scout: `helper2.scout.run`, `helper2.scout.cycle`, `helper2.scout.items{source,kind}`, `helper2.scout.last.success{source}`,
     `helper2.scout.enrichment{outcome}`, `helper2.scout.enrichment.budget.remaining`,
     `helper2.scout.classifier.budget.remaining`;
   - platform: `helper2.rate-limit.fallback{limiter}` (rate-limit decisions made in one replica's memory while
     Redis is down, [0024](0024-http-edge-protection.md)).
   Tag values are fixed words, source ids and configured model ids; never a query, a prompt or a user.
5. **Dashboard.** `deploy/grafana/helper2.json` (uid `helper2`, variable `environment`, datasource uid `prometheus`).
   Rows: overview, Hypertext Transfer Protocol (HTTP), search and sources, artificial intelligence (AI) guide, Scout,
   embeddings, runtime. Latency panels (HTTP, search, source, model call, Scout run) use `histogram_quantile` over
   the `_bucket` series, so they are percentiles across all pods; `helper2.guide.ask` has no buckets, so its panel
   shows the mean. One panel per daily budget (guide, translation, enrichment, classifier) shows the calls left, and
   "Rate-limit decisions in memory" shows `helper2.rate-limit.fallback`. Loaded by file provisioning or the Grafana
   application programming interface (API).

## Consequences
- Good: a slow search shows per-source latency and outcome; a slow guide shows the model step and model.
- Good: logs, traces and meters share the trace id.
- Bad: no alert rules exist yet; problems are seen only when someone opens the dashboard.
- Bad: histogram buckets add series per timer; the bucketed timers are limited to the six listed above.

## Alternatives considered
- Push metrics over OTLP — the cluster scrapes with Alloy; pushing would send a second copy.
- Logs over OTLP — stdout JSON is already collected; a second path adds cost.
- Sample traces lower — traffic is low; probes and scrapes are already dropped.
- Query text as a tag — high cardinality and personal data.

## Code
- `backend/src/main/resources/application.yaml` — `management.*`, `logging.structured`, tracing settings.
- `backend/src/main/java/ai/helper2/platform/observability/ObservabilityConfiguration.java` — actuator filter.
- `backend/src/main/java/ai/helper2/search/config/MicrometerSearchMetrics.java`, `SearchConfiguration.java` — search meters, context executor.
- `backend/src/main/java/ai/helper2/platform/llm/ModelCallMetrics.java` — model call span, timer, tokens.
- `backend/src/main/java/ai/helper2/platform/http/ObservedHttp.java` — observed source and embedding clients.
- `backend/src/main/java/ai/helper2/platform/llm/LlmConfiguration.java`, `sources/SourcesConfiguration.java` —
  translation and classifier budget gauges.
- `backend/src/main/java/ai/helper2/catalog/internal/EmbeddingIndexer.java`, `ObservationQueue.java` — catalog meters.
- `deploy/grafana/helper2.json` (Grafana dashboard); `deploy/helm/helper2/templates/app.yaml` — scrape annotations, tracing env.

## Diagrams
[flow](../diagrams/flow-0026-observability.svg) · [sequence](../diagrams/sequence-0026-observability.svg)

## Roadmap
- [Alert rules: source failures, partial results, cache errors, 5xx, budgets, embedding backlog](../roadmap.md#3-operations).
- [Postgres and Redis exporters in the Helm chart](../roadmap.md#3-operations).
- [Confirm trace and span ids in every log line](../roadmap.md#3-operations).
- [Conference files skipped by ai-deadlines are logged, not counted](../roadmap.md#6-search-and-catalog).
