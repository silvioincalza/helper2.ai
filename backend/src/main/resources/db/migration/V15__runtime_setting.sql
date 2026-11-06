-- Runtime settings (ADR 0060): a few spending limits an administrator changes from the back office without a deploy.
-- Append-only: the current value of a setting is its newest row; a row with no value means "back to the default"
-- (the H2_ variable). The version grows with every change of any setting; a setting's version is its newest row's.
-- Rows are never updated (trigger); past their retention they are deleted, except each setting's newest.
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

-- A setting change in the audit trail says the value before and after, and why (optional).
ALTER TABLE admin_audit
    ADD COLUMN before_value text CHECK (before_value IS NULL OR length(before_value) <= 100),
    ADD COLUMN after_value  text CHECK (after_value IS NULL OR length(after_value) <= 100),
    ADD COLUMN reason       text CHECK (reason IS NULL OR length(reason) <= 500);

-- Every action of V11, the enrichment queue actions of the queue stream (V12), and CHANGE_SETTING: the same list
-- whichever migration runs first.
ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check CHECK (action IN
    ('RESOLVE_REVIEW_ITEM', 'START_SCOUT_RUN', 'ADD_HOME_EXAMPLE', 'CHANGE_HOME_EXAMPLE', 'REMOVE_HOME_EXAMPLE',
     'DRAFT_HOME_EXAMPLES', 'RECHECK_ENRICHMENT', 'REQUEUE_ENRICHMENT', 'SKIP_ENRICHMENT', 'PRIORITISE_ENRICHMENT',
     'BACKFILL_CATEGORIES', 'CHANGE_SETTING'));
