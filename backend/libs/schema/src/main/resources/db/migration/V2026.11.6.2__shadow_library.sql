CREATE TABLE shadow_library (
    name     text        PRIMARY KEY CHECK (name ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$'),
    added_by text        NOT NULL,
    added_at timestamptz NOT NULL,
    reason   text        CHECK (reason IS NULL OR length(reason) <= 500)
);

INSERT INTO shadow_library (name, added_by, added_at) VALUES
    ('z-lib', 'migration', now()),
    ('z-library', 'migration', now()),
    ('zlibrary', 'migration', now()),
    ('1lib', 'migration', now()),
    ('singlelogin', 'migration', now()),
    ('libgen', 'migration', now()),
    ('sci-hub', 'migration', now()),
    ('annas-archive', 'migration', now()),
    ('pdfdrive', 'migration', now());

ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check CHECK (action IN
    ('RESOLVE_REVIEW_ITEM', 'START_SCOUT_RUN', 'ADD_HOME_EXAMPLE', 'CHANGE_HOME_EXAMPLE', 'REMOVE_HOME_EXAMPLE',
     'DRAFT_HOME_EXAMPLES', 'RECHECK_ENRICHMENT', 'REQUEUE_ENRICHMENT', 'SKIP_ENRICHMENT', 'PRIORITISE_ENRICHMENT',
     'BACKFILL_CATEGORIES', 'CHANGE_SETTING', 'ADD_DISCOVERY_HOST', 'REMOVE_DISCOVERY_HOST',
     'CHANGE_RELATIONSHIP_TARGET', 'ADD_RESOURCE', 'EDIT_CANDIDATE', 'SET_FIELD', 'DROP_FIELD',
     'ADD_KNOWN_TARGET', 'EDIT_KNOWN_TARGET', 'REMOVE_KNOWN_TARGET', 'ADD_SHADOW_LIBRARY', 'REMOVE_SHADOW_LIBRARY'));
