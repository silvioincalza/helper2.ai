ALTER TABLE resource DROP CONSTRAINT resource_curated_fields_check;
ALTER TABLE resource ADD CONSTRAINT resource_curated_fields_check
    CHECK (curated_fields <@ ARRAY['type', 'pricing', 'license', 'openSource', 'techLevel', 'audience',
                                   'description', 'language', 'publishedAt']::text[]);
