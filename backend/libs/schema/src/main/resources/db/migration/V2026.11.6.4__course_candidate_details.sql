ALTER TABLE review_item DROP CONSTRAINT review_item_check1;
ALTER TABLE review_item ADD CONSTRAINT review_item_check1
    CHECK (details IS NULL OR kind IN ('RESOURCE_CANDIDATE', 'COURSE_CANDIDATE', 'EXAMPLE_CANDIDATE',
                                       'KNOWN_TARGET_CANDIDATE'));

UPDATE review_item
   SET details = jsonb_strip_nulls(jsonb_build_object(
           'version', 2,
           'sourceId', substring(reason FROM '^New course or certification page found by ([^:]+): '),
           'url', candidate_url,
           'title', substring(reason FROM '^New course or certification page found by [^:]+: (.+)$'),
           'type', 'COURSE',
           'tags', '[]'::jsonb,
           'snippet', NULLIF(btrim(substring(note FROM E'\nSnippet: ([\\s\\S]*)$')), ''),
           'platform', NULLIF(btrim(substring(note FROM E'\nPlatform: ([^\n]*)')), ''),
           'foundBy', jsonb_build_array(jsonb_build_object(
               'sourceId', substring(reason FROM '^New course or certification page found by ([^:]+): '))))),
       note = NULL
 WHERE kind = 'COURSE_CANDIDATE'
   AND status = 'OPEN'
   AND details IS NULL
   AND candidate_url IS NOT NULL
   AND note LIKE 'URL: %'
   AND reason ~ '^New course or certification page found by [^:]+: .+';
