CREATE INDEX IF NOT EXISTS resource_enrichment_value_idx ON resource (popularity DESC, first_discovered_at DESC, id DESC)
    WHERE enrichment_status IN ('NOT_RUN', 'LLM_UNAVAILABLE', 'REJECTED')
      AND data_origin IN ('INDEXED', 'OBSERVED', 'CURATED');

ALTER TABLE relationship DROP CONSTRAINT IF EXISTS relationship_target_kind_check;
ALTER TABLE relationship ADD CONSTRAINT relationship_target_kind_check CHECK (target_kind IN ('VENDOR', 'SERVICE'));

CREATE TABLE IF NOT EXISTS known_target (
    id       uuid PRIMARY KEY,
    kind     text        NOT NULL CHECK (kind IN ('VENDOR', 'SERVICE')),
    name     text        NOT NULL CHECK (length(name) BETWEEN 2 AND 60),
    added_by text        NOT NULL,
    added_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS known_target_name (
    name_key  text PRIMARY KEY CHECK (length(name_key) BETWEEN 2 AND 60),
    target_id uuid NOT NULL REFERENCES known_target (id) ON DELETE CASCADE,
    name      text NOT NULL CHECK (length(name) BETWEEN 2 AND 60)
);
CREATE INDEX IF NOT EXISTS known_target_name_target_idx ON known_target_name (target_id);

ALTER TABLE known_target ADD COLUMN IF NOT EXISTS added_by text NOT NULL DEFAULT 'migration';
ALTER TABLE known_target ALTER COLUMN added_by DROP DEFAULT;
ALTER TABLE known_target ADD COLUMN IF NOT EXISTS added_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE known_target_name ADD COLUMN IF NOT EXISTS name text CHECK (length(name) BETWEEN 2 AND 60);
UPDATE known_target_name n
SET name = coalesce((SELECT t.name FROM known_target t WHERE t.id = n.target_id AND lower(t.name) = n.name_key),
                    n.name_key)
WHERE n.name IS NULL;
ALTER TABLE known_target_name ALTER COLUMN name SET NOT NULL;

WITH seed(name, other_names) AS (VALUES
    ('OpenAI', ARRAY['Open AI']::text[]),
    ('Anthropic', ARRAY[]::text[]),
    ('Google', ARRAY['Google AI', 'Alphabet']::text[]),
    ('Google DeepMind', ARRAY['DeepMind']::text[]),
    ('Microsoft', ARRAY[]::text[]),
    ('Meta', ARRAY['Meta AI', 'Facebook AI']::text[]),
    ('Amazon', ARRAY[]::text[]),
    ('AWS', ARRAY['Amazon Web Services']::text[]),
    ('Apple', ARRAY[]::text[]),
    ('NVIDIA', ARRAY[]::text[]),
    ('IBM', ARRAY[]::text[]),
    ('Mistral AI', ARRAY[]::text[]),
    ('Cohere', ARRAY[]::text[]),
    ('Hugging Face', ARRAY['HuggingFace']::text[]),
    ('xAI', ARRAY[]::text[]),
    ('DeepSeek', ARRAY[]::text[]),
    ('Alibaba', ARRAY['Alibaba Cloud']::text[]),
    ('Baidu', ARRAY[]::text[]),
    ('Tencent', ARRAY[]::text[]),
    ('ByteDance', ARRAY[]::text[]),
    ('Zhipu AI', ARRAY['Z.ai']::text[]),
    ('Moonshot AI', ARRAY[]::text[]),
    ('MiniMax', ARRAY[]::text[]),
    ('Stability AI', ARRAY[]::text[]),
    ('Together AI', ARRAY[]::text[]),
    ('Fireworks AI', ARRAY[]::text[]),
    ('Nous Research', ARRAY[]::text[]),
    ('Cloudflare', ARRAY[]::text[]),
    ('Databricks', ARRAY[]::text[]),
    ('Salesforce', ARRAY[]::text[]),
    ('Oracle', ARRAY[]::text[])),
added AS (
    INSERT INTO known_target (id, kind, name, added_by)
    SELECT gen_random_uuid(), 'VENDOR', s.name, 'migration' FROM seed s
    WHERE NOT EXISTS (SELECT 1 FROM known_target)
    RETURNING id, name)
INSERT INTO known_target_name (name_key, target_id, name)
SELECT lower(n.name), a.id, n.name
FROM added a JOIN seed s ON s.name = a.name
     CROSS JOIN LATERAL unnest(ARRAY[s.name] || s.other_names) AS n(name);
REVOKE SELECT ON known_target, known_target_name FROM h2_public;

ALTER TABLE admin_audit DROP CONSTRAINT IF EXISTS admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check CHECK (action IN
    ('RESOLVE_REVIEW_ITEM', 'START_SCOUT_RUN', 'ADD_HOME_EXAMPLE', 'CHANGE_HOME_EXAMPLE', 'REMOVE_HOME_EXAMPLE',
     'DRAFT_HOME_EXAMPLES', 'RECHECK_ENRICHMENT', 'REQUEUE_ENRICHMENT', 'SKIP_ENRICHMENT', 'PRIORITISE_ENRICHMENT',
     'BACKFILL_CATEGORIES', 'CHANGE_SETTING', 'ADD_DISCOVERY_HOST', 'REMOVE_DISCOVERY_HOST',
     'CHANGE_RELATIONSHIP_TARGET', 'ADD_RESOURCE', 'EDIT_CANDIDATE', 'SET_FIELD', 'DROP_FIELD',
     'ADD_KNOWN_TARGET', 'EDIT_KNOWN_TARGET', 'REMOVE_KNOWN_TARGET'));

ALTER TABLE known_target ADD COLUMN IF NOT EXISTS origin text NOT NULL DEFAULT 'CURATED'
    CHECK (origin IN ('CURATED', 'DISCOVERED'));

ALTER TABLE review_item DROP CONSTRAINT IF EXISTS review_item_kind_check;
ALTER TABLE review_item ADD CONSTRAINT review_item_kind_check CHECK (kind IN
    ('ENRICHMENT_VALIDATION','RELATIONSHIP_CANDIDATE','USER_REPORT','COURSE_CANDIDATE','EXAMPLE_CANDIDATE',
     'RESOURCE_CANDIDATE','KNOWN_TARGET_CANDIDATE'));
ALTER TABLE review_item DROP CONSTRAINT IF EXISTS review_item_check1;
ALTER TABLE review_item ADD CONSTRAINT review_item_check1
    CHECK (details IS NULL OR kind IN ('RESOURCE_CANDIDATE', 'EXAMPLE_CANDIDATE', 'KNOWN_TARGET_CANDIDATE'));
ALTER TABLE review_item DROP CONSTRAINT IF EXISTS review_item_check2;
ALTER TABLE review_item ADD CONSTRAINT review_item_check2
    CHECK (note IS NULL OR kind NOT IN ('RESOURCE_CANDIDATE', 'EXAMPLE_CANDIDATE', 'KNOWN_TARGET_CANDIDATE'));
CREATE UNIQUE INDEX IF NOT EXISTS review_item_known_target_idx ON review_item ((details ->> 'key'))
    WHERE kind = 'KNOWN_TARGET_CANDIDATE';

ALTER TABLE candidate_decision DROP CONSTRAINT IF EXISTS candidate_decision_kind_check;
ALTER TABLE candidate_decision ADD CONSTRAINT candidate_decision_kind_check CHECK (kind IN
    ('RESOURCE_CANDIDATE','EXAMPLE_CANDIDATE','COURSE_CANDIDATE','KNOWN_TARGET_CANDIDATE'));

ALTER TABLE relationship ADD COLUMN IF NOT EXISTS stated_target_kind text
    CHECK (stated_target_kind IN ('PRODUCT', 'VENDOR', 'SERVICE'));

UPDATE home_example
SET label = 'Summarize research papers', query = 'summarize research papers', version = version + 1,
    updated_at = now(), check_outcome = 'PENDING', check_best_rank = NULL, check_result_count = NULL, checked_at = NULL
WHERE origin = 'BUILT_IN' AND locale = 'en'
  AND label = 'Summarise research papers' AND query = 'summarise research papers'
  AND NOT EXISTS (SELECT 1 FROM home_example other
                  WHERE other.locale = 'en'
                    AND (lower(other.label) = 'summarize research papers'
                         OR lower(other.query) = 'summarize research papers'));
