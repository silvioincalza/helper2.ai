-- Categories of the AI world become a search filter (ADR 0053). A resource's categories are labels of their own kind,
-- CATEGORY, apart from its tags: tags are capped at 20 per resource, categories must not take that room, and a
-- resource can have several. They are assigned by code (CategoryMatcher) on every catalog upsert and by the backfill.
ALTER TABLE resource_label DROP CONSTRAINT resource_label_kind_check;
ALTER TABLE resource_label ADD CONSTRAINT resource_label_kind_check
    CHECK (kind IN ('TAG', 'USE_CASE', 'PLATFORM', 'CATEGORY'));

-- One-off catalog jobs that run once per configuration: the category backfill writes the fingerprint of the category
-- words and rules it assigned with, so it runs again only when they change.
CREATE TABLE catalog_marker (
    name       text PRIMARY KEY,
    value      text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- The admin action that assigns every category again (ADR 0053).
ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check CHECK (action IN
    ('RESOLVE_REVIEW_ITEM', 'START_SCOUT_RUN', 'ADD_HOME_EXAMPLE', 'CHANGE_HOME_EXAMPLE', 'REMOVE_HOME_EXAMPLE',
     'DRAFT_HOME_EXAMPLES', 'RECHECK_ENRICHMENT', 'BACKFILL_CATEGORIES'));
