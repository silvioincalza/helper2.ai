-- Helper2.ai schema. PostgreSQL is the system of record. Seed data lives in the repeatable R__ migrations.
-- Timestamp semantics: every *_at column is timestamptz (UTC instants).
--   source_published_at / source_updated_at : what the SOURCE says (nullable = unknown, never guessed)
--   first_discovered_at                     : when Helper2 first saw the resource (always known)
--   retrieved_at                            : when Helper2 fetched the source material
--   created_at / modified_at                : row bookkeeping only, never shown as resource dates

CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- uuid_generate_v5 reproduces ai.helper2.shared.ResourceId in SQL (seeds, source_record identity index).
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
-- Vector search (ADR 0012).
CREATE EXTENSION IF NOT EXISTS vector;

-- unaccent() is STABLE; generated columns need IMMUTABLE. Pinning the dictionary makes this safe.
CREATE FUNCTION h2_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;

-- ISO code -> text search config; unknown codes fall back to 'simple'. Same list as LanguageDetector.SUPPORTED.
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

-- Disclosed source registry (mirrors configuration; kept for FKs and health history).
CREATE TABLE source (
    id              text PRIMARY KEY CHECK (id ~ '^[a-z0-9-]{2,40}$'),
    name            text        NOT NULL,
    mode            text        NOT NULL CHECK (mode IN ('LIVE', 'INDEXED', 'CURATED', 'DEMO')),
    homepage        text        NOT NULL,
    terms_url       text,
    attribution     text,
    enabled         boolean     NOT NULL DEFAULT true,
    last_outcome    text CHECK (last_outcome IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'RATE_LIMITED', 'SKIPPED')),
    last_checked_at timestamptz,
    modified_at     timestamptz NOT NULL DEFAULT now()
);

-- Canonical resource. id = UUIDv5(canonical_url), computed in the domain.
CREATE TABLE resource (
    id                   uuid PRIMARY KEY,
    canonical_url        text        NOT NULL UNIQUE,
    url                  text        NOT NULL,
    title                text        NOT NULL CHECK (length(title) BETWEEN 1 AND 300),
    description          text CHECK (length(description) <= 4000),
    description_origin   text        NOT NULL CHECK (description_origin IN ('SOURCE_PROVIDED','AI_EXTRACTED','AI_SUMMARY','CURATED','COMMUNITY','DEMO')),
    -- ISO 639-1 code from LanguageDetector (Kotlin), or 'simple' when unknown.
    language             text        NOT NULL DEFAULT 'en' CHECK (language IN
        ('simple','ar','hy','eu','ca','da','nl','en','fi','fr','de','el','hi','hu','id','ga','it','lt','no','pt','ro',
         'ru','sr','es','sv','ta','tr')),
    type                 text        NOT NULL CHECK (type IN
        ('PRODUCT','MODEL','AGENT','PROMPT','SKILL','MCP_SERVER','LIBRARY','DATASET','ARTICLE','PAPER','NEWS','EVENT','OTHER')),
    audience             text        NOT NULL CHECK (audience IN ('USE_AI','BUILD_WITH_AI','BOTH','UNKNOWN')),
    tech_level           text CHECK (tech_level IN ('BEGINNER','INTERMEDIATE','ADVANCED')),
    license              text,
    pricing              text CHECK (pricing IN ('FREE','FREEMIUM','PAID','OPEN_SOURCE_SELF_HOSTED')),
    open_source          boolean,
    published_at         timestamptz,
    published_precision  text        NOT NULL DEFAULT 'UNKNOWN' CHECK (published_precision IN ('INSTANT','DAY','UNKNOWN')),
    published_source_id  text REFERENCES source (id),
    updated_at           timestamptz,
    updated_source_id    text REFERENCES source (id),
    -- EVENT only: when it takes place, as its sources state it (earliest start / latest end across the
    -- resource's source records; DAY precision stored as 00:00 UTC). NULL = unknown or not an event.
    event_starts_at      timestamptz,
    event_ends_at        timestamptz,
    first_discovered_at  timestamptz NOT NULL DEFAULT now(),
    last_seen_at         timestamptz NOT NULL DEFAULT now(),
    data_origin          text        NOT NULL CHECK (data_origin IN ('OBSERVED','INDEXED','CURATED','DEMO')),
    enrichment_status    text        NOT NULL DEFAULT 'NOT_RUN' CHECK (enrichment_status IN ('NOT_RUN','VALIDATED','PARTIAL','REJECTED','LLM_UNAVAILABLE')),
    -- Strongest popularity metric of the resource's source records, log-scaled per kind to 0..1.
    -- Kept current by the source_record_popularity trigger, so ranking reads a column (ADR 0009).
    popularity           numeric     NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    modified_at          timestamptz NOT NULL DEFAULT now(),
    CHECK ((published_at IS NULL) = (published_precision = 'UNKNOWN')),
    -- Language-aware full-text search (ADR 0011): 'simple' on the title (names match exactly, any language),
    -- English on title + description for every row, and the row's own stemmer when that is not English.
    search_vector tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', h2_unaccent(coalesce(title, ''))), 'A') ||
        setweight(to_tsvector('english', h2_unaccent(coalesce(title, '') || ' ' || coalesce(description, ''))), 'B') ||
        CASE WHEN language IN ('en', 'simple') THEN ''::tsvector
             ELSE setweight(to_tsvector(h2_ts_config(language), h2_unaccent(coalesce(title, '') || ' ' || coalesce(description, ''))), 'B')
        END
    ) STORED
);
CREATE INDEX resource_search_idx ON resource USING gin (search_vector);
-- Serves the trigram branch of ResourceStore.search (lower(title) % query).
CREATE INDEX resource_title_trgm_idx ON resource USING gin (lower(title) gin_trgm_ops);
CREATE INDEX resource_type_idx ON resource (type);
CREATE INDEX resource_updated_idx ON resource (updated_at DESC NULLS LAST);
CREATE INDEX resource_published_idx ON resource (published_at DESC) WHERE published_at IS NOT NULL;
CREATE INDEX resource_origin_idx ON resource (data_origin);
CREATE INDEX resource_event_idx ON resource (event_starts_at) WHERE type = 'EVENT' AND event_starts_at IS NOT NULL;
-- Retention (ADR 0034): observed resources nobody has seen for a while.
CREATE INDEX resource_observed_seen_idx ON resource (last_seen_at) WHERE data_origin = 'OBSERVED';

-- Tags, use cases and platforms: one normalized table keyed by kind. Rebuilt from the resource's source
-- records on every catalog upsert, so labels never outlive the source data that stated them.
CREATE TABLE resource_label (
    resource_id uuid NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    kind        text NOT NULL CHECK (kind IN ('TAG', 'USE_CASE', 'PLATFORM')),
    value       text NOT NULL CHECK (length(value) BETWEEN 1 AND 60),
    PRIMARY KEY (resource_id, kind, value)
);
CREATE INDEX resource_label_value_idx ON resource_label (kind, value);

-- One record per (source, external id): exactly what a source said, when we retrieved it.
CREATE TABLE source_record (
    id                     uuid PRIMARY KEY,
    source_id              text        NOT NULL REFERENCES source (id),
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
    -- EVENT only: the days the source states for it (DAY precision, 00:00 UTC).
    source_event_starts_at timestamptz,
    source_event_ends_at   timestamptz,
    retrieved_at           timestamptz NOT NULL,
    fingerprint            text        NOT NULL,
    -- Genuinely source-specific extras (popularity metrics, raw topics, version strings).
    extra                  jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at             timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_id, external_id)
);
CREATE INDEX source_record_resource_idx ON source_record (resource_id);
-- The resource id this record's own URL would have: resolves links to a resource whose records moved
-- to another one (an npm package that later turned out to be a GitHub repository's).
CREATE INDEX source_record_identity_idx
    ON source_record (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, canonical_url));

-- Popularity of a resource: the strongest metric of any of its source records, log-scaled per kind to 0..1.
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

-- The popularity metrics of a record without their asOf: every fetch stamps a new asOf, so comparing the
-- whole extra would never find an unchanged record.
CREATE FUNCTION h2_popularity_values(extra jsonb) RETURNS jsonb
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    RETURN (
        SELECT coalesce(jsonb_agg(jsonb_build_array(p->>'kind', p->'value') ORDER BY p->>'kind', p->>'value'), '[]'::jsonb)
        FROM jsonb_array_elements(coalesce(extra -> 'popularity', '[]'::jsonb)) p
    );

CREATE FUNCTION h2_refresh_popularity() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    -- Most upserts rewrite a record whose metrics did not change: skip those, and never rewrite a resource
    -- row whose popularity stays the same.
    IF TG_OP = 'UPDATE' AND NEW.resource_id IS NOT DISTINCT FROM OLD.resource_id
       AND h2_popularity_values(NEW.extra) = h2_popularity_values(OLD.extra) THEN
        RETURN NULL;
    END IF;
    -- The old owner: covers changed metrics on the same resource as well as a record moving away.
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

-- Vector search (ADR 0012): one embedding per resource and model, in its own table so `SELECT r.*` on
-- resource stays as it is. Derived data: the indexer fills and refreshes it from resource (text_hash = md5
-- of the embedded text), and it is safe to truncate.
CREATE TABLE resource_embedding (
    resource_id uuid         NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    model       text         NOT NULL,
    text_hash   text         NOT NULL,
    embedding   vector(1024) NOT NULL,
    embedded_at timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (resource_id, model)
);
-- Cosine distance, the metric the NVIDIA retrieval embeddings are trained for.
CREATE INDEX resource_embedding_hnsw_idx ON resource_embedding USING hnsw (embedding vector_cosine_ops);

-- Evidence: a retrievable reference supporting a field or claim.
CREATE TABLE evidence (
    id               uuid PRIMARY KEY,
    source_record_id uuid        REFERENCES source_record (id) ON DELETE CASCADE,
    source_url       text        NOT NULL,
    retrieved_at     timestamptz NOT NULL,
    excerpt          text CHECK (length(excerpt) <= 500),
    created_at       timestamptz NOT NULL DEFAULT now()
);

-- Enrichment runs (AI Resource Scout). Kept while current rows cite them, plus the latest per resource (ADR 0034).
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
    reprocess_of      uuid REFERENCES enrichment_run (id) ON DELETE SET NULL
);
CREATE INDEX enrichment_run_resource_idx ON enrichment_run (resource_id, started_at DESC);

-- Field-level assertions not covered by source records: AI-extracted, community and curated values.
CREATE TABLE field_assertion (
    id                uuid PRIMARY KEY,
    resource_id       uuid        NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    field             text        NOT NULL,
    value             text        NOT NULL,
    origin            text        NOT NULL CHECK (origin IN ('AI_EXTRACTED','AI_SUMMARY','COMMUNITY','CURATED')),
    verification      text        NOT NULL CHECK (verification IN ('VERIFIED','UNVERIFIED','CONFLICTING','REJECTED')),
    enrichment_run_id uuid REFERENCES enrichment_run (id),
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX field_assertion_resource_idx ON field_assertion (resource_id);
CREATE INDEX field_assertion_run_idx ON field_assertion (enrichment_run_id) WHERE enrichment_run_id IS NOT NULL;

CREATE TABLE field_assertion_evidence (
    assertion_id uuid NOT NULL REFERENCES field_assertion (id) ON DELETE CASCADE,
    evidence_id  uuid NOT NULL REFERENCES evidence (id) ON DELETE CASCADE,
    PRIMARY KEY (assertion_id, evidence_id)
);

-- Resource relationships. Verified only with evidence; candidates go to review.
CREATE TABLE relationship (
    id                uuid PRIMARY KEY,
    from_resource_id  uuid NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    to_resource_id    uuid REFERENCES resource (id) ON DELETE SET NULL,
    to_title          text NOT NULL,
    kind              text NOT NULL CHECK (kind IN ('INTEGRATES_WITH','USES','SUPPORTS','COMPATIBLE_WITH')),
    verification      text NOT NULL CHECK (verification IN ('VERIFIED','UNVERIFIED','CONFLICTING','REJECTED')),
    origin            text NOT NULL CHECK (origin IN ('SOURCE_PROVIDED','AI_EXTRACTED','COMMUNITY','CURATED')),
    enrichment_run_id uuid REFERENCES enrichment_run (id),
    created_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (from_resource_id, kind, to_title)
);
CREATE INDEX relationship_run_idx ON relationship (enrichment_run_id) WHERE enrichment_run_id IS NOT NULL;

CREATE TABLE relationship_evidence (
    relationship_id uuid NOT NULL REFERENCES relationship (id) ON DELETE CASCADE,
    evidence_id     uuid NOT NULL REFERENCES evidence (id) ON DELETE CASCADE,
    PRIMARY KEY (relationship_id, evidence_id)
);

-- AI summary: only claims that passed validation, each with evidence.
CREATE TABLE summary_claim (
    id                uuid PRIMARY KEY,
    resource_id       uuid NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    enrichment_run_id uuid NOT NULL REFERENCES enrichment_run (id),
    position          int  NOT NULL,
    text              text NOT NULL CHECK (length(text) <= 400),
    UNIQUE (enrichment_run_id, position)
);

CREATE TABLE summary_claim_evidence (
    claim_id    uuid NOT NULL REFERENCES summary_claim (id) ON DELETE CASCADE,
    evidence_id uuid NOT NULL REFERENCES evidence (id) ON DELETE CASCADE,
    PRIMARY KEY (claim_id, evidence_id)
);

-- Users who signed in via OAuth2. Anonymous use needs no row. Only what sign-in needs: the provider's immutable
-- subject (GitHub: the numeric id), a name to show, and the last sign-in, which retention reads (ADR 0034).
CREATE TABLE app_user (
    id                uuid PRIMARY KEY,
    provider          text        NOT NULL,
    subject           text        NOT NULL,
    display_name      text,
    last_signed_in_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider, subject)
);
CREATE INDEX app_user_last_signed_in_idx ON app_user (last_signed_in_at);

-- Bookmarks keep a small snapshot so they render even if a live result was never indexed.
CREATE TABLE bookmark (
    user_id     uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    resource_id uuid        NOT NULL,
    title       text        NOT NULL,
    url         text        NOT NULL,
    type        text,
    saved_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, resource_id)
);

-- User reports about wrong/outdated info.
CREATE TABLE issue_report (
    id               uuid PRIMARY KEY,
    resource_id      uuid        NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    kind             text        NOT NULL CHECK (kind IN ('WRONG','OUTDATED','BROKEN_LINK','OTHER')),
    field            text,
    message          text        NOT NULL CHECK (length(message) BETWEEN 5 AND 2000),
    reporter_user_id uuid REFERENCES app_user (id) ON DELETE SET NULL,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX issue_report_resource_idx ON issue_report (resource_id);

-- Human review queue.
CREATE TABLE review_item (
    id                uuid PRIMARY KEY,
    kind              text        NOT NULL CHECK (kind IN ('ENRICHMENT_VALIDATION','SOURCE_CONFLICT','RELATIONSHIP_CANDIDATE','USER_REPORT')),
    -- SUPERSEDED: closed by a newer enrichment run of the same resource, not by a person.
    status            text        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACCEPTED','REJECTED','SUPERSEDED')),
    resource_id       uuid REFERENCES resource (id) ON DELETE CASCADE,
    enrichment_run_id uuid REFERENCES enrichment_run (id),
    -- SET NULL: a superseded candidate outlives the unreviewed relationship a newer run replaced.
    relationship_id   uuid REFERENCES relationship (id) ON DELETE SET NULL,
    report_id         uuid REFERENCES issue_report (id) ON DELETE CASCADE,
    reason            text        NOT NULL,
    details           text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    resolved_at       timestamptz,
    resolved_by       text,
    resolution_note   text,
    CHECK ((status = 'OPEN') = (resolved_at IS NULL))
);
CREATE INDEX review_item_open_idx ON review_item (status, created_at);
CREATE INDEX review_item_resource_idx ON review_item (resource_id);
CREATE INDEX review_item_run_idx ON review_item (enrichment_run_id) WHERE enrichment_run_id IS NOT NULL;
CREATE INDEX review_item_report_idx ON review_item (report_id) WHERE report_id IS NOT NULL;
CREATE INDEX review_item_resolved_idx ON review_item (resolved_at) WHERE status <> 'OPEN';

-- What administrators did, one row per admin action (ADR 0023): append-only, deleted only by retention (ADR 0034).
-- admin_id is the provider subject (GitHub: the numeric id), never an app_user reference, so the trail outlives
-- the account.
CREATE TABLE admin_audit (
    id        uuid PRIMARY KEY,
    admin_id  text        NOT NULL,
    action    text        NOT NULL CHECK (action IN ('RESOLVE_REVIEW_ITEM', 'START_SCOUT_RUN')),
    target_id text,
    at        timestamptz NOT NULL DEFAULT now(),
    outcome   text        NOT NULL
);
CREATE INDEX admin_audit_at_idx ON admin_audit (at DESC);

CREATE FUNCTION h2_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER admin_audit_append_only BEFORE UPDATE ON admin_audit
    FOR EACH ROW EXECUTE FUNCTION h2_append_only();

-- Scout collection checkpoints and run history.
CREATE TABLE source_cursor (
    source_id  text PRIMARY KEY REFERENCES source (id),
    cursor     text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE scout_run (
    id                 uuid PRIMARY KEY,
    source_id          text        NOT NULL REFERENCES source (id),
    status             text        NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','SKIPPED')),
    started_at         timestamptz NOT NULL,
    finished_at        timestamptz,
    items_seen         int         NOT NULL DEFAULT 0,
    items_changed      int         NOT NULL DEFAULT 0,
    enrichments_queued int         NOT NULL DEFAULT 0,
    message            text
);
CREATE INDEX scout_run_started_idx ON scout_run (started_at DESC);

-- When the last scheduled Scout run ended (one row): a replica's hourly tick runs the Scout once this is
-- H2_SCOUT_EVERY old. Runs started from the admin page are not recorded here.
CREATE TABLE scout_schedule (
    id            boolean     PRIMARY KEY DEFAULT true CHECK (id),
    last_ended_at timestamptz NOT NULL
);

-- ShedLock: work that must run on one replica at a time (Scout run, embedding indexer).
-- timestamp without time zone, as ShedLock's Postgres statements compare with timezone('utc', CURRENT_TIMESTAMP).
CREATE TABLE shedlock (
    name       varchar(64)  PRIMARY KEY,
    lock_until timestamp    NOT NULL,
    locked_at  timestamp    NOT NULL,
    locked_by  varchar(255) NOT NULL
);
