ALTER TABLE discovery_host ADD COLUMN reads_lists boolean NOT NULL DEFAULT false
    CHECK (NOT reads_lists OR kind = 'DIRECTORY');

ALTER TABLE list_seed ADD COLUMN origin text NOT NULL DEFAULT 'ADMINISTRATOR'
    CHECK (origin IN ('ADMINISTRATOR', 'DISCOVERY'));
ALTER TABLE list_seed ALTER COLUMN origin DROP DEFAULT;
ALTER TABLE list_seed ADD CONSTRAINT list_seed_discovery_check
    CHECK (origin = 'ADMINISTRATOR' OR kind = 'WEB_PAGE');

CREATE TABLE directory_list_entry (
    list_url     text        NOT NULL CHECK (length(list_url) <= 300),
    resource_id  uuid        NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    list_title   text        NOT NULL CHECK (length(list_title) BETWEEN 1 AND 300),
    heading_path text        NOT NULL CHECK (length(heading_path) <= 200),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (list_url, resource_id)
);
CREATE INDEX directory_list_entry_resource_idx ON directory_list_entry (resource_id);
CREATE INDEX directory_list_entry_updated_idx ON directory_list_entry (updated_at);
GRANT SELECT ON directory_list_entry TO h2_public;

ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check CHECK (action IN
    ('RESOLVE_REVIEW_ITEM', 'START_SCOUT_RUN', 'ADD_HOME_EXAMPLE', 'CHANGE_HOME_EXAMPLE', 'REMOVE_HOME_EXAMPLE',
     'DRAFT_HOME_EXAMPLES', 'RECHECK_ENRICHMENT', 'REQUEUE_ENRICHMENT', 'SKIP_ENRICHMENT', 'PRIORITISE_ENRICHMENT',
     'BACKFILL_CATEGORIES', 'CHANGE_SETTING', 'ADD_DISCOVERY_HOST', 'REMOVE_DISCOVERY_HOST',
     'CHANGE_RELATIONSHIP_TARGET', 'ADD_RESOURCE', 'EDIT_CANDIDATE', 'SET_FIELD', 'DROP_FIELD',
     'ADD_KNOWN_TARGET', 'EDIT_KNOWN_TARGET', 'REMOVE_KNOWN_TARGET', 'ADD_SHADOW_LIBRARY', 'REMOVE_SHADOW_LIBRARY',
     'REMOVE_RESOURCE', 'ADD_LIST_SEED', 'REMOVE_LIST_SEED', 'READ_DIRECTORY_LISTS'));
