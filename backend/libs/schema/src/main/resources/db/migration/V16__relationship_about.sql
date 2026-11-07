-- ADR 0062: an article, a news item or a paper names the models and products it is about (ABOUT), from the
-- article to its subject. The other kinds stay for the rest; the validator keeps them apart by type.
ALTER TABLE relationship DROP CONSTRAINT relationship_kind_check;
ALTER TABLE relationship ADD CONSTRAINT relationship_kind_check
    CHECK (kind IN ('INTEGRATES_WITH', 'USES', 'SUPPORTS', 'COMPATIBLE_WITH', 'ABOUT'));
