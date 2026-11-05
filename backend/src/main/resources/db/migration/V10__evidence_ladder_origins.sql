-- ADR 0056: AI_ESTIMATED is a judgement field (audience, tech level) the model estimated without a quote; SELF_DECLARED
-- is a fact or a relationship that only the product's own pages state. A summary claim that cites such a page is
-- self-declared too.
ALTER TABLE field_assertion DROP CONSTRAINT field_assertion_origin_check;
ALTER TABLE field_assertion ADD CONSTRAINT field_assertion_origin_check
    CHECK (origin IN ('AI_EXTRACTED','AI_ESTIMATED','SELF_DECLARED','CURATED'));
ALTER TABLE relationship DROP CONSTRAINT relationship_origin_check;
ALTER TABLE relationship ADD CONSTRAINT relationship_origin_check CHECK (origin IN ('AI_EXTRACTED','SELF_DECLARED'));
ALTER TABLE summary_claim ADD COLUMN self_declared boolean NOT NULL DEFAULT false;

-- One-time maintenance tasks, claimed once across replicas (the first re-check of stored enrichment answers, ADR 0056).
CREATE TABLE maintenance_task (
    name    text        PRIMARY KEY CHECK (length(name) <= 100),
    done_at timestamptz NOT NULL DEFAULT now()
);

-- An admin can re-check the stored enrichment answers by code (RECHECK_ENRICHMENT).
ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check CHECK (action IN
    ('RESOLVE_REVIEW_ITEM', 'START_SCOUT_RUN', 'ADD_HOME_EXAMPLE', 'CHANGE_HOME_EXAMPLE', 'REMOVE_HOME_EXAMPLE',
     'DRAFT_HOME_EXAMPLES', 'RECHECK_ENRICHMENT'));
