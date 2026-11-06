-- The hosts discovery knows by name (ADR 0063): WRITING hosts publish articles about many products (a post there is an
-- ARTICLE candidate), DIRECTORY hosts list products (a page there is never kept). An admin adds and removes them in
-- the back office; each change is an admin_audit row. One row per host: a host is one kind or the other, never both.
CREATE TABLE discovery_host (
    host       text        PRIMARY KEY CHECK (length(host) BETWEEN 3 AND 253 AND host = lower(host)),
    kind       text        NOT NULL CHECK (kind IN ('WRITING', 'DIRECTORY')),
    added_by   text        NOT NULL,
    added_at   timestamptz NOT NULL,
    reason     text        CHECK (reason IS NULL OR length(reason) <= 500)
);

-- The lists that were in code until now (DiscoveryPages, ADR 0062).
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

ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check CHECK (action IN
    ('RESOLVE_REVIEW_ITEM', 'START_SCOUT_RUN', 'ADD_HOME_EXAMPLE', 'CHANGE_HOME_EXAMPLE', 'REMOVE_HOME_EXAMPLE',
     'DRAFT_HOME_EXAMPLES', 'RECHECK_ENRICHMENT', 'REQUEUE_ENRICHMENT', 'SKIP_ENRICHMENT', 'PRIORITISE_ENRICHMENT',
     'BACKFILL_CATEGORIES', 'CHANGE_SETTING', 'ADD_DISCOVERY_HOST', 'REMOVE_DISCOVERY_HOST'));
