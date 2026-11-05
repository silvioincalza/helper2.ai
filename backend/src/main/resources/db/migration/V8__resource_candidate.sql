-- Product pages the Tavily discovery collector finds (ADR 0051) wait in the review queue as RESOURCE_CANDIDATE, once
-- per URL like course candidates (review_item_candidate_idx). Accepting one indexes it.
ALTER TABLE review_item DROP CONSTRAINT review_item_kind_check;
ALTER TABLE review_item ADD CONSTRAINT review_item_kind_check CHECK (kind IN
    ('ENRICHMENT_VALIDATION','RELATIONSHIP_CANDIDATE','USER_REPORT','COURSE_CANDIDATE','EXAMPLE_CANDIDATE',
     'RESOURCE_CANDIDATE'));
