COPY (
  WITH open AS (
    SELECT i.id, i.kind, i.candidate_url, i.reason, i.details,
           coalesce(i.details ->> 'type', CASE i.kind WHEN 'COURSE_CANDIDATE' THEN 'COURSE' ELSE 'OTHER' END) AS type_found,
           row_number() OVER (
             PARTITION BY i.kind, coalesce(i.details ->> 'type', '')
             ORDER BY md5(i.id::text)) AS n
      FROM review_item i
     WHERE i.kind IN ('RESOURCE_CANDIDATE', 'COURSE_CANDIDATE')
       AND i.status = 'OPEN'
       AND i.candidate_url IS NOT NULL
  )
  SELECT 'p' || lpad(row_number() OVER (ORDER BY n, kind, type_found, md5(id::text))::text, 3, '0') AS id,
         candidate_url AS item_url,
         kind,
         translate(coalesce(details ->> 'title', regexp_replace(reason, '^[^:]*: ', '')), E'\t\n\r"', '   ''') AS title_found,
         type_found,
         NULL AS decision,
         NULL AS expected_type,
         NULL AS expected_canonical_url,
         NULL AS expected_title,
         NULL AS expected_license,
         NULL AS expected_pricing,
         NULL AS expected_open_source
    FROM open
   ORDER BY n, kind, type_found, md5(id::text)
   LIMIT 120
) TO STDOUT WITH (FORMAT csv, DELIMITER E'\t', HEADER true);
