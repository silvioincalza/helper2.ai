-- UC-030 BR-012: the status checks in a row that said a web page is gone, and when the first of them was.
ALTER TABLE list_seed ADD COLUMN gone_checks integer NOT NULL DEFAULT 0 CHECK (gone_checks >= 0);
ALTER TABLE list_seed ADD COLUMN gone_since timestamptz;
ALTER TABLE list_seed ADD CONSTRAINT list_seed_gone_since_check
    CHECK ((gone_checks = 0) = (gone_since IS NULL) AND (gone_checks = 0 OR kind = 'WEB_PAGE'));
