COPY (
  WITH decided AS (
    SELECT i.id, i.kind, i.status, i.candidate_url, i.reason, i.details
      FROM review_item i
     WHERE i.kind IN ('RESOURCE_CANDIDATE', 'COURSE_CANDIDATE')
       AND i.status IN ('ACCEPTED', 'REJECTED')
       AND i.resolved_by IS DISTINCT FROM 'scout'
       AND i.candidate_url IS NOT NULL
  ),
  clean AS (
    SELECT d.*, r.type AS r_type, r.canonical_url AS r_url, r.title AS r_title, r.license AS r_license,
           r.pricing AS r_pricing, r.open_source AS r_open_source
      FROM decided d
      LEFT JOIN resource r ON d.status = 'ACCEPTED' AND r.canonical_url = d.candidate_url
  )
  SELECT 'r' || lpad(row_number() OVER (ORDER BY md5(id::text))::text, 3, '0') AS id,
         candidate_url AS item_url,
         kind,
         translate(coalesce(details ->> 'title', regexp_replace(reason, '^[^:]*: ', '')), E'\t\n\r"', '   ''') AS title_found,
         coalesce(details ->> 'type', CASE kind WHEN 'COURSE_CANDIDATE' THEN 'COURSE' ELSE 'OTHER' END) AS type_found,
         CASE status WHEN 'ACCEPTED' THEN 'KEEP' ELSE 'REJECT' END AS decision,
         r_type AS expected_type,
         r_url AS expected_canonical_url,
         translate(r_title, E'\t\n\r"', '   ''') AS expected_title,
         r_license AS expected_license,
         r_pricing AS expected_pricing,
         r_open_source::text AS expected_open_source
    FROM clean
   ORDER BY 1
) TO STDOUT WITH (FORMAT csv, DELIMITER E'\t', HEADER true);
