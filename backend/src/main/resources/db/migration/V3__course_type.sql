-- ADR 0032 §8: courses and certifications.
-- COURSE joins the resource types. Its card uses existing columns and labels: the platform is a PLATFORM label,
-- level is tech_level, pricing is pricing, credential and format are tags (see R__courses.sql). No new column.
ALTER TABLE resource DROP CONSTRAINT resource_type_check;
ALTER TABLE resource ADD CONSTRAINT resource_type_check CHECK (type IN
    ('PRODUCT','MODEL','AGENT','PROMPT','SKILL','MCP_SERVER','LIBRARY','DATASET','ARTICLE','PAPER','NEWS','EVENT','COURSE','OTHER'));

-- Course pages the Scout collector finds go to review, never straight to the catalog. They have no resource yet,
-- so the item keeps the page's canonical URL; one item per URL ever, so a rejected page is not proposed again.
ALTER TABLE review_item DROP CONSTRAINT review_item_kind_check;
ALTER TABLE review_item ADD CONSTRAINT review_item_kind_check CHECK (kind IN
    ('ENRICHMENT_VALIDATION','SOURCE_CONFLICT','RELATIONSHIP_CANDIDATE','USER_REPORT','COURSE_CANDIDATE'));
ALTER TABLE review_item ADD COLUMN candidate_url text;
CREATE UNIQUE INDEX review_item_candidate_idx ON review_item (kind, candidate_url) WHERE candidate_url IS NOT NULL;
