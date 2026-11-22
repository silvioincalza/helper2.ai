-- UC-030 BR-012: the status checks in a row that said a web page is gone, and when the first of them was.
-- IF NOT EXISTS: staging ran this change as V2026.11.9.5 before it was renamed after the release that ships it.
ALTER TABLE list_seed ADD COLUMN IF NOT EXISTS gone_checks integer NOT NULL DEFAULT 0 CHECK (gone_checks >= 0);
ALTER TABLE list_seed ADD COLUMN IF NOT EXISTS gone_since timestamptz;
ALTER TABLE list_seed DROP CONSTRAINT IF EXISTS list_seed_gone_since_check;
ALTER TABLE list_seed ADD CONSTRAINT list_seed_gone_since_check
    CHECK ((gone_checks = 0) = (gone_since IS NULL) AND (gone_checks = 0 OR kind = 'WEB_PAGE'));
