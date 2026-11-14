INSERT INTO source (id, mode) VALUES ('curated', 'CURATED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ignite.microsoft.com?event=microsoft-ignite-2026'), 'https://ignite.microsoft.com?event=microsoft-ignite-2026', 'https://ignite.microsoft.com/', 'Microsoft Ignite 2026', NULL, 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2026-11-17T00:00:00Z', '2026-11-20T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ignite.microsoft.com?event=microsoft-ignite-2026');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ignite.microsoft.com?event=microsoft-ignite-2026'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://ignite.microsoft.com?event=microsoft-ignite-2026', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ignite.microsoft.com?event=microsoft-ignite-2026'), 'https://ignite.microsoft.com/', 'https://ignite.microsoft.com?event=microsoft-ignite-2026', 'Microsoft Ignite 2026', NULL, 'EVENT',
        '2026-11-17T00:00:00Z', '2026-11-20T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'San Francisco'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ignite.microsoft.com?event=microsoft-ignite-2026') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ignite.microsoft.com?event=microsoft-ignite-2026') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ignite.microsoft.com?event=microsoft-ignite-2026'), 'eventDates', '2026-11-17/2026-11-20', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://ignite.microsoft.com/', '2026-11-12T00:00:00Z', 'Microsoft Ignite | November 17–20, 2026 in San Francisco, CA') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://aws.amazon.com/events/reinvent?event=aws-re-invent-2026'), 'https://aws.amazon.com/events/reinvent?event=aws-re-invent-2026', 'https://aws.amazon.com/events/reinvent', 'AWS re:Invent 2026', 'Amazon Web Services'' cloud and AI conference in Las Vegas, with a free livestream. Topics include AI, security, migration and developer productivity.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2026-11-30T00:00:00Z', '2026-12-04T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://aws.amazon.com/events/reinvent?event=aws-re-invent-2026');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://aws.amazon.com/events/reinvent?event=aws-re-invent-2026'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://aws.amazon.com/events/reinvent?event=aws-re-invent-2026', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://aws.amazon.com/events/reinvent?event=aws-re-invent-2026'), 'https://aws.amazon.com/events/reinvent', 'https://aws.amazon.com/events/reinvent?event=aws-re-invent-2026', 'AWS re:Invent 2026', 'Amazon Web Services'' cloud and AI conference in Las Vegas, with a free livestream. Topics include AI, security, migration and developer productivity.', 'EVENT',
        '2026-11-30T00:00:00Z', '2026-12-04T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Las Vegas'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://aws.amazon.com/events/reinvent?event=aws-re-invent-2026') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://aws.amazon.com/events/reinvent?event=aws-re-invent-2026') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://aws.amazon.com/events/reinvent?event=aws-re-invent-2026'), 'eventDates', '2026-11-30/2026-12-04', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://aws.amazon.com/events/reinvent', '2026-11-12T00:00:00Z', 'AWS re:Invent 2026 | The Premier Cloud and AI Conference | Nov 30-Dec 4, Las Vegas') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://nvidia.com/gtc/dc?event=gtc-washington-d-c-2026'), 'https://nvidia.com/gtc/dc?event=gtc-washington-d-c-2026', 'https://www.nvidia.com/gtc/dc/', 'GTC Washington, D.C. 2026', 'NVIDIA''s GTC conference and expo at the Ronald Reagan Building and International Trade Center, after a day of workshops. It is for developers, researchers, industry partners, public sector professionals and policymakers.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2026-11-30T00:00:00Z', '2026-12-03T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://nvidia.com/gtc/dc?event=gtc-washington-d-c-2026');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://nvidia.com/gtc/dc?event=gtc-washington-d-c-2026'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://nvidia.com/gtc/dc?event=gtc-washington-d-c-2026', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://nvidia.com/gtc/dc?event=gtc-washington-d-c-2026'), 'https://www.nvidia.com/gtc/dc/', 'https://nvidia.com/gtc/dc?event=gtc-washington-d-c-2026', 'GTC Washington, D.C. 2026', 'NVIDIA''s GTC conference and expo at the Ronald Reagan Building and International Trade Center, after a day of workshops. It is for developers, researchers, industry partners, public sector professionals and policymakers.', 'EVENT',
        '2026-11-30T00:00:00Z', '2026-12-03T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Washington, D.C.'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://nvidia.com/gtc/dc?event=gtc-washington-d-c-2026') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://nvidia.com/gtc/dc?event=gtc-washington-d-c-2026') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://nvidia.com/gtc/dc?event=gtc-washington-d-c-2026'), 'eventDates', '2026-11-30/2026-12-03', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://www.nvidia.com/gtc/dc/', '2026-11-12T00:00:00Z', 'November 30–December 3, 2026') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai-dev.deeplearning.ai?event=ai-dev-nyc-2026'), 'https://ai-dev.deeplearning.ai?event=ai-dev-nyc-2026', 'https://ai-dev.deeplearning.ai/', 'AI Dev NYC 2026', 'A technical, hands-on conference for people building with AI, hosted by the DeepLearning.AI team and Andrew Ng at Chelsea Piers in New York.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2026-11-30T00:00:00Z', '2026-12-01T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai-dev.deeplearning.ai?event=ai-dev-nyc-2026');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai-dev.deeplearning.ai?event=ai-dev-nyc-2026'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://ai-dev.deeplearning.ai?event=ai-dev-nyc-2026', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai-dev.deeplearning.ai?event=ai-dev-nyc-2026'), 'https://ai-dev.deeplearning.ai/', 'https://ai-dev.deeplearning.ai?event=ai-dev-nyc-2026', 'AI Dev NYC 2026', 'A technical, hands-on conference for people building with AI, hosted by the DeepLearning.AI team and Andrew Ng at Chelsea Piers in New York.', 'EVENT',
        '2026-11-30T00:00:00Z', '2026-12-01T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'New York'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai-dev.deeplearning.ai?event=ai-dev-nyc-2026') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai-dev.deeplearning.ai?event=ai-dev-nyc-2026') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai-dev.deeplearning.ai?event=ai-dev-nyc-2026'), 'eventDates', '2026-11-30/2026-12-01', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://ai-dev.deeplearning.ai/', '2026-11-12T00:00:00Z', 'NOVEMBER 30 – DECEMBER 1, 2026 ... CHELSEA PIERS, NYC') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://gitex.com?event=gitex-global-2026'), 'https://gitex.com?event=gitex-global-2026', 'https://www.gitex.com/', 'GITEX GLOBAL 2026', 'A tech, AI and startup show in Dubai: a one-day summit at the Dubai World Trade Centre, then an expo at the Dubai Exhibition Centre, Expo City Dubai.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2026-12-07T00:00:00Z', '2026-12-11T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://gitex.com?event=gitex-global-2026');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://gitex.com?event=gitex-global-2026'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://gitex.com?event=gitex-global-2026', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://gitex.com?event=gitex-global-2026'), 'https://www.gitex.com/', 'https://gitex.com?event=gitex-global-2026', 'GITEX GLOBAL 2026', 'A tech, AI and startup show in Dubai: a one-day summit at the Dubai World Trade Centre, then an expo at the Dubai Exhibition Centre, Expo City Dubai.', 'EVENT',
        '2026-12-07T00:00:00Z', '2026-12-11T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Dubai'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://gitex.com?event=gitex-global-2026') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://gitex.com?event=gitex-global-2026') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://gitex.com?event=gitex-global-2026'), 'eventDates', '2026-12-07/2026-12-11', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://www.gitex.com/', '2026-11-12T00:00:00Z', 'GITEX GLOBAL | 7-11 DEC 2026 | DUBAI') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://newyork.theaisummit.com?event=the-ai-summit-new-york-2026'), 'https://newyork.theaisummit.com?event=the-ai-summit-new-york-2026', 'https://newyork.theaisummit.com/', 'The AI Summit New York 2026', 'A two-day AI conference for enterprise leaders at the Javits Center in New York, on deployment, return on investment and governance.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2026-12-09T00:00:00Z', '2026-12-10T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://newyork.theaisummit.com?event=the-ai-summit-new-york-2026');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://newyork.theaisummit.com?event=the-ai-summit-new-york-2026'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://newyork.theaisummit.com?event=the-ai-summit-new-york-2026', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://newyork.theaisummit.com?event=the-ai-summit-new-york-2026'), 'https://newyork.theaisummit.com/', 'https://newyork.theaisummit.com?event=the-ai-summit-new-york-2026', 'The AI Summit New York 2026', 'A two-day AI conference for enterprise leaders at the Javits Center in New York, on deployment, return on investment and governance.', 'EVENT',
        '2026-12-09T00:00:00Z', '2026-12-10T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'New York'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://newyork.theaisummit.com?event=the-ai-summit-new-york-2026') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://newyork.theaisummit.com?event=the-ai-summit-new-york-2026') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://newyork.theaisummit.com?event=the-ai-summit-new-york-2026'), 'eventDates', '2026-12-09/2026-12-10', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://newyork.theaisummit.com/', '2026-11-12T00:00:00Z', 'December 9-10, 2026 | Javits Center, New York') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ces.tech?event=ces-2027'), 'https://ces.tech?event=ces-2027', 'https://www.ces.tech/', 'CES 2027', 'A technology trade show in Las Vegas, owned and produced by the Consumer Technology Association. Featured topics include artificial intelligence and vehicle technology.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-01-06T00:00:00Z', '2027-01-09T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ces.tech?event=ces-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ces.tech?event=ces-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://ces.tech?event=ces-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ces.tech?event=ces-2027'), 'https://www.ces.tech/', 'https://ces.tech?event=ces-2027', 'CES 2027', 'A technology trade show in Las Vegas, owned and produced by the Consumer Technology Association. Featured topics include artificial intelligence and vehicle technology.', 'EVENT',
        '2027-01-06T00:00:00Z', '2027-01-09T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Las Vegas'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ces.tech?event=ces-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ces.tech?event=ces-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ces.tech?event=ces-2027'), 'eventDates', '2027-01-06/2027-01-09', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://www.ces.tech/', '2026-11-12T00:00:00Z', 'January 6-9 Las Vegas, NV ... Registration for CES 2027 is now open!') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://qatar.websummit.com?event=web-summit-qatar-2027'), 'https://qatar.websummit.com?event=web-summit-qatar-2027', 'https://qatar.websummit.com/', 'Web Summit Qatar 2027', 'The Doha edition of Web Summit, a technology conference at the Doha Exhibition and Convention Center for founders, investors and media.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-01-31T00:00:00Z', '2027-02-03T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://qatar.websummit.com?event=web-summit-qatar-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://qatar.websummit.com?event=web-summit-qatar-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://qatar.websummit.com?event=web-summit-qatar-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://qatar.websummit.com?event=web-summit-qatar-2027'), 'https://qatar.websummit.com/', 'https://qatar.websummit.com?event=web-summit-qatar-2027', 'Web Summit Qatar 2027', 'The Doha edition of Web Summit, a technology conference at the Doha Exhibition and Convention Center for founders, investors and media.', 'EVENT',
        '2027-01-31T00:00:00Z', '2027-02-03T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Doha'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://qatar.websummit.com?event=web-summit-qatar-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://qatar.websummit.com?event=web-summit-qatar-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://qatar.websummit.com?event=web-summit-qatar-2027'), 'eventDates', '2027-01-31/2027-02-03', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://qatar.websummit.com/', '2026-11-12T00:00:00Z', 'Returning to Doha from January 31 to February 3, 2027.') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai.engineer?event=ai-engineer-europe-2027'), 'https://ai.engineer?event=ai-engineer-europe-2027', 'https://www.ai.engineer/', 'AI Engineer Europe 2027', 'A conference for AI engineers at ExCeL in London, part of the AI Engineer conference series for software engineers building with AI.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-02-17T00:00:00Z', '2027-02-19T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai.engineer?event=ai-engineer-europe-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai.engineer?event=ai-engineer-europe-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://ai.engineer?event=ai-engineer-europe-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai.engineer?event=ai-engineer-europe-2027'), 'https://www.ai.engineer/', 'https://ai.engineer?event=ai-engineer-europe-2027', 'AI Engineer Europe 2027', 'A conference for AI engineers at ExCeL in London, part of the AI Engineer conference series for software engineers building with AI.', 'EVENT',
        '2027-02-17T00:00:00Z', '2027-02-19T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'London'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai.engineer?event=ai-engineer-europe-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai.engineer?event=ai-engineer-europe-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai.engineer?event=ai-engineer-europe-2027'), 'eventDates', '2027-02-17/2027-02-19', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://www.ai.engineer/', '2026-11-12T00:00:00Z', '...back with 3000 of Europe''s top AI Engineers at ExCeL, February 17–19, 2027!') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://mwcbarcelona.com?event=mwc-barcelona-2027'), 'https://mwcbarcelona.com?event=mwc-barcelona-2027', 'https://www.mwcbarcelona.com/', 'MWC Barcelona 2027', 'A connectivity industry event hosted by the GSMA at Fira Gran Via in Barcelona. Its themes range from AI and cloud to telecoms, devices and enterprise technology.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-03-01T00:00:00Z', '2027-03-04T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://mwcbarcelona.com?event=mwc-barcelona-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://mwcbarcelona.com?event=mwc-barcelona-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://mwcbarcelona.com?event=mwc-barcelona-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://mwcbarcelona.com?event=mwc-barcelona-2027'), 'https://www.mwcbarcelona.com/', 'https://mwcbarcelona.com?event=mwc-barcelona-2027', 'MWC Barcelona 2027', 'A connectivity industry event hosted by the GSMA at Fira Gran Via in Barcelona. Its themes range from AI and cloud to telecoms, devices and enterprise technology.', 'EVENT',
        '2027-03-01T00:00:00Z', '2027-03-04T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Barcelona'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://mwcbarcelona.com?event=mwc-barcelona-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://mwcbarcelona.com?event=mwc-barcelona-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://mwcbarcelona.com?event=mwc-barcelona-2027'), 'eventDates', '2027-03-01/2027-03-04', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://www.mwcbarcelona.com/', '2026-11-12T00:00:00Z', '1 - 4 March, 2027') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://humanx.co?event=humanx-las-vegas-2027'), 'https://humanx.co?event=humanx-las-vegas-2027', 'https://www.humanx.co/', 'HumanX Las Vegas 2027', 'An AI conference for decision-makers and industry leaders in Las Vegas, with an expo at Mandalay Bay.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-03-08T00:00:00Z', '2027-03-10T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://humanx.co?event=humanx-las-vegas-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://humanx.co?event=humanx-las-vegas-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://humanx.co?event=humanx-las-vegas-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://humanx.co?event=humanx-las-vegas-2027'), 'https://www.humanx.co/', 'https://humanx.co?event=humanx-las-vegas-2027', 'HumanX Las Vegas 2027', 'An AI conference for decision-makers and industry leaders in Las Vegas, with an expo at Mandalay Bay.', 'EVENT',
        '2027-03-08T00:00:00Z', '2027-03-10T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Las Vegas'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://humanx.co?event=humanx-las-vegas-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://humanx.co?event=humanx-las-vegas-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://humanx.co?event=humanx-las-vegas-2027'), 'eventDates', '2027-03-08/2027-03-10', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://www.humanx.co/', '2026-11-12T00:00:00Z', 'March 8-10, 2027 · Mandalay Bay, Las Vegas') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://nvidia.com/gtc?event=gtc-2027'), 'https://nvidia.com/gtc?event=gtc-2027', 'https://www.nvidia.com/gtc/', 'GTC 2027', 'NVIDIA''s AI conference, in San Jose. GTC brings together developers, researchers and business leaders; its sessions cover physical AI, AI factories, agentic AI and inference.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-03-15T00:00:00Z', '2027-03-18T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://nvidia.com/gtc?event=gtc-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://nvidia.com/gtc?event=gtc-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://nvidia.com/gtc?event=gtc-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://nvidia.com/gtc?event=gtc-2027'), 'https://www.nvidia.com/gtc/', 'https://nvidia.com/gtc?event=gtc-2027', 'GTC 2027', 'NVIDIA''s AI conference, in San Jose. GTC brings together developers, researchers and business leaders; its sessions cover physical AI, AI factories, agentic AI and inference.', 'EVENT',
        '2027-03-15T00:00:00Z', '2027-03-18T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'San Jose'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://nvidia.com/gtc?event=gtc-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://nvidia.com/gtc?event=gtc-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://nvidia.com/gtc?event=gtc-2027'), 'eventDates', '2027-03-15/2027-03-18', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://www.nvidia.com/gtc/', '2026-11-12T00:00:00Z', 'And don’t forget to save the date for GTC 2027, coming to San Jose on March 15–18.') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://interrupt.langchain.com?event=interrupt-27'), 'https://interrupt.langchain.com?event=interrupt-27', 'https://interrupt.langchain.com/', 'Interrupt 27', 'LangChain''s conference on AI agents at Fort Mason in San Francisco, with keynotes, technical talks and hands-on workshops for engineers, AI leaders and researchers.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-03-24T00:00:00Z', '2027-03-25T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://interrupt.langchain.com?event=interrupt-27');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://interrupt.langchain.com?event=interrupt-27'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://interrupt.langchain.com?event=interrupt-27', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://interrupt.langchain.com?event=interrupt-27'), 'https://interrupt.langchain.com/', 'https://interrupt.langchain.com?event=interrupt-27', 'Interrupt 27', 'LangChain''s conference on AI agents at Fort Mason in San Francisco, with keynotes, technical talks and hands-on workshops for engineers, AI leaders and researchers.', 'EVENT',
        '2027-03-24T00:00:00Z', '2027-03-25T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'San Francisco'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://interrupt.langchain.com?event=interrupt-27') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://interrupt.langchain.com?event=interrupt-27') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://interrupt.langchain.com?event=interrupt-27'), 'eventDates', '2027-03-24/2027-03-25', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://interrupt.langchain.com/', '2026-11-12T00:00:00Z', 'Fort Mason, San Francisco ... March 24–25, 2027') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://reg.googlecloudevents.com/flow/googlecloud/next27/vegas/page/home?event=google-cloud-next-2027'), 'https://reg.googlecloudevents.com/flow/googlecloud/next27/vegas/page/home?event=google-cloud-next-2027', 'https://reg.googlecloudevents.com/flow/googlecloud/next27/vegas/page/home', 'Google Cloud Next 2027', 'Google Cloud''s conference on AI and cloud at the Mandalay Bay Convention Center in Las Vegas, for attendees in person or online.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-04-13T00:00:00Z', '2027-04-15T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://reg.googlecloudevents.com/flow/googlecloud/next27/vegas/page/home?event=google-cloud-next-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://reg.googlecloudevents.com/flow/googlecloud/next27/vegas/page/home?event=google-cloud-next-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://reg.googlecloudevents.com/flow/googlecloud/next27/vegas/page/home?event=google-cloud-next-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://reg.googlecloudevents.com/flow/googlecloud/next27/vegas/page/home?event=google-cloud-next-2027'), 'https://reg.googlecloudevents.com/flow/googlecloud/next27/vegas/page/home', 'https://reg.googlecloudevents.com/flow/googlecloud/next27/vegas/page/home?event=google-cloud-next-2027', 'Google Cloud Next 2027', 'Google Cloud''s conference on AI and cloud at the Mandalay Bay Convention Center in Las Vegas, for attendees in person or online.', 'EVENT',
        '2027-04-13T00:00:00Z', '2027-04-15T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Las Vegas'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://reg.googlecloudevents.com/flow/googlecloud/next27/vegas/page/home?event=google-cloud-next-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://reg.googlecloudevents.com/flow/googlecloud/next27/vegas/page/home?event=google-cloud-next-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://reg.googlecloudevents.com/flow/googlecloud/next27/vegas/page/home?event=google-cloud-next-2027'), 'eventDates', '2027-04-13/2027-04-15', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://reg.googlecloudevents.com/flow/googlecloud/next27/vegas/page/home', '2026-11-12T00:00:00Z', 'April 13–15, 2027* ... Google Cloud Next is returning to Mandalay Bay Convention Center in Las Vegas.') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://vancouver.websummit.com?event=web-summit-vancouver-2027'), 'https://vancouver.websummit.com?event=web-summit-vancouver-2027', 'https://vancouver.websummit.com/', 'Web Summit Vancouver 2027', 'The Vancouver edition of Web Summit, at the Vancouver Convention Center, for founders, investors and innovators.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-05-25T00:00:00Z', '2027-05-28T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://vancouver.websummit.com?event=web-summit-vancouver-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://vancouver.websummit.com?event=web-summit-vancouver-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://vancouver.websummit.com?event=web-summit-vancouver-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://vancouver.websummit.com?event=web-summit-vancouver-2027'), 'https://vancouver.websummit.com/', 'https://vancouver.websummit.com?event=web-summit-vancouver-2027', 'Web Summit Vancouver 2027', 'The Vancouver edition of Web Summit, at the Vancouver Convention Center, for founders, investors and innovators.', 'EVENT',
        '2027-05-25T00:00:00Z', '2027-05-28T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Vancouver'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://vancouver.websummit.com?event=web-summit-vancouver-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://vancouver.websummit.com?event=web-summit-vancouver-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://vancouver.websummit.com?event=web-summit-vancouver-2027'), 'eventDates', '2027-05-25/2027-05-28', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://vancouver.websummit.com/', '2026-11-12T00:00:00Z', 'Join us at the Vancouver Convention Center, May 25-28, 2027.') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://superai.com?event=superai-london-2027'), 'https://superai.com?event=superai-london-2027', 'https://www.superai.com/', 'SuperAI London 2027', NULL, 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-05-26T00:00:00Z', '2027-05-27T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://superai.com?event=superai-london-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://superai.com?event=superai-london-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://superai.com?event=superai-london-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://superai.com?event=superai-london-2027'), 'https://www.superai.com/', 'https://superai.com?event=superai-london-2027', 'SuperAI London 2027', NULL, 'EVENT',
        '2027-05-26T00:00:00Z', '2027-05-27T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'London'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://superai.com?event=superai-london-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://superai.com?event=superai-london-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://superai.com?event=superai-london-2027'), 'eventDates', '2027-05-26/2027-05-27', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://www.superai.com/', '2026-11-12T00:00:00Z', 'Join SuperAI Global in 2027. London: May 26-27. Singapore: Sept 8-9 at Marina Bay Sands.') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://computextaipei.com.tw/en/index.html?event=computex-2027'), 'https://computextaipei.com.tw/en/index.html?event=computex-2027', 'https://www.computextaipei.com.tw/en/index.html', 'COMPUTEX 2027', NULL, 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-06-01T00:00:00Z', '2027-06-04T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://computextaipei.com.tw/en/index.html?event=computex-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://computextaipei.com.tw/en/index.html?event=computex-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://computextaipei.com.tw/en/index.html?event=computex-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://computextaipei.com.tw/en/index.html?event=computex-2027'), 'https://www.computex.biz/', 'https://computextaipei.com.tw/en/index.html?event=computex-2027', 'COMPUTEX 2027', NULL, 'EVENT',
        '2027-06-01T00:00:00Z', '2027-06-04T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Taipei'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://computextaipei.com.tw/en/index.html?event=computex-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://computextaipei.com.tw/en/index.html?event=computex-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://computextaipei.com.tw/en/index.html?event=computex-2027'), 'eventDates', '2027-06-01/2027-06-04', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://www.computex.biz/', '2026-11-12T00:00:00Z', 'COMPUTEX 2027 Exhibitor Registration Is Now Open ... June 1-4, 2027') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://snowflake.com/en/summit?event=snowflake-summit-27'), 'https://snowflake.com/en/summit?event=snowflake-summit-27', 'https://www.snowflake.com/en/summit/', 'Snowflake Summit 27', 'Snowflake''s four-day conference on data, AI and applications at Moscone Center, with sessions, hands-on labs, training and certification.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-06-07T00:00:00Z', '2027-06-10T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://snowflake.com/en/summit?event=snowflake-summit-27');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://snowflake.com/en/summit?event=snowflake-summit-27'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://snowflake.com/en/summit?event=snowflake-summit-27', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://snowflake.com/en/summit?event=snowflake-summit-27'), 'https://www.snowflake.com/en/summit/', 'https://snowflake.com/en/summit?event=snowflake-summit-27', 'Snowflake Summit 27', 'Snowflake''s four-day conference on data, AI and applications at Moscone Center, with sessions, hands-on labs, training and certification.', 'EVENT',
        '2027-06-07T00:00:00Z', '2027-06-10T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://snowflake.com/en/summit?event=snowflake-summit-27') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://snowflake.com/en/summit?event=snowflake-summit-27') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://snowflake.com/en/summit?event=snowflake-summit-27'), 'eventDates', '2027-06-07/2027-06-10', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://www.snowflake.com/en/summit/', '2026-11-12T00:00:00Z', 'JUNE 7 - 10, 2027') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ibm.com/events/think?event=think-2027'), 'https://ibm.com/events/think?event=think-2027', 'https://www.ibm.com/events/think', 'Think 2027', 'IBM''s Think event in Boston, for business and technology leaders, on AI, innovation and the future of business.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-06-07T00:00:00Z', '2027-06-10T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ibm.com/events/think?event=think-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ibm.com/events/think?event=think-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://ibm.com/events/think?event=think-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ibm.com/events/think?event=think-2027'), 'https://www.ibm.com/events/think', 'https://ibm.com/events/think?event=think-2027', 'Think 2027', 'IBM''s Think event in Boston, for business and technology leaders, on AI, innovation and the future of business.', 'EVENT',
        '2027-06-07T00:00:00Z', '2027-06-10T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Boston'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ibm.com/events/think?event=think-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ibm.com/events/think?event=think-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ibm.com/events/think?event=think-2027'), 'eventDates', '2027-06-07/2027-06-10', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://www.ibm.com/events/think', '2026-11-12T00:00:00Z', 'June 7-10, 2027 | Boston, MA') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://london.theaisummit.com?event=the-ai-summit-london-2027'), 'https://london.theaisummit.com?event=the-ai-summit-london-2027', 'https://london.theaisummit.com/', 'The AI Summit London 2027', 'A commercial AI conference at Tobacco Dock in London, with an exhibition and curated content tracks.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-06-09T00:00:00Z', '2027-06-10T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://london.theaisummit.com?event=the-ai-summit-london-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://london.theaisummit.com?event=the-ai-summit-london-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://london.theaisummit.com?event=the-ai-summit-london-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://london.theaisummit.com?event=the-ai-summit-london-2027'), 'https://london.theaisummit.com/', 'https://london.theaisummit.com?event=the-ai-summit-london-2027', 'The AI Summit London 2027', 'A commercial AI conference at Tobacco Dock in London, with an exhibition and curated content tracks.', 'EVENT',
        '2027-06-09T00:00:00Z', '2027-06-10T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'London'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://london.theaisummit.com?event=the-ai-summit-london-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://london.theaisummit.com?event=the-ai-summit-london-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://london.theaisummit.com?event=the-ai-summit-london-2027'), 'eventDates', '2027-06-09/2027-06-10', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://london.theaisummit.com/', '2026-11-12T00:00:00Z', '9-10 June 2027 | Tobacco Dock, London') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://rio.websummit.com?event=web-summit-rio-2027'), 'https://rio.websummit.com?event=web-summit-rio-2027', 'https://rio.websummit.com/', 'Web Summit Rio 2027', 'The Rio de Janeiro edition of Web Summit, a technology conference at Riocentro for founders, investors and global leaders.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-06-14T00:00:00Z', '2027-06-17T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://rio.websummit.com?event=web-summit-rio-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://rio.websummit.com?event=web-summit-rio-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://rio.websummit.com?event=web-summit-rio-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://rio.websummit.com?event=web-summit-rio-2027'), 'https://rio.websummit.com/', 'https://rio.websummit.com?event=web-summit-rio-2027', 'Web Summit Rio 2027', 'The Rio de Janeiro edition of Web Summit, a technology conference at Riocentro for founders, investors and global leaders.', 'EVENT',
        '2027-06-14T00:00:00Z', '2027-06-17T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Rio'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://rio.websummit.com?event=web-summit-rio-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://rio.websummit.com?event=web-summit-rio-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://rio.websummit.com?event=web-summit-rio-2027'), 'eventDates', '2027-06-14/2027-06-17', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://rio.websummit.com/', '2026-11-12T00:00:00Z', 'Junte-se a nós no Riocentro, no Rio, de 14 a 17 de junho de 2027.') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://vivatech.com?event=vivatech-2027'), 'https://vivatech.com?event=vivatech-2027', 'https://vivatech.com/', 'VivaTech 2027', 'A technology and innovation event at Paris Expo Porte de Versailles for startups, investors, exhibitors and media, co-organized by Groupe Les Echos-Le Parisien and Publicis Groupe.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-06-16T00:00:00Z', '2027-06-19T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://vivatech.com?event=vivatech-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://vivatech.com?event=vivatech-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://vivatech.com?event=vivatech-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://vivatech.com?event=vivatech-2027'), 'https://vivatech.com/', 'https://vivatech.com?event=vivatech-2027', 'VivaTech 2027', 'A technology and innovation event at Paris Expo Porte de Versailles for startups, investors, exhibitors and media, co-organized by Groupe Les Echos-Le Parisien and Publicis Groupe.', 'EVENT',
        '2027-06-16T00:00:00Z', '2027-06-19T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Paris'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://vivatech.com?event=vivatech-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://vivatech.com?event=vivatech-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://vivatech.com?event=vivatech-2027'), 'eventDates', '2027-06-16/2027-06-19', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://vivatech.com/', '2026-11-12T00:00:00Z', '16-19 June 2027 ... Paris Expo Porte de Versailles') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://databricks.com/dataaisummit?event=data-ai-summit-2027'), 'https://databricks.com/dataaisummit?event=data-ai-summit-2027', 'https://www.databricks.com/dataaisummit', 'Data + AI Summit 2027', 'Databricks'' event for the data, analytics and AI community, at the Moscone Center in San Francisco and online, for AI and machine learning engineers, data scientists and data engineers.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-06-21T00:00:00Z', '2027-06-24T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://databricks.com/dataaisummit?event=data-ai-summit-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://databricks.com/dataaisummit?event=data-ai-summit-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://databricks.com/dataaisummit?event=data-ai-summit-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://databricks.com/dataaisummit?event=data-ai-summit-2027'), 'https://www.databricks.com/dataaisummit', 'https://databricks.com/dataaisummit?event=data-ai-summit-2027', 'Data + AI Summit 2027', 'Databricks'' event for the data, analytics and AI community, at the Moscone Center in San Francisco and online, for AI and machine learning engineers, data scientists and data engineers.', 'EVENT',
        '2027-06-21T00:00:00Z', '2027-06-24T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'eventFormat', 'HYBRID', 'location', 'San Francisco'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://databricks.com/dataaisummit?event=data-ai-summit-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://databricks.com/dataaisummit?event=data-ai-summit-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://databricks.com/dataaisummit?event=data-ai-summit-2027'), 'eventDates', '2027-06-21/2027-06-24', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://www.databricks.com/dataaisummit', '2026-11-12T00:00:00Z', 'June 21–24, 2027 / San Francisco + Virtual') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai.engineer?event=ai-engineer-world-s-fair-2027'), 'https://ai.engineer?event=ai-engineer-world-s-fair-2027', 'https://www.ai.engineer/', 'AI Engineer World''s Fair 2027', 'AI Engineer''s conference at Moscone West in San Francisco, for AI engineers, founders and VPs of AI.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-06-29T00:00:00Z', '2027-07-01T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai.engineer?event=ai-engineer-world-s-fair-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai.engineer?event=ai-engineer-world-s-fair-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://ai.engineer?event=ai-engineer-world-s-fair-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai.engineer?event=ai-engineer-world-s-fair-2027'), 'https://www.ai.engineer/', 'https://ai.engineer?event=ai-engineer-world-s-fair-2027', 'AI Engineer World''s Fair 2027', 'AI Engineer''s conference at Moscone West in San Francisco, for AI engineers, founders and VPs of AI.', 'EVENT',
        '2027-06-29T00:00:00Z', '2027-07-01T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'San Francisco'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai.engineer?event=ai-engineer-world-s-fair-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai.engineer?event=ai-engineer-world-s-fair-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai.engineer?event=ai-engineer-world-s-fair-2027'), 'eventDates', '2027-06-29/2027-07-01', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://www.ai.engineer/', '2026-11-12T00:00:00Z', 'June 29 - July 1, 2027.') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://raisesummit.com?event=raise-summit-2027'), 'https://raisesummit.com?event=raise-summit-2027', 'https://raisesummit.com/', 'RAISE Summit 2027', 'An AI summit in Paris for executives, investors, builders and policymakers.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-07-06T00:00:00Z', '2027-07-08T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://raisesummit.com?event=raise-summit-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://raisesummit.com?event=raise-summit-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://raisesummit.com?event=raise-summit-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://raisesummit.com?event=raise-summit-2027'), 'https://raisesummit.com/', 'https://raisesummit.com?event=raise-summit-2027', 'RAISE Summit 2027', 'An AI summit in Paris for executives, investors, builders and policymakers.', 'EVENT',
        '2027-07-06T00:00:00Z', '2027-07-08T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Paris, France'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://raisesummit.com?event=raise-summit-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://raisesummit.com?event=raise-summit-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://raisesummit.com?event=raise-summit-2027'), 'eventDates', '2027-07-06/2027-07-08', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://raisesummit.com/', '2026-11-12T00:00:00Z', '6-8 July 2027 | Paris, France') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai4.com/vegas?event=ai4-2027'), 'https://ai4.com/vegas?event=ai4-2027', 'https://ai4.com/vegas/', 'Ai4 2027', 'An AI conference at The Venetian in Las Vegas for business executives, technology leaders, startups, investors and researchers. Topics include AI agents, generative AI and world models.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-08-02T00:00:00Z', '2027-08-05T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai4.com/vegas?event=ai4-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai4.com/vegas?event=ai4-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://ai4.com/vegas?event=ai4-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai4.com/vegas?event=ai4-2027'), 'https://ai4.com/vegas/', 'https://ai4.com/vegas?event=ai4-2027', 'Ai4 2027', 'An AI conference at The Venetian in Las Vegas for business executives, technology leaders, startups, investors and researchers. Topics include AI agents, generative AI and world models.', 'EVENT',
        '2027-08-02T00:00:00Z', '2027-08-05T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Las Vegas'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai4.com/vegas?event=ai4-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai4.com/vegas?event=ai4-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://ai4.com/vegas?event=ai4-2027'), 'eventDates', '2027-08-02/2027-08-05', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://ai4.com/vegas/', '2026-11-12T00:00:00Z', 'August 2-5, 2027 ... The Venetian, Las Vegas') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://superai.com?event=superai-singapore-2027'), 'https://superai.com?event=superai-singapore-2027', 'https://www.superai.com/', 'SuperAI Singapore 2027', NULL, 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-09-08T00:00:00Z', '2027-09-09T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://superai.com?event=superai-singapore-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://superai.com?event=superai-singapore-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://superai.com?event=superai-singapore-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://superai.com?event=superai-singapore-2027'), 'https://www.superai.com/', 'https://superai.com?event=superai-singapore-2027', 'SuperAI Singapore 2027', NULL, 'EVENT',
        '2027-09-08T00:00:00Z', '2027-09-09T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Singapore'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://superai.com?event=superai-singapore-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://superai.com?event=superai-singapore-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://superai.com?event=superai-singapore-2027'), 'eventDates', '2027-09-08/2027-09-09', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://www.superai.com/', '2026-11-12T00:00:00Z', 'Join SuperAI Global in 2027. London: May 26-27. Singapore: Sept 8-9 at Marina Bay Sands.') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://humanx.co?event=humanx-amsterdam-2027'), 'https://humanx.co?event=humanx-amsterdam-2027', 'https://www.humanx.co/', 'HumanX Amsterdam 2027', 'An AI conference for leaders, builders and investors at RAI Amsterdam, with three days of keynotes and one-to-one meetings.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-09-20T00:00:00Z', '2027-09-22T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://humanx.co?event=humanx-amsterdam-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://humanx.co?event=humanx-amsterdam-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://humanx.co?event=humanx-amsterdam-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://humanx.co?event=humanx-amsterdam-2027'), 'https://www.humanx.co/', 'https://humanx.co?event=humanx-amsterdam-2027', 'HumanX Amsterdam 2027', 'An AI conference for leaders, builders and investors at RAI Amsterdam, with three days of keynotes and one-to-one meetings.', 'EVENT',
        '2027-09-20T00:00:00Z', '2027-09-22T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Amsterdam, Netherlands'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://humanx.co?event=humanx-amsterdam-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://humanx.co?event=humanx-amsterdam-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://humanx.co?event=humanx-amsterdam-2027'), 'eventDates', '2027-09-20/2027-09-22', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://www.humanx.co/', '2026-11-12T00:00:00Z', 'September 20-22, 2027 · RAI Amsterdam, Netherlands') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://salesforce.com/dreamforce?event=dreamforce-2027'), 'https://salesforce.com/dreamforce?event=dreamforce-2027', 'https://www.salesforce.com/dreamforce/', 'Dreamforce 2027', NULL, 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-09-21T00:00:00Z', '2027-09-23T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://salesforce.com/dreamforce?event=dreamforce-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://salesforce.com/dreamforce?event=dreamforce-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://salesforce.com/dreamforce?event=dreamforce-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://salesforce.com/dreamforce?event=dreamforce-2027'), 'https://www.salesforce.com/dreamforce/', 'https://salesforce.com/dreamforce?event=dreamforce-2027', 'Dreamforce 2027', NULL, 'EVENT',
        '2027-09-21T00:00:00Z', '2027-09-23T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'San Francisco'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://salesforce.com/dreamforce?event=dreamforce-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://salesforce.com/dreamforce?event=dreamforce-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://salesforce.com/dreamforce?event=dreamforce-2027'), 'eventDates', '2027-09-21/2027-09-23', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://www.salesforce.com/dreamforce/', '2026-11-12T00:00:00Z', 'September 21-23, 2027 | San Francisco and Salesforce+') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://worldsummit.ai?event=world-summit-ai-2027'), 'https://worldsummit.ai?event=world-summit-ai-2027', 'https://worldsummit.ai/', 'World Summit AI 2027', 'An AI summit at Taets Art & Event Park, Amsterdam, for startups, academics, investors and business leaders, on real-world applications and emerging technologies.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-10-06T00:00:00Z', '2027-10-07T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://worldsummit.ai?event=world-summit-ai-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://worldsummit.ai?event=world-summit-ai-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://worldsummit.ai?event=world-summit-ai-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://worldsummit.ai?event=world-summit-ai-2027'), 'https://worldsummit.ai/', 'https://worldsummit.ai?event=world-summit-ai-2027', 'World Summit AI 2027', 'An AI summit at Taets Art & Event Park, Amsterdam, for startups, academics, investors and business leaders, on real-world applications and emerging technologies.', 'EVENT',
        '2027-10-06T00:00:00Z', '2027-10-07T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Amsterdam'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://worldsummit.ai?event=world-summit-ai-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://worldsummit.ai?event=world-summit-ai-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://worldsummit.ai?event=world-summit-ai-2027'), 'eventDates', '2027-10-06/2027-10-07', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://worldsummit.ai/', '2026-11-12T00:00:00Z', '6 – 7 October 2027 ... Taets Art & Event Park, Amsterdam') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://aiconference.com?event=the-ai-conference-2027'), 'https://aiconference.com?event=the-ai-conference-2027', 'https://aiconference.com/', 'The AI Conference 2027', NULL, 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-10-12T00:00:00Z', '2027-10-14T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://aiconference.com?event=the-ai-conference-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://aiconference.com?event=the-ai-conference-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://aiconference.com?event=the-ai-conference-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://aiconference.com?event=the-ai-conference-2027'), 'https://aiconference.com/', 'https://aiconference.com?event=the-ai-conference-2027', 'The AI Conference 2027', NULL, 'EVENT',
        '2027-10-12T00:00:00Z', '2027-10-14T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'San Francisco'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://aiconference.com?event=the-ai-conference-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://aiconference.com?event=the-ai-conference-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://aiconference.com?event=the-ai-conference-2027'), 'eventDates', '2027-10-12/2027-10-14', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://aiconference.com/', '2026-11-12T00:00:00Z', 'OCT 12-14 | 2027 SAN FRANCISCO') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin, event_starts_at, event_ends_at)
VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://websummit.com?event=web-summit-lisbon-2027'), 'https://websummit.com?event=web-summit-lisbon-2027', 'https://websummit.com/', 'Web Summit Lisbon 2027', 'The Lisbon edition of Web Summit, a technology conference with content tracks, talks and masterclasses.', 'CURATED', 'EVENT', 'BOTH', 'CURATED', '2027-11-08T00:00:00Z', '2027-11-11T00:00:00Z')
ON CONFLICT (canonical_url) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    data_origin = EXCLUDED.data_origin, event_starts_at = EXCLUDED.event_starts_at, event_ends_at = EXCLUDED.event_ends_at,
    modified_at = now();
DELETE FROM resource_label WHERE kind <> 'CATEGORY' AND resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://websummit.com?event=web-summit-lisbon-2027');
INSERT INTO resource_label (resource_id, kind, value) VALUES (uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://websummit.com?event=web-summit-lisbon-2027'), 'TAG', 'industry-event') ON CONFLICT DO NOTHING;
INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
VALUES (gen_random_uuid(), 'curated', 'https://websummit.com?event=web-summit-lisbon-2027', uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://websummit.com?event=web-summit-lisbon-2027'), 'https://websummit.com/', 'https://websummit.com?event=web-summit-lisbon-2027', 'Web Summit Lisbon 2027', 'The Lisbon edition of Web Summit, a technology conference with content tracks, talks and masterclasses.', 'EVENT',
        '2027-11-08T00:00:00Z', '2027-11-11T00:00:00Z', '2026-11-12T00:00:00Z', 'industry-events-v1',
        jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', 'BOTH', 'useCases', '[]'::jsonb,
                           'tags', '["industry-event"]'::jsonb, 'platforms', '[]'::jsonb, 'popularity', '[]'::jsonb,
                           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN', 'eventKind', 'CONFERENCE', 'location', 'Lisbon'))
ON CONFLICT (source_id, external_id) DO UPDATE SET resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
    canonical_url = EXCLUDED.canonical_url, title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    WHERE a.resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://websummit.com?event=web-summit-lisbon-2027') AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion WHERE resource_id = uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://websummit.com?event=web-summit-lisbon-2027') AND origin = 'CURATED' AND enrichment_run_id IS NULL AND field IN ('eventDates', 'eventFormat');
WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
             VALUES (gen_random_uuid(), uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, 'https://websummit.com?event=web-summit-lisbon-2027'), 'eventDates', '2027-11-08/2027-11-11', 'CURATED', 'VERIFIED') RETURNING id),
     ev AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
             VALUES (gen_random_uuid(), 'https://websummit.com/', '2026-11-12T00:00:00Z', 'One event, countless opportunities. Join us in Lisbon, November 8-11, 2027.') RETURNING id)
INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, ev.id FROM a, ev;

CREATE TEMPORARY TABLE dropped_event ON COMMIT DROP AS
SELECT resource_id, external_id FROM source_record
WHERE source_id = 'curated' AND fingerprint = 'industry-events-v1' AND external_id NOT IN (
    'https://ignite.microsoft.com?event=microsoft-ignite-2026',
    'https://aws.amazon.com/events/reinvent?event=aws-re-invent-2026',
    'https://nvidia.com/gtc/dc?event=gtc-washington-d-c-2026',
    'https://ai-dev.deeplearning.ai?event=ai-dev-nyc-2026',
    'https://gitex.com?event=gitex-global-2026',
    'https://newyork.theaisummit.com?event=the-ai-summit-new-york-2026',
    'https://ces.tech?event=ces-2027',
    'https://qatar.websummit.com?event=web-summit-qatar-2027',
    'https://ai.engineer?event=ai-engineer-europe-2027',
    'https://mwcbarcelona.com?event=mwc-barcelona-2027',
    'https://humanx.co?event=humanx-las-vegas-2027',
    'https://nvidia.com/gtc?event=gtc-2027',
    'https://interrupt.langchain.com?event=interrupt-27',
    'https://reg.googlecloudevents.com/flow/googlecloud/next27/vegas/page/home?event=google-cloud-next-2027',
    'https://vancouver.websummit.com?event=web-summit-vancouver-2027',
    'https://superai.com?event=superai-london-2027',
    'https://computextaipei.com.tw/en/index.html?event=computex-2027',
    'https://snowflake.com/en/summit?event=snowflake-summit-27',
    'https://ibm.com/events/think?event=think-2027',
    'https://london.theaisummit.com?event=the-ai-summit-london-2027',
    'https://rio.websummit.com?event=web-summit-rio-2027',
    'https://vivatech.com?event=vivatech-2027',
    'https://databricks.com/dataaisummit?event=data-ai-summit-2027',
    'https://ai.engineer?event=ai-engineer-world-s-fair-2027',
    'https://raisesummit.com?event=raise-summit-2027',
    'https://ai4.com/vegas?event=ai4-2027',
    'https://superai.com?event=superai-singapore-2027',
    'https://humanx.co?event=humanx-amsterdam-2027',
    'https://salesforce.com/dreamforce?event=dreamforce-2027',
    'https://worldsummit.ai?event=world-summit-ai-2027',
    'https://aiconference.com?event=the-ai-conference-2027',
    'https://websummit.com?event=web-summit-lisbon-2027');
DELETE FROM evidence WHERE id IN (
    SELECT j.evidence_id FROM field_assertion_evidence j JOIN field_assertion a ON a.id = j.assertion_id
    JOIN dropped_event d ON d.resource_id = a.resource_id
    WHERE a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat'));
DELETE FROM field_assertion a USING dropped_event d
WHERE a.resource_id = d.resource_id AND a.origin = 'CURATED' AND a.enrichment_run_id IS NULL AND a.field IN ('eventDates', 'eventFormat');
DELETE FROM source_record s USING dropped_event d WHERE s.source_id = 'curated' AND s.external_id = d.external_id;
DELETE FROM resource r USING dropped_event d
WHERE r.id = d.resource_id AND r.data_origin = 'CURATED' AND NOT EXISTS (SELECT 1 FROM source_record o WHERE o.resource_id = r.id);
