-- UC-030 BR-013: what following the links of a read list page gave, kept with the read it belongs to.
-- IF NOT EXISTS: staging ran this change as V2026.11.9.4 before it was renamed after the release that ships it.
ALTER TABLE list_seed_last_read ADD COLUMN IF NOT EXISTS followed jsonb NOT NULL DEFAULT '[]'
    CHECK (jsonb_typeof(followed) = 'array');
