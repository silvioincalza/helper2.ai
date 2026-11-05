-- AI-drafted home examples (ADR 0049 §6): a draft waits in the review queue as EXAMPLE_CANDIDATE, and an admin starts
-- a draft run (DRAFT_HOME_EXAMPLES in the audit trail).
ALTER TABLE review_item DROP CONSTRAINT review_item_kind_check;
ALTER TABLE review_item ADD CONSTRAINT review_item_kind_check CHECK (kind IN
    ('ENRICHMENT_VALIDATION','RELATIONSHIP_CANDIDATE','USER_REPORT','COURSE_CANDIDATE','EXAMPLE_CANDIDATE'));

ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check CHECK (action IN
    ('RESOLVE_REVIEW_ITEM', 'START_SCOUT_RUN', 'ADD_HOME_EXAMPLE', 'CHANGE_HOME_EXAMPLE', 'REMOVE_HOME_EXAMPLE',
     'DRAFT_HOME_EXAMPLES'));
