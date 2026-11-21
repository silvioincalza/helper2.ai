-- UC-030 BR-013: what following the links of a read list page gave, kept with the read it belongs to.
ALTER TABLE list_seed_last_read ADD COLUMN followed jsonb NOT NULL DEFAULT '[]'
    CHECK (jsonb_typeof(followed) = 'array');
