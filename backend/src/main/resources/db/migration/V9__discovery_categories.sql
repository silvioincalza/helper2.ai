-- Tavily discovery is one collector per category now (ADR 0051): discovery-<category id> instead of tavily-discovery.
-- Each record of the old collector moves to the collector of its first tag that is a category (its tags are the
-- categories it belongs to), or to discovery-ai-assistants when none is; its extra gets the categories. Open resource
-- candidates name their new collector the same way, so they can still be accepted. The old source goes with its
-- cursor and run history (ON DELETE CASCADE): each category starts its own first pass.
CREATE TEMP TABLE discovery_category (id text PRIMARY KEY) ON COMMIT DROP;
INSERT INTO discovery_category (id) VALUES
    ('prompt-libraries'), ('skills-and-gpts'), ('ai-assistants'), ('writing'), ('translation'),
    ('meetings-and-transcription'), ('documents-and-pdf'), ('research-and-papers'), ('ai-search'),
    ('browser-extensions'), ('education-and-tutors'), ('health'), ('image-generation'), ('video-generation'),
    ('voice-and-audio'), ('design-and-presentations'), ('customer-support'), ('voice-agents'), ('data-and-analytics'),
    ('no-code'), ('marketing-and-seo'), ('legal'), ('coding'), ('agent-platforms'), ('llm-frameworks'),
    ('rag-and-vector-databases'), ('llmops-and-evaluation'), ('ai-security'), ('fine-tuning'), ('model-hosting'),
    ('ai-cloud-and-paas'), ('ai-gateways'), ('self-hosted'), ('local-ai');

-- The categories of a list of tags (a jsonb array), in their order.
CREATE OR REPLACE FUNCTION pg_temp.h2_categories(tags jsonb) RETURNS text[] LANGUAGE sql STABLE AS $$
    SELECT coalesce(array_agg(t.value ORDER BY t.n), '{}')
    FROM jsonb_array_elements_text(CASE WHEN jsonb_typeof(tags) = 'array' THEN tags ELSE '[]'::jsonb END)
             WITH ORDINALITY AS t(value, n)
    WHERE t.value IN (SELECT id FROM discovery_category)
$$;

CREATE TEMP TABLE discovery_move ON COMMIT DROP AS
SELECT sr.id,
       sr.resource_id,
       'discovery-' || coalesce((pg_temp.h2_categories(sr.extra -> 'tags'))[1], 'ai-assistants') AS target,
       pg_temp.h2_categories(sr.extra -> 'tags') AS categories
FROM source_record sr
WHERE sr.source_id = 'tavily-discovery';

CREATE TEMP TABLE discovery_candidate ON COMMIT DROP AS
SELECT i.id,
       'discovery-' || coalesce((pg_temp.h2_categories(CAST(i.details AS jsonb) -> 'tags'))[1], 'ai-assistants')
           AS target
FROM review_item i
WHERE i.kind = 'RESOURCE_CANDIDATE'
  AND CASE WHEN i.details LIKE '{%' THEN CAST(i.details AS jsonb) ->> 'sourceId' = 'tavily-discovery' ELSE false END;

-- The new sources, until the application registers them with their names at startup.
INSERT INTO source (id, name, mode, homepage, terms_url, attribution)
SELECT DISTINCT target, 'Tavily discovery', 'INDEXED', 'https://tavily.com', 'https://tavily.com/terms',
       'Web results via the Tavily Search API; each links to the original publisher.'
FROM (SELECT target FROM discovery_move UNION SELECT target FROM discovery_candidate) t
ON CONFLICT (id) DO NOTHING;

UPDATE source_record sr
SET source_id = m.target,
    extra = sr.extra || jsonb_build_object('categories', to_jsonb(m.categories))
FROM discovery_move m
WHERE sr.id = m.id;

UPDATE resource r SET published_source_id = m.target
FROM discovery_move m
WHERE r.id = m.resource_id AND r.published_source_id = 'tavily-discovery';

UPDATE resource r SET updated_source_id = m.target
FROM discovery_move m
WHERE r.id = m.resource_id AND r.updated_source_id = 'tavily-discovery';

UPDATE review_item i
SET details = CAST(jsonb_set(CAST(i.details AS jsonb), '{sourceId}', to_jsonb(c.target)) AS text)
FROM discovery_candidate c
WHERE i.id = c.id;

DELETE FROM source WHERE id = 'tavily-discovery';
