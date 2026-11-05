-- ADR 0042: GET /sources counts per source the resources first discovered in the last 7 days, on every Home load.
-- Without it the count reads the whole resource table (60 000 rows: 34 ms median); with it only the week's rows (19 ms).
CREATE INDEX resource_first_discovered_idx ON resource (first_discovered_at);
