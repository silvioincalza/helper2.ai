# Grafana dashboards

Two dashboards, one per app, each with an `environment` variable (prod, staging) and a link to the other:

- `helper2.json` (uid `helper2`): the public app, `application="helper2"`. Rows: overview, HTTP, search and sources,
  AI guide, runtime, pod (container `app`), logs.
- `helper2-backoffice.json` (uid `helper2-backoffice`): the back office, `application="helper2-backoffice"`. Rows:
  overview, Scout (runs, failures, items per collector), enrichment and budgets (the daily model budgets, the
  evidence step's page reads, the Scout's Tavily credits), embedding indexer (empty without `H2_NVIDIA_API_KEY`),
  admin and runtime, pod (container `backoffice`), logs. The Scout rows are empty while the Scout is off
  (`backoffice.scoutEnabled`; off on staging except for a rehearsal).

The pod rows read the cluster's cAdvisor and kube-state-metrics series, and the logs rows read Loki (datasource uid
`loki`); both select the namespace `helper2-$environment`, and the logs rows add a `level` variable.
Meters and tags: [ADR 0009](../../docs/adr/foundation/0009-observability.md).

The datasources are referenced by uid (`prometheus`, `loki`): provision them with these uids. There are no
`${DS_*}` inputs to rewrite.

These files are the single source. A Grafana file provider overwrites any change saved in the UI or through the API
within a minute, so change a dashboard here, then copy it to the provider's folder.

Load them, either:

- **File provisioning**: copy both files into the folder of a Grafana dashboard provider (for example
  `/var/lib/grafana/dashboards`); Grafana picks it up within a minute.
- **API**, only for a Grafana without that file provider (it reverts the upload otherwise), with a
  service-account token that can write dashboards:

  ```bash
  for f in deploy/grafana/helper2.json deploy/grafana/helper2-backoffice.json; do
    jq '{dashboard: ., overwrite: true}' "$f" \
      | curl -fsS -X POST -H "Authorization: Bearer $GRAFANA_TOKEN" -H 'Content-Type: application/json' \
          --data @- "$GRAFANA_URL/api/dashboards/db"
  done
  ```

Latency panels for HTTP, `helper2.search.latency`, `helper2.source.latency`, model calls and Scout runs take
`histogram_quantile` over the `_bucket` series (`management.metrics.distribution.percentiles-histogram` in
`application.yaml`; set on the timer for `helper2.source.latency`), so they are percentiles across all pods.
"Source latency p95" sums the outcomes of each source: a skipped source or a fresh cache hit records no latency,
a failed or timed-out call does. The same p95 is the recording rule
`environment_source:helper2_source_latency_seconds:p95_5m` (`deploy/monitoring/helper2-rules.yaml`). "Time to first useful event by intent" does the same
with `helper2.guide.first_useful`, whose buckets (with exact ones at the 3 s and 8 s targets) are set on the timer.
`helper2.guide.ask` has no buckets: its panel shows the mean and Micrometer's rolling max.
Budget panels (guide and translation on `helper2`; enrichment, gap rounds, classifier, event reader, example drafts
and the evidence step's page reads on `helper2-backoffice`) show what is left under each daily cap. Both apps register
the embedding gauges, the review count and the example-draft budget, but only the back office runs that work: the
public app's copies do not move, so read them on `helper2-backoffice`;
"Rate-limit decisions in memory" (`helper2.rate-limit.fallback`) should stay at 0 while Redis is up.
Gauges with a base unit get it as a suffix in Prometheus: `helper2.embedding.last.success` is
`helper2_embedding_last_success_seconds`. Outbound calls (`http_client_requests_*`) reach Prometheus only if the
scraper's metric allow-list, if it has one, includes the `http_client_` prefix.
