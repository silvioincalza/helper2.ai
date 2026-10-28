-- npm's search API `date` is the latest publish, not the creation date (ADR 0032). Rows recorded before NpmSource
-- reported it as `updated` still carry it as published_at, so "born last month" would list old, republished
-- packages. Move it to updated_at where that is not already later, and clear published_at.
UPDATE resource
SET updated_at = GREATEST(coalesce(updated_at, published_at), published_at),
    published_at = NULL,
    published_precision = 'UNKNOWN',
    published_source_id = NULL
WHERE published_source_id = 'npm';

UPDATE source_record
SET source_updated_at = GREATEST(coalesce(source_updated_at, source_published_at), source_published_at),
    source_published_at = NULL
WHERE source_id = 'npm' AND source_published_at IS NOT NULL;
