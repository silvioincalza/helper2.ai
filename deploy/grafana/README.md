# Grafana dashboard

`helper2.json`: one dashboard (uid `helper2`) for the app, with an `environment` variable (prod, staging).
Rows: overview, HTTP, search and sources, AI guide, Scout (empty on staging, where Scout is off), embeddings
(empty without `H2_NVIDIA_API_KEY`), runtime, pod, logs.
The pod row reads the cluster's cAdvisor and kube-state-metrics series, and the logs row reads Loki (datasource uid
`loki`); both select the namespace `helper2-$environment`, and the logs row adds a `level` variable.
Meters and tags: [ADR 0026](../../docs/adr/operations/0026-observability.md).

The datasources are referenced by uid (`prometheus`, `loki`): provision them with these uids. There are no
`${DS_*}` inputs to rewrite.

This file is the single source. A Grafana file provider overwrites any change saved in the UI or through the API
within a minute, so change the dashboard here, then copy it to the provider's folder.

Load it, either:

- **File provisioning**: copy the file into the folder of a Grafana dashboard provider (for example
  `/var/lib/grafana/dashboards`); Grafana picks it up within a minute.
- **API**, only for a Grafana without that file provider (it reverts the upload otherwise), with a
  service-account token that can write dashboards:

  ```bash
  jq '{dashboard: ., overwrite: true}' deploy/grafana/helper2.json \
    | curl -fsS -X POST -H "Authorization: Bearer $GRAFANA_TOKEN" -H 'Content-Type: application/json' \
        --data @- "$GRAFANA_URL/api/dashboards/db"
  ```

Latency panels for HTTP, `helper2.search.latency`, `helper2.source.latency`, model calls and Scout runs take
`histogram_quantile` over the `_bucket` series (`management.metrics.distribution.percentiles-histogram` in
`application.yaml`), so they are percentiles across all pods. "Time to first useful event by intent" does the same
with `helper2.guide.first_useful`, whose buckets (with exact ones at the 3 s and 8 s targets) are set on the timer.
`helper2.guide.ask` has no buckets: its panel shows the mean and Micrometer's rolling max.
Budget panels (guide, translation, enrichment, classifier) show the calls left under each daily cap;
"Rate-limit decisions in memory" (`helper2.rate-limit.fallback`) should stay at 0 while Redis is up.
Gauges with a base unit get it as a suffix in Prometheus: `helper2.embedding.last.success` is
`helper2_embedding_last_success_seconds`. Outbound calls (`http_client_requests_*`) reach Prometheus only if the
scraper's metric allow-list, if it has one, includes the `http_client_` prefix.
