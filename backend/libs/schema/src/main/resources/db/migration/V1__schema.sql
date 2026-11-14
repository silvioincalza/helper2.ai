DO $$
DECLARE
    db pg_database%ROWTYPE;
BEGIN
    SELECT * INTO db FROM pg_database WHERE datname = current_database();
    IF pg_encoding_to_char(db.encoding) <> 'UTF8'
       OR lower(replace(db.datctype, '-', '')) <> 'en_us.utf8'
       OR lower(replace(db.datcollate, '-', '')) <> 'en_us.utf8' THEN
        RAISE EXCEPTION 'the database must be UTF8 with LC_CTYPE and LC_COLLATE en_US.utf8, not % with %/%',
            pg_encoding_to_char(db.encoding), db.datctype, db.datcollate;
    END IF;
END
$$;

CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;

CREATE FUNCTION h2_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;

CREATE FUNCTION h2_ts_config(code text) RETURNS regconfig
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    RETURN CASE code
        WHEN 'ar' THEN 'pg_catalog.arabic'::regconfig     WHEN 'hy' THEN 'pg_catalog.armenian'::regconfig
        WHEN 'eu' THEN 'pg_catalog.basque'::regconfig     WHEN 'ca' THEN 'pg_catalog.catalan'::regconfig
        WHEN 'da' THEN 'pg_catalog.danish'::regconfig     WHEN 'nl' THEN 'pg_catalog.dutch'::regconfig
        WHEN 'en' THEN 'pg_catalog.english'::regconfig    WHEN 'fi' THEN 'pg_catalog.finnish'::regconfig
        WHEN 'fr' THEN 'pg_catalog.french'::regconfig     WHEN 'de' THEN 'pg_catalog.german'::regconfig
        WHEN 'el' THEN 'pg_catalog.greek'::regconfig      WHEN 'hi' THEN 'pg_catalog.hindi'::regconfig
        WHEN 'hu' THEN 'pg_catalog.hungarian'::regconfig  WHEN 'id' THEN 'pg_catalog.indonesian'::regconfig
        WHEN 'ga' THEN 'pg_catalog.irish'::regconfig      WHEN 'it' THEN 'pg_catalog.italian'::regconfig
        WHEN 'lt' THEN 'pg_catalog.lithuanian'::regconfig WHEN 'no' THEN 'pg_catalog.norwegian'::regconfig
        WHEN 'pt' THEN 'pg_catalog.portuguese'::regconfig WHEN 'ro' THEN 'pg_catalog.romanian'::regconfig
        WHEN 'ru' THEN 'pg_catalog.russian'::regconfig    WHEN 'sr' THEN 'pg_catalog.serbian'::regconfig
        WHEN 'es' THEN 'pg_catalog.spanish'::regconfig    WHEN 'sv' THEN 'pg_catalog.swedish'::regconfig
        WHEN 'ta' THEN 'pg_catalog.tamil'::regconfig      WHEN 'tr' THEN 'pg_catalog.turkish'::regconfig
        ELSE 'pg_catalog.simple'::regconfig
    END;

CREATE FUNCTION h2_name_words(t text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    RETURN translate(t, '/._@:', '     ');

CREATE FUNCTION h2_append_only() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = public, pg_catalog
    AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE FUNCTION h2_audit_kept() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = public, pg_catalog
    AS $$
BEGIN
    IF OLD.at > now() - interval '729 days' THEN
        RAISE EXCEPTION 'admin_audit keeps a row for its retention period: % is younger', OLD.id;
    END IF;
    RETURN OLD;
END;
$$;

CREATE TABLE ai_cache (
    cache_name text        NOT NULL,
    key_hash   bytea       NOT NULL,
    value      text        NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (cache_name, key_hash)
);
CREATE INDEX ai_cache_expires_idx ON ai_cache (expires_at);

CREATE TABLE shedlock (
    name       varchar(64)  PRIMARY KEY,
    lock_until timestamp    NOT NULL,
    locked_at  timestamp    NOT NULL,
    locked_by  varchar(255) NOT NULL
);

CREATE TABLE app_user (
    id                uuid PRIMARY KEY,
    provider          text        NOT NULL,
    subject           text        NOT NULL,
    display_name      text,
    last_signed_in_at timestamptz NOT NULL DEFAULT now(),
    radar_motion      boolean     NOT NULL DEFAULT true,
    UNIQUE (provider, subject)
);
CREATE INDEX app_user_last_signed_in_idx ON app_user (last_signed_in_at);

CREATE TABLE bookmark (
    user_id     uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    resource_id uuid        NOT NULL,
    title       text        NOT NULL,
    url         text        NOT NULL,
    type        text,
    saved_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, resource_id)
);

CREATE TABLE admin_audit (
    id           uuid PRIMARY KEY,
    admin_id     text        NOT NULL,
    action       text        NOT NULL CHECK (action IN
        ('RESOLVE_REVIEW_ITEM', 'START_SCOUT_RUN', 'ADD_HOME_EXAMPLE', 'CHANGE_HOME_EXAMPLE', 'REMOVE_HOME_EXAMPLE',
         'DRAFT_HOME_EXAMPLES', 'RECHECK_ENRICHMENT', 'REQUEUE_ENRICHMENT', 'SKIP_ENRICHMENT', 'PRIORITISE_ENRICHMENT',
         'BACKFILL_CATEGORIES', 'CHANGE_SETTING', 'ADD_DISCOVERY_HOST', 'REMOVE_DISCOVERY_HOST',
         'CHANGE_RELATIONSHIP_TARGET', 'ADD_RESOURCE', 'EDIT_CANDIDATE', 'SET_FIELD', 'DROP_FIELD',
         'ADD_KNOWN_TARGET', 'EDIT_KNOWN_TARGET', 'REMOVE_KNOWN_TARGET')),
    target_id    text,
    at           timestamptz NOT NULL DEFAULT now(),
    outcome      text        NOT NULL,
    before_value text CHECK (before_value IS NULL OR length(before_value) <= 100),
    after_value  text CHECK (after_value IS NULL OR length(after_value) <= 100),
    reason       text CHECK (reason IS NULL OR length(reason) <= 500)
);
CREATE INDEX admin_audit_at_idx ON admin_audit (at DESC);

CREATE TRIGGER admin_audit_append_only BEFORE UPDATE ON admin_audit
    FOR EACH ROW EXECUTE FUNCTION h2_append_only();
CREATE TRIGGER admin_audit_kept BEFORE DELETE ON admin_audit
    FOR EACH ROW EXECUTE FUNCTION h2_audit_kept();
CREATE TRIGGER admin_audit_no_truncate BEFORE TRUNCATE ON admin_audit
    FOR EACH STATEMENT EXECUTE FUNCTION h2_append_only();
ALTER TABLE admin_audit
    ENABLE ALWAYS TRIGGER admin_audit_append_only,
    ENABLE ALWAYS TRIGGER admin_audit_kept,
    ENABLE ALWAYS TRIGGER admin_audit_no_truncate;

CREATE TABLE source (
    id              text PRIMARY KEY CHECK (id ~ '^[a-z0-9-]{2,40}$'),
    mode            text        NOT NULL CHECK (mode IN ('LIVE', 'INDEXED', 'CURATED', 'DEMO')),
    last_outcome    text CHECK (last_outcome IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'RATE_LIMITED', 'SKIPPED')),
    last_checked_at timestamptz
);

CREATE TABLE resource (
    id                   uuid PRIMARY KEY,
    canonical_url        text        NOT NULL UNIQUE,
    url                  text        NOT NULL,
    title                text        NOT NULL CHECK (length(title) BETWEEN 1 AND 300),
    description          text CHECK (length(description) <= 4000),
    description_origin   text        NOT NULL CHECK (description_origin IN ('SOURCE_PROVIDED','AI_EXTRACTED','AI_SUMMARY','CURATED','COMMUNITY','DEMO')),
    language             text        NOT NULL DEFAULT 'en' CHECK (language IN
        ('simple','ar','hy','eu','ca','da','nl','en','fi','fr','de','el','hi','hu','id','ga','it','lt','no','pt','ro',
         'ru','sr','es','sv','ta','tr')),
    type                 text        NOT NULL CHECK (type IN
        ('PRODUCT','MODEL','AGENT','PROMPT','SKILL','MCP_SERVER','LIBRARY','DATASET','ARTICLE','PAPER','NEWS','EVENT','COURSE','OTHER')),
    audience             text        NOT NULL CHECK (audience IN ('USE_AI','BUILD_WITH_AI','BOTH','UNKNOWN')),
    tech_level           text CHECK (tech_level IN ('BEGINNER','INTERMEDIATE','ADVANCED')),
    license              text,
    pricing              text CHECK (pricing IN ('FREE','FREEMIUM','PAID','OPEN_SOURCE_SELF_HOSTED')),
    open_source          boolean,
    published_at         timestamptz,
    published_precision  text        NOT NULL DEFAULT 'UNKNOWN' CHECK (published_precision IN ('INSTANT','DAY','UNKNOWN')),
    published_source_id  text REFERENCES source (id) ON DELETE RESTRICT,
    updated_at           timestamptz,
    updated_source_id    text REFERENCES source (id) ON DELETE RESTRICT,
    event_starts_at      timestamptz,
    event_ends_at        timestamptz,
    first_discovered_at  timestamptz NOT NULL DEFAULT now(),
    last_seen_at         timestamptz NOT NULL DEFAULT now(),
    data_origin          text        NOT NULL CHECK (data_origin IN ('OBSERVED','INDEXED','CURATED','DEMO')),
    enrichment_status    text        NOT NULL DEFAULT 'NOT_RUN' CHECK (enrichment_status IN ('NOT_RUN','VALIDATED','PARTIAL','REJECTED','LLM_UNAVAILABLE')),
    popularity           numeric     NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    modified_at          timestamptz NOT NULL DEFAULT now(),
    package_terms        text CHECK (length(package_terms) <= 2000),
    CHECK ((published_at IS NULL) = (published_precision = 'UNKNOWN')),
    search_vector tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', h2_unaccent(translate(coalesce(title, ''), '/', ' '))), 'A') ||
        setweight(to_tsvector('simple', h2_unaccent(h2_name_words(coalesce(title, '')))), 'A') ||
        setweight(to_tsvector('english', h2_unaccent(translate(coalesce(title, ''), '/', ' ') || ' ' || coalesce(description, ''))), 'B') ||
        CASE WHEN language IN ('en', 'simple') THEN ''::tsvector
             ELSE setweight(to_tsvector(h2_ts_config(language),
                      h2_unaccent(translate(coalesce(title, ''), '/', ' ') || ' ' || coalesce(description, ''))), 'B')
        END ||
        setweight(to_tsvector('simple', h2_unaccent(coalesce(package_terms, ''))), 'B') ||
        setweight(to_tsvector('english', h2_unaccent(coalesce(package_terms, ''))), 'B')
    ) STORED,
    enrichment_rerun_requested_at timestamptz,
    curated_fields       text[]      NOT NULL DEFAULT '{}'
        CHECK (curated_fields <@ ARRAY['type', 'pricing', 'license', 'openSource', 'techLevel', 'audience']::text[])
);
CREATE INDEX resource_search_idx ON resource USING gin (search_vector);
CREATE INDEX resource_title_trgm_idx ON resource USING gin (lower(title) gin_trgm_ops);
CREATE INDEX resource_type_idx ON resource (type);
CREATE INDEX resource_updated_idx ON resource (updated_at DESC NULLS LAST);
CREATE INDEX resource_published_idx ON resource (published_at DESC) WHERE published_at IS NOT NULL;
CREATE INDEX resource_origin_idx ON resource (data_origin);
CREATE INDEX resource_event_idx ON resource (event_starts_at) WHERE type = 'EVENT' AND event_starts_at IS NOT NULL;
CREATE INDEX resource_observed_seen_idx ON resource (last_seen_at) WHERE data_origin = 'OBSERVED';
CREATE INDEX resource_first_discovered_idx ON resource (first_discovered_at);
CREATE INDEX resource_enrichment_backlog_idx ON resource (first_discovered_at DESC, id DESC)
    WHERE enrichment_status IN ('NOT_RUN', 'LLM_UNAVAILABLE', 'REJECTED')
      AND data_origin IN ('INDEXED', 'OBSERVED', 'CURATED');
CREATE INDEX resource_enrichment_value_idx ON resource (popularity DESC, first_discovered_at DESC, id DESC)
    WHERE enrichment_status IN ('NOT_RUN', 'LLM_UNAVAILABLE', 'REJECTED')
      AND data_origin IN ('INDEXED', 'OBSERVED', 'CURATED');

CREATE TABLE resource_label (
    resource_id uuid NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    kind        text NOT NULL CHECK (kind IN ('TAG', 'USE_CASE', 'PLATFORM', 'CATEGORY')),
    value       text NOT NULL CHECK (length(value) BETWEEN 1 AND 60),
    PRIMARY KEY (resource_id, kind, value)
);
CREATE INDEX resource_label_value_idx ON resource_label (kind, value);

CREATE TABLE source_record (
    id                     uuid PRIMARY KEY,
    source_id              text        NOT NULL REFERENCES source (id) ON DELETE RESTRICT,
    external_id            text        NOT NULL,
    resource_id            uuid        NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    source_url             text        NOT NULL,
    canonical_url          text        NOT NULL,
    repository_url         text,
    title                  text        NOT NULL,
    description            text,
    type                   text        NOT NULL,
    license                text,
    source_published_at    timestamptz,
    source_updated_at      timestamptz,
    source_event_starts_at timestamptz,
    source_event_ends_at   timestamptz,
    retrieved_at           timestamptz NOT NULL,
    fingerprint            text        NOT NULL,
    extra                  jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at             timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_id, external_id)
);
CREATE INDEX source_record_resource_idx ON source_record (resource_id);
CREATE INDEX source_record_identity_idx
    ON source_record (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, canonical_url));

CREATE FUNCTION h2_resource_popularity(rid uuid) RETURNS numeric
    LANGUAGE sql STABLE PARALLEL SAFE
    RETURN coalesce((
        SELECT max(least(1.0, ln(1 + (p->>'value')::numeric) / ln(1 + CASE p->>'kind'
                 WHEN 'GITHUB_STARS' THEN 50000 WHEN 'HF_LIKES' THEN 5000
                 WHEN 'HF_DOWNLOADS' THEN 5000000 WHEN 'NPM_DOWNLOADS_WEEKLY' THEN 1000000 END)))
        FROM source_record sr, jsonb_array_elements(coalesce(sr.extra -> 'popularity', '[]'::jsonb)) p
        WHERE sr.resource_id = rid
          AND p->>'kind' IN ('GITHUB_STARS', 'HF_LIKES', 'HF_DOWNLOADS', 'NPM_DOWNLOADS_WEEKLY')
          AND (p->>'value')::numeric > 0
    ), 0);

CREATE FUNCTION h2_popularity_values(extra jsonb) RETURNS jsonb
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    RETURN (
        SELECT coalesce(jsonb_agg(jsonb_build_array(p->>'kind', p->'value') ORDER BY p->>'kind', p->>'value'), '[]'::jsonb)
        FROM jsonb_array_elements(coalesce(extra -> 'popularity', '[]'::jsonb)) p
    );

CREATE FUNCTION h2_refresh_popularity() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = public, pg_catalog
    AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.resource_id IS NOT DISTINCT FROM OLD.resource_id
       AND h2_popularity_values(NEW.extra) = h2_popularity_values(OLD.extra) THEN
        RETURN NULL;
    END IF;
    IF TG_OP IN ('UPDATE', 'DELETE') AND OLD.resource_id IS NOT NULL THEN
        UPDATE resource SET popularity = h2_resource_popularity(OLD.resource_id)
            WHERE id = OLD.resource_id AND popularity IS DISTINCT FROM h2_resource_popularity(OLD.resource_id);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') AND NEW.resource_id IS NOT NULL
       AND (TG_OP = 'INSERT' OR NEW.resource_id IS DISTINCT FROM OLD.resource_id) THEN
        UPDATE resource SET popularity = h2_resource_popularity(NEW.resource_id)
            WHERE id = NEW.resource_id AND popularity IS DISTINCT FROM h2_resource_popularity(NEW.resource_id);
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER source_record_popularity
    AFTER INSERT OR DELETE OR UPDATE OF resource_id, extra ON source_record
    FOR EACH ROW EXECUTE FUNCTION h2_refresh_popularity();

CREATE TABLE resource_embedding (
    resource_id uuid         NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    model       text         NOT NULL,
    text_hash   text         NOT NULL,
    embedding   vector(1024) NOT NULL,
    embedded_at timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (resource_id, model)
);
CREATE INDEX resource_embedding_hnsw_idx ON resource_embedding USING hnsw (embedding vector_cosine_ops);

CREATE TABLE evidence (
    id               uuid PRIMARY KEY,
    source_record_id uuid        REFERENCES source_record (id) ON DELETE CASCADE,
    source_url       text        NOT NULL,
    retrieved_at     timestamptz NOT NULL,
    excerpt          text CHECK (length(excerpt) <= 500),
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX evidence_source_record_idx ON evidence (source_record_id) WHERE source_record_id IS NOT NULL;

CREATE TABLE enrichment_run (
    id                uuid PRIMARY KEY,
    resource_id       uuid        NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    provider          text        NOT NULL,
    model             text        NOT NULL,
    prompt_version    text        NOT NULL,
    input_fingerprint text        NOT NULL,
    started_at        timestamptz NOT NULL,
    finished_at       timestamptz,
    outcome           text        NOT NULL CHECK (outcome IN ('VALIDATED','PARTIAL','REJECTED','LLM_UNAVAILABLE','FAILED')),
    validation_errors jsonb       NOT NULL DEFAULT '[]'::jsonb,
    extra_evidence    text CHECK (length(extra_evidence) <= 300)
);
CREATE INDEX enrichment_run_resource_idx ON enrichment_run (resource_id, started_at DESC);

CREATE TABLE field_assertion (
    id                uuid PRIMARY KEY,
    resource_id       uuid        NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    field             text        NOT NULL,
    value             text        NOT NULL,
    origin            text        NOT NULL CHECK (origin IN ('AI_EXTRACTED','AI_ESTIMATED','SELF_DECLARED','CURATED')),
    verification      text        NOT NULL CHECK (verification IN ('VERIFIED','UNVERIFIED','CONFLICTING','REJECTED')),
    enrichment_run_id uuid REFERENCES enrichment_run (id) ON DELETE NO ACTION,
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX field_assertion_resource_idx ON field_assertion (resource_id);
CREATE INDEX field_assertion_run_idx ON field_assertion (enrichment_run_id) WHERE enrichment_run_id IS NOT NULL;

CREATE TABLE field_assertion_evidence (
    assertion_id uuid NOT NULL REFERENCES field_assertion (id) ON DELETE CASCADE,
    evidence_id  uuid NOT NULL REFERENCES evidence (id) ON DELETE CASCADE,
    PRIMARY KEY (assertion_id, evidence_id)
);
CREATE INDEX field_assertion_evidence_evidence_idx ON field_assertion_evidence (evidence_id);

CREATE TABLE relationship (
    id                uuid PRIMARY KEY,
    from_resource_id  uuid NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    to_resource_id    uuid REFERENCES resource (id) ON DELETE SET NULL,
    to_title          text NOT NULL,
    kind              text NOT NULL CHECK (kind IN ('INTEGRATES_WITH', 'USES', 'SUPPORTS', 'COMPATIBLE_WITH', 'ABOUT')),
    verification      text NOT NULL CHECK (verification IN ('VERIFIED','UNVERIFIED','CONFLICTING','REJECTED')),
    origin            text NOT NULL CHECK (origin IN ('AI_EXTRACTED', 'SELF_DECLARED')),
    enrichment_run_id uuid REFERENCES enrichment_run (id) ON DELETE NO ACTION,
    created_at        timestamptz NOT NULL DEFAULT now(),
    target_origin     text CHECK (target_origin IN ('CURATED')),
    target_kind       text CHECK (target_kind IN ('VENDOR', 'SERVICE')),
    stated_target_kind text CHECK (stated_target_kind IN ('PRODUCT', 'VENDOR', 'SERVICE')),
    CHECK (target_kind IS NULL OR to_resource_id IS NULL),
    UNIQUE (from_resource_id, kind, to_title)
);
CREATE INDEX relationship_to_resource_idx ON relationship (to_resource_id) WHERE to_resource_id IS NOT NULL;
CREATE INDEX relationship_run_idx ON relationship (enrichment_run_id) WHERE enrichment_run_id IS NOT NULL;

CREATE TABLE known_target (
    id       uuid PRIMARY KEY,
    kind     text        NOT NULL CHECK (kind IN ('VENDOR', 'SERVICE')),
    name     text        NOT NULL CHECK (length(name) BETWEEN 2 AND 60),
    added_by text        NOT NULL,
    added_at timestamptz NOT NULL DEFAULT now(),
    origin   text        NOT NULL DEFAULT 'CURATED' CHECK (origin IN ('CURATED', 'DISCOVERED'))
);
CREATE TABLE known_target_name (
    name_key  text PRIMARY KEY CHECK (length(name_key) BETWEEN 2 AND 60),
    target_id uuid NOT NULL REFERENCES known_target (id) ON DELETE CASCADE,
    name      text NOT NULL CHECK (length(name) BETWEEN 2 AND 60)
);
CREATE INDEX known_target_name_target_idx ON known_target_name (target_id);

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
    RETURNING id, name)
INSERT INTO known_target_name (name_key, target_id, name)
SELECT lower(n.name), a.id, n.name
FROM added a JOIN seed s ON s.name = a.name
     CROSS JOIN LATERAL unnest(ARRAY[s.name] || s.other_names) AS n(name);

CREATE TABLE relationship_evidence (
    relationship_id uuid NOT NULL REFERENCES relationship (id) ON DELETE CASCADE,
    evidence_id     uuid NOT NULL REFERENCES evidence (id) ON DELETE CASCADE,
    PRIMARY KEY (relationship_id, evidence_id)
);
CREATE INDEX relationship_evidence_evidence_idx ON relationship_evidence (evidence_id);

CREATE TABLE summary_claim (
    id                uuid PRIMARY KEY,
    resource_id       uuid NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    enrichment_run_id uuid NOT NULL REFERENCES enrichment_run (id) ON DELETE NO ACTION,
    position          int  NOT NULL,
    text              text NOT NULL CHECK (length(text) <= 400),
    self_declared     boolean NOT NULL DEFAULT false,
    UNIQUE (enrichment_run_id, position)
);
CREATE INDEX summary_claim_resource_idx ON summary_claim (resource_id);

CREATE TABLE summary_claim_evidence (
    claim_id    uuid NOT NULL REFERENCES summary_claim (id) ON DELETE CASCADE,
    evidence_id uuid NOT NULL REFERENCES evidence (id) ON DELETE CASCADE,
    PRIMARY KEY (claim_id, evidence_id)
);
CREATE INDEX summary_claim_evidence_evidence_idx ON summary_claim_evidence (evidence_id);

CREATE TABLE awesome_list_entry (
    list_id       uuid        NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    resource_id   uuid        NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    heading_path  text        NOT NULL CHECK (length(heading_path) <= 200),
    search_vector tsvector    NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (list_id, resource_id),
    CHECK (list_id <> resource_id)
);
CREATE INDEX awesome_list_entry_search_idx ON awesome_list_entry USING gin (search_vector);
CREATE INDEX awesome_list_entry_resource_idx ON awesome_list_entry (resource_id);
CREATE INDEX awesome_list_entry_updated_idx ON awesome_list_entry (updated_at);

CREATE TABLE awesome_list (
    list_id       uuid PRIMARY KEY REFERENCES resource (id) ON DELETE CASCADE,
    search_vector tsvector    NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX awesome_list_search_idx ON awesome_list USING gin (search_vector);
CREATE INDEX awesome_list_updated_idx ON awesome_list (updated_at);

CREATE TABLE job_marker (
    name    text        PRIMARY KEY CHECK (length(name) <= 100),
    value   text,
    done_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE issue_report (
    id               uuid PRIMARY KEY,
    resource_id      uuid        NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    kind             text        NOT NULL CHECK (kind IN ('WRONG','OUTDATED','BROKEN_LINK','OTHER')),
    field            text,
    message          text        NOT NULL CHECK (length(message) BETWEEN 5 AND 2000),
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX issue_report_resource_idx ON issue_report (resource_id);

CREATE TABLE review_item (
    id                uuid PRIMARY KEY,
    kind              text        NOT NULL CHECK (kind IN
        ('ENRICHMENT_VALIDATION','RELATIONSHIP_CANDIDATE','USER_REPORT','COURSE_CANDIDATE','EXAMPLE_CANDIDATE',
         'RESOURCE_CANDIDATE','KNOWN_TARGET_CANDIDATE')),
    status            text        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACCEPTED','REJECTED','SUPERSEDED')),
    resource_id       uuid REFERENCES resource (id) ON DELETE CASCADE,
    enrichment_run_id uuid REFERENCES enrichment_run (id) ON DELETE NO ACTION,
    relationship_id   uuid REFERENCES relationship (id) ON DELETE SET NULL,
    report_id         uuid REFERENCES issue_report (id) ON DELETE CASCADE,
    candidate_url     text,
    reason            text        NOT NULL,
    details           jsonb CHECK (jsonb_typeof(details) = 'object'),
    note              text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    resolved_at       timestamptz,
    resolved_by       text,
    resolution_note   text,
    CHECK ((status = 'OPEN') = (resolved_at IS NULL)),
    CHECK (details IS NULL OR kind IN ('RESOURCE_CANDIDATE', 'EXAMPLE_CANDIDATE', 'KNOWN_TARGET_CANDIDATE')),
    CHECK (note IS NULL OR kind NOT IN ('RESOURCE_CANDIDATE', 'EXAMPLE_CANDIDATE', 'KNOWN_TARGET_CANDIDATE'))
);
CREATE INDEX review_item_open_idx ON review_item (status, created_at);
CREATE INDEX review_item_resource_idx ON review_item (resource_id);
CREATE INDEX review_item_run_idx ON review_item (enrichment_run_id) WHERE enrichment_run_id IS NOT NULL;
CREATE INDEX review_item_report_idx ON review_item (report_id) WHERE report_id IS NOT NULL;
CREATE INDEX review_item_relationship_idx ON review_item (relationship_id) WHERE relationship_id IS NOT NULL;
CREATE UNIQUE INDEX review_item_candidate_idx ON review_item (kind, candidate_url) WHERE candidate_url IS NOT NULL;
CREATE INDEX review_item_resolved_idx ON review_item (resolved_at) WHERE status <> 'OPEN';
CREATE UNIQUE INDEX review_item_known_target_idx ON review_item ((details ->> 'key'))
    WHERE kind = 'KNOWN_TARGET_CANDIDATE';

CREATE TABLE candidate_decision (
    kind          text        NOT NULL CHECK (kind IN
        ('RESOURCE_CANDIDATE','EXAMPLE_CANDIDATE','COURSE_CANDIDATE','KNOWN_TARGET_CANDIDATE')),
    candidate_key uuid        NOT NULL,
    status        text        NOT NULL CHECK (status IN ('ACCEPTED','REJECTED','SUPERSEDED')),
    decided_at    timestamptz NOT NULL,
    PRIMARY KEY (kind, candidate_key)
);

CREATE TABLE live_web_query_result (
    query_key    bytea       PRIMARY KEY,
    fetched_at   timestamptz NOT NULL,
    result_ids   uuid[]      NOT NULL
);
CREATE INDEX live_web_query_result_fetched_idx ON live_web_query_result (fetched_at);

CREATE TABLE source_cursor (
    source_id  text PRIMARY KEY REFERENCES source (id) ON DELETE CASCADE,
    cursor     text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE scout_run (
    id                 uuid PRIMARY KEY,
    source_id          text        NOT NULL REFERENCES source (id) ON DELETE CASCADE,
    status             text        NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','SKIPPED')),
    started_at         timestamptz NOT NULL,
    finished_at        timestamptz,
    items_seen         int         NOT NULL DEFAULT 0,
    items_changed      int         NOT NULL DEFAULT 0,
    enrichments_queued int         NOT NULL DEFAULT 0,
    message            text
);
CREATE INDEX scout_run_started_idx ON scout_run (started_at DESC);

CREATE TABLE scout_schedule (
    id            boolean     PRIMARY KEY DEFAULT true CHECK (id),
    last_ended_at timestamptz NOT NULL
);

CREATE TABLE enrichment_queue_control (
    resource_id       uuid        PRIMARY KEY REFERENCES resource (id) ON DELETE CASCADE,
    prioritised_at    timestamptz,
    skipped_at        timestamptz,
    skip_reason       text        CHECK (length(skip_reason) <= 200),
    failures_reset_at timestamptz,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CHECK (prioritised_at IS NULL OR skipped_at IS NULL),
    CHECK (skip_reason IS NULL OR skipped_at IS NOT NULL)
);

CREATE INDEX enrichment_queue_control_prioritised_idx ON enrichment_queue_control (prioritised_at)
    WHERE prioritised_at IS NOT NULL;
CREATE INDEX enrichment_queue_control_skipped_idx ON enrichment_queue_control (skipped_at DESC, resource_id DESC)
    WHERE skipped_at IS NOT NULL;

CREATE TABLE discovery_host (
    host       text        PRIMARY KEY CHECK (length(host) BETWEEN 3 AND 253 AND host = lower(host)),
    kind       text        NOT NULL CHECK (kind IN ('WRITING', 'DIRECTORY')),
    added_by   text        NOT NULL,
    added_at   timestamptz NOT NULL,
    reason     text        CHECK (reason IS NULL OR length(reason) <= 500)
);

INSERT INTO discovery_host (host, kind, added_by, added_at) VALUES
    ('medium.com', 'WRITING', 'migration', now()),
    ('dev.to', 'WRITING', 'migration', now()),
    ('substack.com', 'WRITING', 'migration', now()),
    ('forbes.com', 'WRITING', 'migration', now()),
    ('techcrunch.com', 'WRITING', 'migration', now()),
    ('theverge.com', 'WRITING', 'migration', now()),
    ('wired.com', 'WRITING', 'migration', now()),
    ('zdnet.com', 'WRITING', 'migration', now()),
    ('venturebeat.com', 'WRITING', 'migration', now()),
    ('hashnode.dev', 'WRITING', 'migration', now()),
    ('wordpress.com', 'WRITING', 'migration', now()),
    ('blogspot.com', 'WRITING', 'migration', now()),
    ('beehiiv.com', 'WRITING', 'migration', now()),
    ('producthunt.com', 'DIRECTORY', 'migration', now()),
    ('huggingface.co', 'DIRECTORY', 'migration', now()),
    ('wikipedia.org', 'DIRECTORY', 'migration', now()),
    ('news.ycombinator.com', 'DIRECTORY', 'migration', now()),
    ('g2.com', 'DIRECTORY', 'migration', now()),
    ('capterra.com', 'DIRECTORY', 'migration', now()),
    ('gartner.com', 'DIRECTORY', 'migration', now()),
    ('notion.site', 'DIRECTORY', 'migration', now()),
    ('theresanaiforthat.com', 'DIRECTORY', 'migration', now()),
    ('futurepedia.io', 'DIRECTORY', 'migration', now()),
    ('futuretools.io', 'DIRECTORY', 'migration', now()),
    ('toolify.ai', 'DIRECTORY', 'migration', now()),
    ('topai.tools', 'DIRECTORY', 'migration', now()),
    ('aitools.fyi', 'DIRECTORY', 'migration', now()),
    ('getapp.com', 'DIRECTORY', 'migration', now()),
    ('softwareadvice.com', 'DIRECTORY', 'migration', now()),
    ('trustradius.com', 'DIRECTORY', 'migration', now()),
    ('trustpilot.com', 'DIRECTORY', 'migration', now()),
    ('alternativeto.net', 'DIRECTORY', 'migration', now()),
    ('saasworthy.com', 'DIRECTORY', 'migration', now());

CREATE TABLE guide_similar_question (
    answer_key text         PRIMARY KEY CHECK (answer_key ~ '^[0-9a-f]{64}$'),
    group_key  text         NOT NULL,
    embedding  vector(1024) NOT NULL,
    expires_at timestamptz  NOT NULL
);
CREATE INDEX guide_similar_question_group_idx ON guide_similar_question (group_key, expires_at);
CREATE INDEX guide_similar_question_expires_idx ON guide_similar_question (expires_at);

CREATE TABLE home_example (
    id                    uuid PRIMARY KEY,
    label                 text        NOT NULL CHECK (length(label) BETWEEN 2 AND 32),
    query                 text        NOT NULL CHECK (length(query) BETWEEN 3 AND 200),
    path                  text        NOT NULL CHECK (path IN ('USE_AI', 'BUILD_WITH_AI')),
    locale                text        NOT NULL DEFAULT 'en' CHECK (locale ~ '^[a-z]{2}$'),
    status                text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PAUSED')),
    expected_resource_ids uuid[]      NOT NULL CHECK (cardinality(expected_resource_ids) BETWEEN 1 AND 3),
    origin                text        NOT NULL CHECK (origin IN ('BUILT_IN', 'CURATED', 'AI_DRAFTED')),
    model                 text,
    check_outcome         text        NOT NULL DEFAULT 'PENDING' CHECK (check_outcome IN ('PENDING', 'PASS', 'WEAK', 'FAIL')),
    check_best_rank       integer     CHECK (check_best_rank > 0),
    check_result_count    integer     CHECK (check_result_count >= 0),
    checked_at            timestamptz,
    version               integer     NOT NULL DEFAULT 0,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CHECK ((origin = 'AI_DRAFTED') = (model IS NOT NULL))
);
CREATE UNIQUE INDEX home_example_label_idx ON home_example (locale, lower(label));
CREATE UNIQUE INDEX home_example_query_idx ON home_example (locale, lower(query));

INSERT INTO home_example (id, label, query, path, expected_resource_ids, origin)
SELECT gen_random_uuid(), s.label, s.query, s.path,
       ARRAY(SELECT uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, u) FROM unnest(s.urls) AS u),
       'BUILT_IN'
FROM (VALUES
    ('Summarize research papers', 'summarize research papers', 'USE_AI', '{https://elicit.com}'::text[]),
    ('Take meeting notes', 'take meeting notes', 'USE_AI', '{https://otter.ai}'),
    ('Make images from text', 'make images from text', 'USE_AI', '{https://midjourney.com,https://canva.com/magic,https://firefly.adobe.com}'),
    ('Remove a photo background', 'remove image background', 'USE_AI', '{https://huggingface.co/spaces/openskyml/remove-background-on-image}'),
    ('Edit videos with AI', 'edit videos with AI', 'USE_AI', '{https://runwayml.com}'),
    ('Postgres MCP server', 'postgres mcp server', 'BUILD_WITH_AI', '{https://npmjs.com/package/@microsoft/postgres-mcp,https://npmjs.com/package/enhanced-postgres-mcp-server}'),
    ('Vector database', 'vector database', 'BUILD_WITH_AI', '{https://github.com/milvus-io/milvus,https://github.com/qdrant/qdrant,https://github.com/weaviate/weaviate}'),
    ('Agent framework', 'agent framework', 'BUILD_WITH_AI', '{https://github.com/microsoft/agent-framework}'),
    ('Run LLMs locally', 'run llms locally', 'BUILD_WITH_AI', '{https://github.com/llamaedge/llamaedge,https://github.com/nomic-ai/gpt4all}'),
    ('RAG framework', 'rag framework', 'BUILD_WITH_AI', '{https://github.com/hkuds/rag-anything}'),
    ('AI coding assistant', 'AI coding assistant', 'BUILD_WITH_AI', '{https://github.com/github/copilotforxcode,https://npmjs.com/package/@qwen-code/qwen-code}')
) AS s (label, query, path, urls);

CREATE TABLE runtime_setting (
    version    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    value      bigint      CHECK (value IS NULL OR value >= 0),
    changed_by text        NOT NULL,
    changed_at timestamptz NOT NULL,
    reason     text        CHECK (reason IS NULL OR length(reason) <= 500)
);

CREATE INDEX runtime_setting_name_idx ON runtime_setting (name, version DESC);

CREATE TRIGGER runtime_setting_append_only BEFORE UPDATE ON runtime_setting
    FOR EACH ROW EXECUTE FUNCTION h2_append_only();

CREATE TABLE public_app_setting (
    key          text        PRIMARY KEY CHECK (length(key) BETWEEN 1 AND 100),
    group_id     text        NOT NULL CHECK (length(group_id) BETWEEN 1 AND 50),
    group_title  text        NOT NULL CHECK (length(group_title) BETWEEN 1 AND 100),
    group_order  integer     NOT NULL,
    position     integer     NOT NULL,
    label        text        NOT NULL CHECK (length(label) BETWEEN 1 AND 200),
    kind         text        NOT NULL CHECK (kind IN
                   ('COUNT', 'CAP', 'FLAG', 'DURATION', 'MODEL', 'VERSION', 'SCHEDULE', 'NAMES')),
    value        text        CHECK (value IS NULL OR length(value) <= 500),
    unit         text        CHECK (unit IS NULL OR length(unit) <= 50),
    variable     text        CHECK (variable IS NULL OR length(variable) <= 100),
    build        text        NOT NULL CHECK (length(build) BETWEEN 1 AND 100),
    published_at timestamptz NOT NULL
);

DO $$
DECLARE
  role_name text;
BEGIN
  FOREACH role_name IN ARRAY ARRAY['h2_public', 'h2_backoffice'] LOOP
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = role_name) THEN
      EXECUTE format('CREATE ROLE %I NOLOGIN', role_name);
    END IF;
    EXECUTE format('ALTER ROLE %I NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT', role_name);
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), role_name);
  END LOOP;
END
$$;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
DO $$
BEGIN
  EXECUTE format('REVOKE TEMPORARY ON DATABASE %I FROM PUBLIC', current_database());
END
$$;

GRANT USAGE ON SCHEMA public TO h2_public, h2_backoffice;

GRANT SELECT ON ALL TABLES IN SCHEMA public TO h2_public, h2_backoffice;
REVOKE SELECT ON scout_run, scout_schedule, source_cursor, discovery_host, enrichment_queue_control, admin_audit
  FROM h2_public;
REVOKE SELECT ON candidate_decision, known_target, known_target_name FROM h2_public;

GRANT INSERT, UPDATE, DELETE ON resource, resource_label, live_web_query_result, app_user, guide_similar_question,
  ai_cache TO h2_public;
GRANT INSERT, UPDATE ON source, source_record, issue_report, review_item, shedlock TO h2_public;
GRANT INSERT, DELETE ON bookmark, public_app_setting TO h2_public;

GRANT INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO h2_backoffice;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO h2_backoffice;

REVOKE INSERT, UPDATE, DELETE ON ${flyway:table} FROM h2_public, h2_backoffice;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO h2_public, h2_backoffice;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT INSERT, UPDATE, DELETE ON TABLES TO h2_backoffice;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO h2_backoffice;
