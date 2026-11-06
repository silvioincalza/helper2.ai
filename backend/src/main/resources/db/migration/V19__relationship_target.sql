-- ADR 0064: an admin points a relationship candidate at the right resource, or adds that resource, from the review
-- list. A target a person chose is CURATED; the kind the model proposed stays.
ALTER TABLE relationship DROP CONSTRAINT relationship_origin_check;
ALTER TABLE relationship ADD CONSTRAINT relationship_origin_check
    CHECK (origin IN ('AI_EXTRACTED', 'SELF_DECLARED', 'CURATED'));

ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check CHECK (action IN
    ('RESOLVE_REVIEW_ITEM', 'START_SCOUT_RUN', 'ADD_HOME_EXAMPLE', 'CHANGE_HOME_EXAMPLE', 'REMOVE_HOME_EXAMPLE',
     'DRAFT_HOME_EXAMPLES', 'RECHECK_ENRICHMENT', 'REQUEUE_ENRICHMENT', 'SKIP_ENRICHMENT', 'PRIORITISE_ENRICHMENT',
     'BACKFILL_CATEGORIES', 'CHANGE_SETTING', 'ADD_DISCOVERY_HOST', 'REMOVE_DISCOVERY_HOST',
     'CHANGE_RELATIONSHIP_TARGET', 'ADD_RESOURCE'));
