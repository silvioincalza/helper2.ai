-- Curated editorial seed for the "Use AI" path. Repeatable and idempotent: Flyway re-runs it whenever
-- this file changes, and every statement upserts, so editing an entry here updates it in place.
-- Honesty rules for this seed:
--   * Descriptions are written by the Helper2 team (description_origin = CURATED), not copied.
--   * No pricing, publication or update dates are asserted: they change often and were not sourced.
--   * URLs are the products' official sites. The "curated" source is disclosed as NOT live.
-- Resource ids use the same UUIDv5 namespace as ai.helper2.shared.ResourceId.

INSERT INTO source (id, name, mode, homepage, attribution)
VALUES ('curated', 'Helper2 curated list', 'CURATED', 'https://helper2.ai/sources',
        'Editorial seed written by the Helper2 team; not live data.')
ON CONFLICT (id) DO NOTHING;

CREATE TEMPORARY TABLE seed (canonical_url text, title text, description text, audience text, use_cases text[]) ON COMMIT DROP;

INSERT INTO seed VALUES
('https://chatgpt.com', 'ChatGPT', 'General-purpose AI assistant for conversation, drafting text, answering questions and working with files.', 'USE_AI', '{chat,writing,research}'),
('https://claude.ai', 'Claude', 'AI assistant for writing, analysis, research and coding help, with support for long documents.', 'USE_AI', '{chat,writing,coding}'),
('https://gemini.google.com', 'Gemini', 'Google''s AI assistant for questions, writing and planning, connected to Google services.', 'USE_AI', '{chat,research,writing}'),
('https://copilot.microsoft.com', 'Microsoft Copilot', 'Microsoft''s AI assistant for chat, web answers and help inside Microsoft 365 apps.', 'USE_AI', '{chat,writing}'),
('https://perplexity.ai', 'Perplexity', 'Answer engine that searches the web and responds with cited sources.', 'USE_AI', '{research,chat}'),
('https://deepl.com/translator', 'DeepL Translator', 'Machine translation for text and documents across many languages, including Italian.', 'USE_AI', '{translation,writing}'),
('https://grammarly.com', 'Grammarly', 'Writing assistant that suggests grammar, clarity and tone improvements.', 'USE_AI', '{writing}'),
('https://notion.com/product/ai', 'Notion AI', 'AI features inside the Notion workspace for drafting, summarising and searching notes.', 'USE_AI', '{writing,summarization,automation}'),
('https://canva.com/magic', 'Canva Magic Studio', 'AI design tools inside Canva for generating images, layouts and presentations.', 'USE_AI', '{design,image-generation}'),
('https://midjourney.com', 'Midjourney', 'Image generation from text prompts, focused on artistic and illustrative styles.', 'USE_AI', '{image-generation,design}'),
('https://firefly.adobe.com', 'Adobe Firefly', 'Adobe''s generative AI for images and design assets, integrated with Creative Cloud.', 'USE_AI', '{image-generation,design}'),
('https://elevenlabs.io', 'ElevenLabs', 'AI voice generation and text-to-speech, including voice dubbing.', 'USE_AI', '{audio}'),
('https://otter.ai', 'Otter.ai', 'Meeting transcription and notes with searchable transcripts.', 'USE_AI', '{transcription,summarization}'),
('https://notebooklm.google.com', 'NotebookLM', 'Research notebook that answers questions and creates summaries grounded in documents you upload.', 'USE_AI', '{research,summarization,learning}'),
('https://zapier.com/ai', 'Zapier AI', 'Automation platform features for building AI-assisted workflows between apps.', 'USE_AI', '{automation}'),
('https://runwayml.com', 'Runway', 'AI video generation and editing tools.', 'USE_AI', '{video}'),
('https://gamma.app', 'Gamma', 'Creates presentations, documents and simple web pages from a text prompt.', 'USE_AI', '{design,writing}'),
('https://khanmigo.ai', 'Khanmigo', 'Khan Academy''s AI tutor and teaching assistant.', 'USE_AI', '{learning}'),
('https://github.com/features/copilot', 'GitHub Copilot', 'AI pair programmer that suggests code and answers questions inside editors and GitHub.', 'BOTH', '{coding}'),
('https://descript.com', 'Descript', 'Audio and video editor that lets you edit recordings by editing their transcript.', 'USE_AI', '{video,transcription,audio}'),
('https://suno.com', 'Suno', 'Generates songs and music from text descriptions.', 'USE_AI', '{audio}'),
('https://elicit.com', 'Elicit', 'Research assistant that finds and summarises academic papers.', 'USE_AI', '{research,summarization}');

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, tech_level, data_origin)
SELECT uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, s.canonical_url), s.canonical_url, s.canonical_url,
       s.title, s.description, 'CURATED', 'PRODUCT', s.audience, 'BEGINNER', 'CURATED'
FROM seed s
ON CONFLICT (canonical_url) DO UPDATE SET
    url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    tech_level = EXCLUDED.tech_level, data_origin = EXCLUDED.data_origin, modified_at = now();

DELETE FROM resource_label
WHERE resource_id IN (SELECT uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, canonical_url) FROM seed);

INSERT INTO resource_label (resource_id, kind, value)
SELECT uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, s.canonical_url), 'USE_CASE', u
FROM seed s, unnest(s.use_cases) AS u
ON CONFLICT DO NOTHING;

INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           retrieved_at, fingerprint, extra)
SELECT gen_random_uuid(), 'curated', s.canonical_url,
       uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, s.canonical_url),
       s.canonical_url, s.canonical_url, s.title, s.description, 'PRODUCT', now(), 'seed-v1',
       jsonb_build_object('descriptionOrigin', 'CURATED', 'audience', s.audience, 'techLevel', 'BEGINNER',
                          'useCases', to_jsonb(s.use_cases), 'tags', '[]'::jsonb, 'platforms', '[]'::jsonb,
                          'popularity', '[]'::jsonb, 'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN')
FROM seed s
ON CONFLICT (source_id, external_id) DO UPDATE SET
    resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url, canonical_url = EXCLUDED.canonical_url,
    title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;
