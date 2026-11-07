-- ADR 0066: an admin sets a resource's own value of a field. The value is in the resource's column, and the field is
-- listed here, so no source's record and no enrichment run changes it. A CURATED field_assertion says where it came
-- from. Only fields with a column of their own; platforms and use cases are labels rebuilt from the records.
ALTER TABLE resource ADD COLUMN curated_fields text[] NOT NULL DEFAULT '{}'
    CHECK (curated_fields <@ ARRAY['type', 'pricing', 'license', 'openSource', 'techLevel', 'audience']::text[]);

ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check CHECK (action IN
    ('RESOLVE_REVIEW_ITEM', 'START_SCOUT_RUN', 'ADD_HOME_EXAMPLE', 'CHANGE_HOME_EXAMPLE', 'REMOVE_HOME_EXAMPLE',
     'DRAFT_HOME_EXAMPLES', 'RECHECK_ENRICHMENT', 'REQUEUE_ENRICHMENT', 'SKIP_ENRICHMENT', 'PRIORITISE_ENRICHMENT',
     'BACKFILL_CATEGORIES', 'CHANGE_SETTING', 'ADD_DISCOVERY_HOST', 'REMOVE_DISCOVERY_HOST',
     'CHANGE_RELATIONSHIP_TARGET', 'ADD_RESOURCE', 'EDIT_CANDIDATE', 'SET_FIELD', 'DROP_FIELD'));
