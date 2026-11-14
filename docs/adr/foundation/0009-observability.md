# 0009 — Observability

**Depends on:** [0001](0001-stack-and-runtime.md), [0006](0006-outbound-calls-and-resilience.md), [0008](0008-llm-integration.md), [0022](../search/0022-federated-search.md)

## Context
One search calls up to nine sources in parallel; one guide question calls a model several times. When an answer is
slow or partial, the owner must see which source, which model step or which budget caused it, and be told when
something breaks without watching a dashboard. The cluster already runs Grafana Alloy, Prometheus, Loki and Tempo.
Telemetry must not carry query text or user data, and logs, which hold client addresses, are personal data with a
retention limit. The guide needs one latency number to hold: a full answer takes about 6 s, but the asker sees
the guide work long before that, and what makes the wait feel long is the first moment the page shows nothing real.

## Decision
1. **Logs.** Spring Boot structured logging on stdout, format `H2_LOG_FORMAT` (`logstash` JavaScript Object Notation (JSON); the `dev` profile uses
   plain text). Trace and span ids (`traceId`, `spanId`) are in every log line written inside a request or an observed
   background task: Micrometer Tracing puts them in the logging context while a span is in scope. Startup lines and
   the threads of their own (guide heartbeat, embedding indexer, catalog write queue) have none. No second copy over OpenTelemetry Protocol (OTLP).
2. **Traces.** `spring-boot-starter-opentelemetry`. Export only when `H2_TRACING_EXPORT=true`; sampling
   `H2_TRACING_SAMPLING` (1.0); endpoint `H2_OTLP_TRACES_ENDPOINT` (the chart: Alloy at
   `http://alloy.observability.svc:4318/v1/traces`, which forwards to Tempo). Resource attributes: `service.name`
   (`helper2` for the public app, `helper2-backoffice` for the back office), `deployment.environment.name=H2_ENVIRONMENT`.
3. **Context across threads.** The search executor is wrapped with `ContextExecutorService`, so each source call runs
   in the request's trace. So are the guide's question threads and its deadline callback (`GuideLimits`), and a Scout
   run started from the admin page (`ScoutConfiguration.RUN_THREADS`). Scheduled jobs (the Scout tick, the daily
   retention jobs) are observed by Spring (`tasks.scheduled.execution`), so each run is a trace of its own. Source and embedding clients share `ObservedHttp` ([0006](0006-outbound-calls-and-resilience.md)):
   `http.client.requests`, a client span and trace headers, with a bounded Uniform Resource Identifier (URI) tag
   and no query string. Every model call (guide steps, translation, setup guide with steps `tool-guide` and `tool-guide-verify`, enrichment, classifier with step
   `classify`) is observed by `ModelCallMetrics` (span and timer `helper2.llm.call`), tagged with the version of its
   prompt.
   `ObservabilityConfiguration` drops observations of `/actuator/**` (probes and scrapes).
4. **Meters** (Prometheus, pulled from `/actuator/prometheus` on the management port, [0037](../identity/0037-http-edge.md);
   OTLP metric push is off). Common tags `application` and `environment`. `application` names the app
   ([0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)): `helper2` for the public app, `helper2-backoffice` for the back office. Both apps
   share the libraries, so some meters exist in both (measured: the embedding gauges, the review count, the
   example-draft budget); only the app that runs the work moves them, and the dashboards and rules read that app. Histogram buckets for
   `http.server.requests`, `http.client.requests`, `helper2.scout.run`, `helper2.llm.call`,
   `helper2.search.latency` (`application.yaml`), `helper2.source.latency` (set on the timer), and
   `helper2.guide.first_useful` (set on the timer, with exact buckets at its 3 s and 8 s targets). Our meters:
   - search: `helper2.search.requests{partial}`, `helper2.search.latency`, `helper2.source.requests{source,outcome,served_from}`,
     `helper2.source.latency{source,outcome}` (one live call to a source: its time, failed and timed-out calls
     included; a skipped source or a fresh cache hit is not a call and records nothing; the recording rule
     `environment_source:helper2_source_latency_seconds:p95_5m` gives its p95 per source), `helper2.cache.events{event}`,
     `helper2.shared.cache{cache,result}` (`translation`, `query-vector`, `tool-guide`, `guide-answer`; a miss includes a database error
     or timeout);
   - catalog: `helper2.catalog.observations{outcome}`, `helper2.catalog.observations.queued`,
     `helper2.embedding.backlog`, `helper2.embedding.failures{reason}`, `helper2.embedding.last.success`;
   - guide and models: `helper2.guide.ask{outcome}`, `helper2.guide.questions{outcome}`, `helper2.guide.cache{result}`,
     `helper2.guide.step{step,intent}`, `helper2.guide.first_useful{intent}` (item 8),
     `helper2.llm.call{step,model,prompt,outcome}`, `helper2.llm.tokens{step,prompt,type}`,
     `helper2.llm.tokens_unknown{step,prompt,type}` (calls whose answer reported no such count),
     `helper2.llm.thinking_fallback{step,reason}` (a guide step asked again with thinking off: `cut` while thinking,
     [0008](0008-llm-integration.md), or `time`, [0035](../guide/0035-guide-model-stream.md)), `helper2.guide.budget.remaining`,
     `helper2.search.translation.budget.remaining`;
   - Scout: `helper2.scout.run`, `helper2.scout.cycle`, `helper2.scout.items{source,kind}`, `helper2.scout.last.success{source}`,
     `helper2.scout.enrichment{outcome}`, `helper2.scout.enrichment.budget.remaining`,
     `helper2.scout.classifier.budget.remaining`;
   - platform: `helper2.rate-limit.fallback{limiter}` (rate-limit decisions made in one replica's memory while
     Redis is down, [0037](../identity/0037-http-edge.md));
   - resilience policies (resilience4j-micrometer, [0006](0006-outbound-calls-and-resilience.md)), tag `name` = policy
     (`source-<id>`, `collector-<id>`, `redis`): `resilience4j.circuitbreaker.state{name,state}` (1 for the current
     state), `resilience4j.circuitbreaker.calls{name,kind}`, `resilience4j.circuitbreaker.not.permitted.calls{name}`,
     `resilience4j.circuitbreaker.failure.rate`, `resilience4j.circuitbreaker.slow.call.rate`,
     `resilience4j.bulkhead.available.concurrent.calls{name}`, `resilience4j.retry.calls{name,kind}`.
   Tag values are fixed words, source ids, policy names and configured model ids; never a query, a prompt or a user.
5. **Dashboards, one per app** (variable `environment`, datasource uid `prometheus`, a link to each other).
   `deploy/grafana/helper2.json` (uid `helper2`, `application="helper2"`): overview, Hypertext Transfer Protocol
   (HTTP), search and sources, artificial intelligence (AI) guide, runtime, pod, logs.
   `deploy/grafana/helper2-backoffice.json` (uid `helper2-backoffice`, `application="helper2-backoffice"`): overview,
   Scout (time since each collector's last success, runs by status, failures by error, items), enrichment and budgets
   (every back office daily budget, the evidence step's page reads, the Scout's Tavily credits by feature, model
   calls), embedding indexer, admin and runtime, pod, logs. Latency panels (HTTP, search, source, model call, Scout
   run, guide first useful event) use `histogram_quantile` over the `_bucket` series, so they are percentiles across
   all pods; `helper2.guide.ask` has no buckets, so its panel shows the mean. One panel per daily budget shows the
   calls left, and "Rate-limit decisions in memory" shows `helper2.rate-limit.fallback`. Loaded by file provisioning
   or the Grafana application programming interface (API).
6. **Alert rules.** `deploy/monitoring/helper2-rules.yaml`, a plain Prometheus rule file, is the single source. The
   chart embeds it as a `PrometheusRule` when `monitoring.prometheusRule.enabled` is true (off by default: the
   custom resource exists only where the Prometheus Operator runs); the template reads the file through the symlink
   `files/helper2-rules.yaml`, so the rules are never copied. Without the operator, Prometheus loads the file in
   `rule_files`. App rules group by `environment` and select the app: the search, guide and HTTP rules
   `application="helper2"`; the Scout, budget, indexer and review rules of the back office
   `application="helper2-backoffice"`; the circuit rules and the Tavily credits both, because both apps call out and
   spend credits. Pod and Job rules read kube-state-metrics in the namespaces `helper2-<environment>`. Each rule has a `severity` (`critical`: users get errors now; `warning`: look within
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
     10 min ([0006](0006-outbound-calls-and-resilience.md));
   - `Helper2DailyBudgetLow`: a public app's `*.budget.remaining` gauge below 20% of its cap for 5 min (the cap is
     the gauge's highest value of the last day; label `budget` = guide, tool_guide, search_translation);
     `Helper2BackofficeBudgetLow`: the same for the back office's budgets and credits (scout_enrichment, scout_gap,
     scout_classifier, scout_event_reader, examples_draft, scout_evidence_credits; the shared translation cap is the
     public rule's);
   - `Helper2LiveWebCreditsHigh`: more than 200 Tavily credits in a day, both apps added up;
   - `Helper2EmbeddingIndexerStalled`: no successful indexer batch for 30 min; `Helper2EmbeddingBacklogStuck`:
     backlog above 0 for 3 h on the replica that indexes, read over the last 20 min so a restart does not start the
     3 h again; `Helper2ReviewBacklog`: more than 50 open review items for
     a day;
   - `Helper2ScoutCollectorStale`: a collector's last success older than its run interval plus a day (2 days;
     `live-web-scout` 6, `course-scout` 8), over a 9-day lookback so a deploy does not lose the gauge; the discovery
     categories are left out;
   - `Helper2DiscoveryStale`: one alert per environment when any discovery category (`discovery-*`) has had no
     success for more than 8 days; the description lists them ([0013](../scout/0013-discovery.md));
   - `Helper2ScoutRunsFailing`: three or more collectors with a failed run in the last day, a cause they share;
   - `Helper2GuidePicksFromLowRanks`: more than 45% of the day's kept picks, and at least 3, from search rank 9+
     (`helper2.guide.picks{rank="9+"}`), the first sign of content poisoning ([0031](../guide/0031-guide-pipeline.md)).
     The usual share is about 30%: the guide chooses among 24 candidates and often keeps a known project that search
     ranks low;
   - `Helper2GuideFirstUsefulSlow`: the p95 of `helper2.guide.first_useful` above its 8 s target for 15 min, above
     3 questions in 15 min (item 8);
   - `Helper2BackupFailed` (critical): a `pg-backup` Job that started in the last 24 h failed;
     `Helper2BackupStale` (critical): no successful backup for 36 h;
     `Helper2PodRestarting`: more than 2 restarts of a container in an hour;
   - `Helper2ReplicasMissing`: the `helper2` Deployment has had fewer available replicas than wanted for 10 min;
     `Helper2NoReplica` (critical): none for 2 min, the site is down. Both read kube-state-metrics, not `up{}`:
     the scrape job's labels live outside this repository; `Helper2BackofficeDown`: no back office replica for
     15 min (the public site keeps answering, the Scout and the indexer stop);
   - `Helper2VolumeAlmostFull`: a PersistentVolumeClaim in a `helper2-*` namespace (Postgres data, backups, the
     Redis append-only file) under 20% free for 15 min; `Helper2VolumeFull` (critical): under 10% for 5 min.
   `promtool check rules` and `promtool test rules` (unit tests in `deploy/monitoring/tests/`) run in CI; chart-check
   renders the `PrometheusRule` once and validates it against its CRD schema.
7. **Retention.** Required of the observability stack, which lives outside this repository and must use these values:
   - logs (Loki: the app's stdout and the ingress-nginx access logs): deleted after 3 months (`retention_period: 2160h`,
     90 days), the period the privacy page states;
   - traces (Tempo): deleted after 3 months (`block_retention: 2160h`), never kept longer than the logs they link to;
   - metrics (Prometheus): 90 days (`--storage.tsdb.retention.time=90d`), at least 9 days for the Scout rule's
     lookback. Meters carry no personal data, so this bound is cost, not privacy.
   Database backups: 7 days on the `pg-backup` volume (`backup.retentionDays`); the encrypted off-site copy, 7 days
   by its bucket's lifecycle rule ([0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)).
8. **Guide latency target: time to the first useful event.** The first useful event is the first `retrieved` (the
   results the guide reads, with their count and sources) or `clarify` (the question back) on the stream
   ([0031](../guide/0031-guide-pipeline.md)): `retrieved` is the first step the page always shows, while
   `planned` is not shown and `understood` shows nothing for a plain suggestion, the most common question.
   `GuideStreamController` records `helper2.guide.first_useful{intent}` once per question, from the request to that
   event reaching the connection; it covers the plan call and the search. A cached or shared answer has no step
   events and is not timed; the end-to-end time stays in `helper2.guide.ask` and in the evaluation
   ([0046](../operations/0046-evaluation-guide-and-search.md)). Targets: **p50 3 s, p95 8 s**, initial targets to confirm with
   the first weeks of data. They come from the measured steps: planning 0.5–2.8 s with thinking off
   ([0008](0008-llm-integration.md)), a search of 2.0–4.2 s when Tavily answers live and less from its stored
   results, Tavily's 6 s timeout as the slow end. The p95 target has an alert; the p50 target is read on the dashboard.

### Code
- `backend/libs/platform/src/main/resources/helper2-shared.yaml`, `backend/apps/public/src/main/resources/application.yaml`, `backend/apps/backoffice/src/main/resources/application.yaml` —
  `management.*` (the `application` tag), `logging.structured`, tracing settings (`service.name`).
- `backend/libs/platform/src/main/java/ai/helper2/platform/observability/ObservabilityConfiguration.java` — actuator filter.
- `backend/libs/search/src/main/java/ai/helper2/search/config/MicrometerSearchMetrics.java`, `SearchConfiguration.java` — search meters, context executor.
- `backend/libs/platform/src/main/java/ai/helper2/platform/llm/ModelCallMetrics.java` — model call span, timer, tokens.
- `backend/apps/public/src/main/java/ai/helper2/guide/web/GuideStreamController.java` — `helper2.guide.first_useful`.
- `backend/apps/public/src/main/java/ai/helper2/guide/web/GuideLimits.java`, `scout/internal/ScoutConfiguration.java` (`RUN_THREADS`) —
  trace context on the guide's threads and on a Scout run started from the admin page.
- `backend/apps/public/src/test/java/ai/helper2/platform/observability/StructuredLogTraceIdIT.java` — trace and span ids in the JSON log lines.
- `backend/libs/platform/src/main/java/ai/helper2/platform/http/ObservedHttp.java` — observed source and embedding clients.
- `backend/libs/platform/src/main/java/ai/helper2/platform/llm/LlmConfiguration.java`,
  `backend/libs/search/src/main/java/ai/helper2/search/internal/TranslationConfiguration.java`,
  `backend/apps/backoffice/src/main/java/ai/helper2/sources/config/scout/CollectorsConfiguration.java` —
  translation and classifier budget gauges.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/usecase/IndexEmbeddings.java`, `ObservationQueue.java` — catalog meters.
- `deploy/grafana/helper2.json`, `helper2-backoffice.json` (Grafana dashboards); `deploy/helm/helper2/templates/app.yaml`,
  `backoffice.yaml` — scrape annotations, tracing env.
- `deploy/monitoring/helper2-rules.yaml` (alert rules), `deploy/monitoring/tests/` (promtool unit tests);
  `deploy/helm/helper2/templates/prometheusrule.yaml`, `files/helper2-rules.yaml` (symlink) — optional `PrometheusRule`.

## Alternatives considered
- Push metrics over OTLP — the cluster scrapes with Alloy; pushing would send a second copy.
- Logs over OTLP — stdout JSON is already collected; a second path adds cost.
- Sample traces lower — traffic is low; probes and scrapes are already dropped.
- Query text as a tag — high cardinality and personal data.
- Grafana-managed alerts — stored in Grafana's database, not reviewable or testable in the repository.
- Rules only as a `PrometheusRule` template — ties the chart to the Prometheus Operator; a plain rule file works
  with any Prometheus.
- A copy of the rules in the chart — two sources drift; the symlink and `.Files.Get` keep one.
- End-to-end p50 as the guide's latency target — it adds the choose call and the reason check, which the asker
  waits for while already seeing the results being read; the evaluation reports it.
- `understood` as the first useful event — earlier, but empty on the page for a plain suggestion.
- A `picked` event that shows the picks before the reason check — it does not move the first useful event and adds
  a step whose picks can still disappear; it pays only when `helper2.guide.step` shows the reason check dominates
  the answer time.

## Consequences
- A slow search shows per-source latency and outcome; a slow guide shows the model step and model.
- Logs, traces and meters share the trace id.
- A failing source, an open circuit, a spent budget, a stalled indexer or Scout, a failed backup or a poisoned
  pick raises an alert, and each alert says where to look first.
- One rule file serves a plain Prometheus and the operator; the unit tests pin each threshold.
- The guide's latency is held by one number the asker feels; the model step and the search behind a slow first
  event are in `helper2.llm.call` and `helper2.source.latency`.
- The rules look back over time windows, so a gauge kept by one replica (Scout, indexer) or a circuit open on any
  pod still raises its alert.

### Known limits
- Alert delivery is configured in the observability stack, not here.
- A collector disabled on purpose keeps alerting until its value is 9 days old.
- Histogram buckets add series per timer.

## Diagrams
[flow](../../diagrams/flow-0009-observability.svg) · [sequence](../../diagrams/sequence-0009-observability.svg)

## Backlog
- Decides: [F1.8](https://github.com/silvioincalza/helper2.ai/issues/27).
- Open issues that change it:
  - [S1.8.3](https://github.com/silvioincalza/helper2.ai/issues/174): Postgres and Redis exporters in the Helm chart and an alert on Redis evicted keys.
  - [S4.9.1](https://github.com/silvioincalza/helper2.ai/issues/285): the guide latency targets confirmed on prod data.
