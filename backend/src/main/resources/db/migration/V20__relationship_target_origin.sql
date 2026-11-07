-- ADR 0064: the target an admin chose is CURATED in its own column. The relationship's origin stays the model's,
-- since the kind is still AI-written: the resource page keeps its AI label. A relationship V19 marked CURATED came
-- from a model before the admin's change.
ALTER TABLE relationship ADD COLUMN target_origin text CHECK (target_origin IN ('CURATED'));
UPDATE relationship SET target_origin = 'CURATED', origin = 'AI_EXTRACTED' WHERE origin = 'CURATED';

ALTER TABLE relationship DROP CONSTRAINT relationship_origin_check;
ALTER TABLE relationship ADD CONSTRAINT relationship_origin_check CHECK (origin IN ('AI_EXTRACTED', 'SELF_DECLARED'));
