-- The enrichment backlog (the Scout's next resources, the back office queue and its count) reads only resources that
-- may still need a run, newest first. Measured on synthetic data (250 000 resources): the next 20 took 1.4 s with a
-- sort over the whole table, and take under 1 ms with this index and the query in two parts; the count goes from
-- 1.3 s to 0.65 s. At 60 000 resources: 250 ms to under 1 ms, and 200 ms to 115 ms.
CREATE INDEX resource_enrichment_backlog_idx ON resource (first_discovered_at DESC, id DESC)
    WHERE enrichment_status IN ('NOT_RUN', 'LLM_UNAVAILABLE', 'REJECTED')
      AND data_origin IN ('INDEXED', 'OBSERVED', 'CURATED');
