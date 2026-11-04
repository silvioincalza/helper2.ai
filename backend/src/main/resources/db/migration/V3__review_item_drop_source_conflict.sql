-- SOURCE_CONFLICT is no longer a review kind: no code ever opened one. A model that contradicts a source opens an
-- ENRICHMENT_VALIDATION item (ADR 0019); sources that disagree at search time are shown to the user (ADR 0008).
DELETE
FROM review_item
WHERE kind = 'SOURCE_CONFLICT';
ALTER TABLE review_item DROP CONSTRAINT review_item_kind_check;
ALTER TABLE review_item
    ADD CONSTRAINT review_item_kind_check CHECK (kind IN
                                                 ('ENRICHMENT_VALIDATION', 'RELATIONSHIP_CANDIDATE', 'USER_REPORT',
                                                  'COURSE_CANDIDATE'));
