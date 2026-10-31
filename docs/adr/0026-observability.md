# 0026 — Observability: structured logs, OpenTelemetry traces, Prometheus meters, Grafana dashboard, alert rules

**Status.** Accepted (2026-10-27). **Builds on:** [0001](0001-stack-and-runtime.md), [0008](0008-federated-search.md), [0014](0014-llm-integration.md), [0033](0033-resilience-policies.md)

## Context
One search calls up to nine sources in parallel; one guide question calls a model several times. When an answer is
slow or partial, the owner must see which source, which model step or which budget caused it, and be told when
something breaks without watching a dashboard. The cluster already runs Grafana Alloy, Prometheus, Loki and Tempo.
Telemetry must not carry query text or user data, and logs, which hold client addresses, are personal data with a
retention limit.

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
   and no query string. Every model call (guide steps, translation, setup guide with step `tool-guide`, enrichment, classifier with step
   `classify`) is observed by `ModelCallMetrics` (span and timer `helper2.llm.call`), tagged with the version of its
   prompt.
   `ObservabilityConfiguration` drops observations of `/actuator/**` (probes and scrapes).
4. **Meters** (Prometheus, pulled from `/actuator/prometheus` on the management port, [0024](0024-http-edge-protection.md);
   OTLP metric push is off). Common tags `application=helper2`, `environment`. Histogram buckets for
   `http.server.requests`, `http.client.requests`, `helper2.scout.run`, `helper2.llm.call`,
   `helper2.search.latency`, `helper2.source.latency`. Our meters:
   - search: `helper2.search.requests{partial}`, `helper2.search.latency`, `helper2.source.requests{source,outcome,served_from}`,
     `helper2.source.latency{source}`, `helper2.cache.events{event}`;
   - catalog: `helper2.catalog.observations{outcome}`, `helper2.catalog.observations.queued`,
     `helper2.embedding.backlog`, `helper2.embedding.failures{reason}`, `helper2.embedding.last.success`;
   - guide and models: `helper2.guide.ask{outcome}`, `helper2.guide.questions{outcome}`, `helper2.guide.cache{result}`,
     `helper2.llm.call{step,model,prompt,outcome}`, `helper2.llm.tokens{step,prompt,type}`, `helper2.guide.budget.remaining`,
     `helper2.search.translation.budget.remaining`;
   - Scout: `helper2.scout.run`, `helper2.scout.cycle`, `helper2.scout.items{source,kind}`, `helper2.scout.last.success{source}`,
     `helper2.scout.enrichment{outcome}`, `helper2.scout.enrichment.budget.remaining`,
     `helper2.scout.classifier.budget.remaining`;
   - platform: `helper2.rate-limit.fallback{limiter}` (rate-limit decisions made in one replica's memory while
     Redis is down, [0024](0024-http-edge-protection.md));
   - resilience policies (resilience4j-micrometer, [0033](0033-resilience-policies.md)), tag `name` = policy
     (`source-<id>`, `collector-<id>`, `redis`): `resilience4j.circuitbreaker.state{name,state}` (1 for the current
     state), `resilience4j.circuitbreaker.calls{name,kind}`, `resilience4j.circuitbreaker.not.permitted.calls{name}`,
     `resilience4j.circuitbreaker.failure.rate`, `resilience4j.circuitbreaker.slow.call.rate`,
     `resilience4j.bulkhead.available.concurrent.calls{name}`, `resilience4j.retry.calls{name,kind}`.
   Tag values are fixed words, source ids, policy names and configured model ids; never a query, a prompt or a user.
5. **Dashboard.** `deploy/grafana/helper2.json` (uid `helper2`, variable `environment`, datasource uid `prometheus`).
   Rows: overview, Hypertext Transfer Protocol (HTTP), search and sources, artificial intelligence (AI) guide, Scout,
   embeddings, runtime. Latency panels (HTTP, search, source, model call, Scout run) use `histogram_quantile` over
   the `_bucket` series, so they are percentiles across all pods; `helper2.guide.ask` has no buckets, so its panel
   shows the mean. One panel per daily budget (guide, translation, enrichment, classifier) shows the calls left, and
   "Rate-limit decisions in memory" shows `helper2.rate-limit.fallback`. Loaded by file provisioning or the Grafana
   application programming interface (API).
6. **Alert rules.** `deploy/monitoring/helper2-rules.yaml`, a plain Prometheus rule file, is the single source. The
   chart embeds it as a `PrometheusRule` when `monitoring.prometheusRule.enabled` is true (off by default: the
   custom resource exists only where the Prometheus Operator runs); the template reads the file through the symlink
   `files/helper2-rules.yaml`, so the rules are never copied. Without the operator, Prometheus loads the file in
   `rule_files`. App rules group by `environment`; pod and Job rules read kube-state-metrics in the namespaces
   `helper2-<environment>`. Each rule has a `severity` (`critical`: users get errors now; `warning`: look within
   the day), a `for:`, a summary and description in plain English, and a `runbook` hint naming the dashboard panel
   and the first command. The rules:
   - `Helper2HighServerErrorRatio` (critical): more than 5% of requests answer 5xx for 10 min, above one request a
     minute;
   - `Helper2SourceFailing`: more than 20% of a source's calls FAILED, TIMED_OUT or RATE_LIMITED for 15 min
     (SKIPPED is a decision, not a failure); `Helper2PartialSearches`: more than half of the searches partial for
     30 min; `Helper2SearchCacheErrors`: `helper2.cache.events{event="error"}` for 10 min;
   - `Helper2RateLimitInMemory`: any `helper2.rate-limit.fallback`;
   - `Helper2CircuitOpen`: a policy's circuit not closed on some pod for 5 min (it cycles open and half-open while
     the dependency is down); `Helper2CallsNotPermitted`: an open circuit refuses more than one call a minute for
     10 min ([0033](0033-resilience-policies.md));
   - `Helper2DailyBudgetLow`: any `*.budget.remaining` gauge below 20% of its cap for 5 min (the cap is the
     gauge's highest value of the last day; label `budget` = guide, tool_guide, search_translation,
     scout_enrichment, scout_classifier);
   - `Helper2EmbeddingIndexerStalled`: no successful indexer batch for 30 min; `Helper2EmbeddingBacklogStuck`:
     backlog above 0 for 3 h on the replica that indexes;
   - `Helper2ScoutCollectorStale`: a collector's last success older than its run interval plus a day (2 days;
     `tavily-scout` 6, `course-scout` 8), over a 9-day lookback so a deploy does not lose the gauge;
   - `Helper2GuidePicksFromLowRanks`: more than 20% of the day's kept picks, and at least 3, from search rank 9+
     (`helper2.guide.picks{rank="9+"}`), the first sign of content poisoning ([0015](0015-ai-guide-evidence-gate.md));
   - `Helper2BackupFailed` (critical): a `pg-backup` Job that started in the last 24 h failed;
     `Helper2PodRestarting`: more than 2 restarts of a container in an hour.
   `promtool check rules` and `promtool test rules` (unit tests in `deploy/monitoring/tests/`) run in CI; chart-check
   renders the `PrometheusRule` once and validates it against its CRD schema.
7. **Retention.** Required of the observability stack, which lives outside this repository and must use these values:
   - logs (Loki: the app's stdout and the Traefik access logs): deleted after 3 months (`retention_period: 2160h`,
     90 days), the period the privacy page states;
   - traces (Tempo): deleted after 3 months (`block_retention: 2160h`), never kept longer than the logs they link to;
   - metrics (Prometheus): 90 days (`--storage.tsdb.retention.time=90d`), at least 9 days for the Scout rule's
     lookback. Meters carry no personal data, so this bound is cost, not privacy.
   Database backups: 7 days on the `pg-backup` volume (`backup.retentionDays`); the off-site copy, when on, by its
   bucket's lifecycle rule ([0027](0027-runtime-topology.md)).

## Consequences
- Good: a slow search shows per-source latency and outcome; a slow guide shows the model step and model.
- Good: logs, traces and meters share the trace id.
- Good: a failing source, an open circuit, a spent budget, a stalled indexer or Scout, a failed backup or a poisoned
  pick raises an alert, and each alert says where to look first.
- Good: one rule file serves a plain Prometheus and the operator; the unit tests pin each threshold.
- Bad: alert delivery (Alertmanager receivers, the owner's phone or mail) is configured in the observability stack,
  not here.
- Bad: gauges live in the memory of one replica (the Scout and indexer gauges on the replica that ran them), so
  their rules look back over time windows; a collector disabled on purpose keeps alerting until its value is 9 days old.
  Circuit states are per replica too: the circuit rule fires when any pod's circuit stays open.
- Bad: Redis memory and evictions are not measured (no Redis exporter in the chart), so an evicted key is noticed
  only by its effect (a cache miss, a fresh rate-limit window).
- Bad: histogram buckets add series per timer; the bucketed timers are limited to the six listed above.

## Alternatives considered
- Push metrics over OTLP — the cluster scrapes with Alloy; pushing would send a second copy.
- Logs over OTLP — stdout JSON is already collected; a second path adds cost.
- Sample traces lower — traffic is low; probes and scrapes are already dropped.
- Query text as a tag — high cardinality and personal data.
- Grafana-managed alerts — stored in Grafana's database, not reviewable or testable in the repository.
- Rules only as a `PrometheusRule` template — ties the chart to the Prometheus Operator; a plain rule file works
  with any Prometheus.
- A copy of the rules in the chart — two sources drift; the symlink and `.Files.Get` keep one.

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
- `deploy/monitoring/helper2-rules.yaml` (alert rules), `deploy/monitoring/tests/` (promtool unit tests);
  `deploy/helm/helper2/templates/prometheusrule.yaml`, `files/helper2-rules.yaml` (symlink) — optional `PrometheusRule`.

## Diagrams
[flow](../diagrams/flow-0026-observability.svg) · [sequence](../diagrams/sequence-0026-observability.svg)

## Roadmap
- [Postgres and Redis exporters in the Helm chart](../roadmap.md#3-operations).
- [Confirm trace and span ids in every log line](../roadmap.md#3-operations).
- [Conference files skipped by ai-deadlines are logged, not counted](../roadmap.md#6-search-and-catalog).
