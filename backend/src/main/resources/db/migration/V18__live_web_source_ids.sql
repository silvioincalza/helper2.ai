-- The web sources carry Helper2's own names (ADR 0037): live-web was tavily, live-web-scout was tavily-scout. The
-- search provider behind them is an adapter and stays out of ids, settings and pages; the Privacy page still names it.
-- source.id has no ON UPDATE CASCADE: copy the row under the new id, move the children, drop the old row. The
-- application rewrites name, homepage and terms_url from its own source descriptors at startup.
INSERT INTO source (id, name, mode, homepage, terms_url, attribution, enabled)
SELECT 'live-web', 'Live web (AI news)', mode, 'https://helper2.ai/sources', 'https://helper2.ai/terms', attribution, enabled
FROM source WHERE id = 'tavily'
ON CONFLICT (id) DO NOTHING;

INSERT INTO source (id, name, mode, homepage, terms_url, attribution, enabled)
SELECT 'live-web-scout', 'Live web scout (AI news)', mode, 'https://helper2.ai/sources', 'https://helper2.ai/terms', attribution, enabled
FROM source WHERE id = 'tavily-scout'
ON CONFLICT (id) DO NOTHING;

UPDATE source_record SET source_id = 'live-web'       WHERE source_id = 'tavily';
UPDATE source_record SET source_id = 'live-web-scout' WHERE source_id = 'tavily-scout';
UPDATE resource SET published_source_id = 'live-web'       WHERE published_source_id = 'tavily';
UPDATE resource SET published_source_id = 'live-web-scout' WHERE published_source_id = 'tavily-scout';
UPDATE resource SET updated_source_id = 'live-web'       WHERE updated_source_id = 'tavily';
UPDATE resource SET updated_source_id = 'live-web-scout' WHERE updated_source_id = 'tavily-scout';
UPDATE source_cursor SET source_id = 'live-web'       WHERE source_id = 'tavily'       AND NOT EXISTS (SELECT 1 FROM source_cursor WHERE source_id = 'live-web');
UPDATE source_cursor SET source_id = 'live-web-scout' WHERE source_id = 'tavily-scout' AND NOT EXISTS (SELECT 1 FROM source_cursor WHERE source_id = 'live-web-scout');
UPDATE scout_run SET source_id = 'live-web'       WHERE source_id = 'tavily';
UPDATE scout_run SET source_id = 'live-web-scout' WHERE source_id = 'tavily-scout';

DELETE FROM source WHERE id IN ('tavily', 'tavily-scout');

-- Stored live web result sets (ADR 0037): same table, Helper2's name.
ALTER TABLE tavily_query_result RENAME TO live_web_query_result;
ALTER INDEX tavily_query_result_fetched_idx RENAME TO live_web_query_result_fetched_idx;
