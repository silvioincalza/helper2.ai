-- Curated AI courses and certifications (ADR 0032 §8, Source 1). Repeatable and idempotent: Flyway re-runs it
-- whenever this file changes; rows are upserted and courses removed from this list are removed from the catalog.
-- Every URL is the provider's own page, opened on 2026-10-28 (HTTP 200 and the expected title; Udemy and O'Reilly
-- video pages block scripted clients, so those were confirmed through their indexed listings on the same day).
-- Honesty rules:
--   * Descriptions are written by the Helper2 team (description_origin = CURATED); Scout enrichment may add more.
--   * Card fields reuse existing columns and labels, never new ones:
--       platform   -> PLATFORM label (the e-learning platform or vendor);
--       level      -> tech_level, only where the page states it (Beginner/Fundamentals/Foundational -> BEGINNER,
--                     Intermediate/Associate -> INTERMEDIATE, Professional with years of experience -> ADVANCED);
--       pricing    -> pricing, only where the page states it: an exam fee or a paid subscription -> PAID, "free" -> FREE.
--                     Courses "free for a limited time" or with contradictory free/paid wording stay unknown;
--       credential -> tag 'certification' (proctored vendor exam) or 'certificate' (of completion); 'none' (no tag)
--                     only when the page says there is none; NULL when the page does not say;
--       format     -> tag 'exam-only', 'self-paced', 'instructor-led' or 'live-event'.
--     The same values are kept in source_record.extra ('credential', 'courseFormat') for typed use later.
--   * No publication or update date is asserted.
-- Not included: Microsoft Azure AI Engineer Associate (AI-102), retired (its page says so; AI-103 replaces it).

INSERT INTO source (id, name, mode, homepage, attribution)
VALUES ('curated', 'Helper2 curated list', 'CURATED', 'https://helper2.ai/sources',
        'Editorial seed written by the Helper2 team; not live data.')
ON CONFLICT (id) DO NOTHING;

CREATE TEMPORARY TABLE course_seed (
    canonical_url text, url text, title text, description text, platform text, audience text,
    tech_level text, pricing text, credential text, format text
) ON COMMIT DROP;

INSERT INTO course_seed VALUES
-- Vendor certifications: proctored exams with a stated fee.
('https://cloud.google.com/learn/certification/machine-learning-engineer', 'https://cloud.google.com/learn/certification/machine-learning-engineer',
 'Google Cloud Professional Machine Learning Engineer', 'Google Cloud certification exam on designing, building and running ML solutions on Google Cloud; recommended for people with 3+ years of industry experience.',
 'Google Cloud', 'BUILD_WITH_AI', 'ADVANCED', 'PAID', 'certification', 'exam-only'),
('https://cloud.google.com/learn/certification/generative-ai-leader', 'https://cloud.google.com/learn/certification/generative-ai-leader',
 'Google Cloud Generative AI Leader', 'Google Cloud certification exam on generative AI concepts and business use, for any job role, with or without hands-on technical experience.',
 'Google Cloud', 'BOTH', 'BEGINNER', 'PAID', 'certification', 'exam-only'),
('https://aws.amazon.com/certification/certified-machine-learning-engineer-associate', 'https://aws.amazon.com/certification/certified-machine-learning-engineer-associate/',
 'AWS Certified Machine Learning Engineer – Associate', 'Associate-level AWS certification exam on building, deploying and operating machine learning workloads on AWS.',
 'AWS', 'BUILD_WITH_AI', 'INTERMEDIATE', 'PAID', 'certification', 'exam-only'),
('https://aws.amazon.com/certification/certified-ai-practitioner', 'https://aws.amazon.com/certification/certified-ai-practitioner/',
 'AWS Certified AI Practitioner', 'Foundational AWS certification exam on AI, machine learning and generative AI concepts and their use on AWS.',
 'AWS', 'BOTH', 'BEGINNER', 'PAID', 'certification', 'exam-only'),
('https://learn.microsoft.com/en-us/credentials/certifications/azure-ai-apps-and-agents-developer-associate', 'https://learn.microsoft.com/en-us/credentials/certifications/azure-ai-apps-and-agents-developer-associate/',
 'Microsoft Certified: Azure AI Apps and Agents Developer Associate (AI-103)', 'Microsoft certification (exam AI-103) on designing, building and deploying AI apps and agents with Python and Microsoft Foundry. Successor of the retired AI-102.',
 'Microsoft Learn', 'BUILD_WITH_AI', 'INTERMEDIATE', 'PAID', 'certification', 'exam-only'),
('https://learn.microsoft.com/en-us/credentials/certifications/azure-ai-fundamentals', 'https://learn.microsoft.com/en-us/credentials/certifications/azure-ai-fundamentals/',
 'Microsoft Certified: Azure AI Fundamentals (AI-901)', 'Entry-level Microsoft certification (exam AI-901) on AI concepts and building AI solutions on Azure and Microsoft Foundry.',
 'Microsoft Learn', 'BOTH', 'BEGINNER', 'PAID', 'certification', 'exam-only'),
('https://nvidia.com/en-us/learn/certification/generative-ai-llm-associate', 'https://www.nvidia.com/en-us/learn/certification/generative-ai-llm-associate/',
 'NVIDIA-Certified Associate: Generative AI LLMs (NCA-GENL)', 'NVIDIA associate certification exam on the fundamentals of developing and using large language models with NVIDIA solutions.',
 'NVIDIA', 'BUILD_WITH_AI', NULL, 'PAID', 'certification', 'exam-only'),
('https://nvidia.com/en-us/learn/certification/generative-ai-multimodal-associate', 'https://www.nvidia.com/en-us/learn/certification/generative-ai-multimodal-associate/',
 'NVIDIA-Certified Associate: Generative AI Multimodal (NCA-GENM)', 'NVIDIA associate certification exam on designing and using multimodal generative AI models.',
 'NVIDIA', 'BUILD_WITH_AI', NULL, 'PAID', 'certification', 'exam-only'),
('https://nvidia.com/en-us/learn/certification/ai-infrastructure-operations-associate', 'https://www.nvidia.com/en-us/learn/certification/ai-infrastructure-operations-associate/',
 'NVIDIA-Certified Associate: AI Infrastructure and Operations (NCA-AIIO)', 'NVIDIA associate certification exam on AI infrastructure and operations: compute, networking and managing AI workloads.',
 'NVIDIA', 'BUILD_WITH_AI', NULL, 'PAID', 'certification', 'exam-only'),
('https://nvidia.com/en-us/learn/certification/generative-ai-llm-professional', 'https://www.nvidia.com/en-us/learn/certification/generative-ai-llm-professional/',
 'NVIDIA-Certified Professional: Generative AI LLMs (NCP-GENL)', 'NVIDIA professional certification exam on building, training, optimizing and deploying large language models.',
 'NVIDIA', 'BUILD_WITH_AI', 'ADVANCED', 'PAID', 'certification', 'exam-only'),
('https://nvidia.com/en-us/learn/certification/agentic-ai-professional', 'https://www.nvidia.com/en-us/learn/certification/agentic-ai-professional/',
 'NVIDIA-Certified Professional: Agentic AI (NCP-AAI)', 'NVIDIA professional certification exam on designing, building and deploying agentic AI systems.',
 'NVIDIA', 'BUILD_WITH_AI', 'ADVANCED', 'PAID', 'certification', 'exam-only'),
('https://databricks.com/learn/certification/genai-engineer-associate', 'https://www.databricks.com/learn/certification/genai-engineer-associate',
 'Databricks Certified Generative AI Engineer Associate', 'Databricks proctored certification exam on building LLM-based solutions on Databricks; recommends 6+ months of hands-on experience.',
 'Databricks', 'BUILD_WITH_AI', 'INTERMEDIATE', 'PAID', 'certification', 'exam-only'),
('https://databricks.com/learn/certification/machine-learning-associate', 'https://www.databricks.com/learn/certification/machine-learning-associate',
 'Databricks Certified Machine Learning Associate', 'Databricks proctored certification exam on basic machine learning tasks on Databricks; recommends 6+ months of hands-on experience.',
 'Databricks', 'BUILD_WITH_AI', 'INTERMEDIATE', 'PAID', 'certification', 'exam-only'),
-- DeepLearning.AI short courses (free "for a limited time during the platform beta": pricing left unknown).
('https://deeplearning.ai/courses/chatgpt-prompt-eng', 'https://www.deeplearning.ai/courses/chatgpt-prompt-eng',
 'ChatGPT Prompt Engineering for Developers', 'DeepLearning.AI short course with Isa Fulford and Andrew Ng on using an LLM API with good prompts to build applications; basic Python is enough.',
 'DeepLearning.AI', 'BUILD_WITH_AI', 'BEGINNER', NULL, NULL, 'self-paced'),
('https://deeplearning.ai/courses/langchain', 'https://www.deeplearning.ai/courses/langchain',
 'LangChain for LLM Application Development', 'DeepLearning.AI short course on building LLM applications with LangChain: models, prompts, memory, chains, question answering over documents and agents.',
 'DeepLearning.AI', 'BUILD_WITH_AI', 'BEGINNER', NULL, NULL, 'self-paced'),
('https://deeplearning.ai/courses/mcp-build-rich-context-ai-apps-with-anthropic', 'https://www.deeplearning.ai/courses/mcp-build-rich-context-ai-apps-with-anthropic',
 'MCP: Build Rich-Context AI Apps with Anthropic', 'DeepLearning.AI short course, made with Anthropic, on building Model Context Protocol servers and clients and connecting AI apps to tools and data.',
 'DeepLearning.AI', 'BUILD_WITH_AI', 'INTERMEDIATE', NULL, NULL, 'self-paced'),
('https://deeplearning.ai/courses/ai-agents-in-langgraph', 'https://www.deeplearning.ai/courses/ai-agents-in-langgraph',
 'AI Agents in LangGraph', 'DeepLearning.AI short course on building agents from scratch and then with LangGraph, including agentic search, persistence and human-in-the-loop.',
 'DeepLearning.AI', 'BUILD_WITH_AI', 'INTERMEDIATE', NULL, NULL, 'self-paced'),
-- Coursera programmes (shareable certificate; pricing wording on the pages is contradictory, so unknown).
('https://coursera.org/specializations/machine-learning-introduction', 'https://www.coursera.org/specializations/machine-learning-introduction',
 'Machine Learning Specialization', 'Three-course Coursera series by DeepLearning.AI and Stanford Online, taught by Andrew Ng, on supervised and unsupervised learning, recommenders and reinforcement learning.',
 'Coursera', 'BUILD_WITH_AI', 'BEGINNER', NULL, 'certificate', 'self-paced'),
('https://coursera.org/specializations/deep-learning', 'https://www.coursera.org/specializations/deep-learning',
 'Deep Learning Specialization', 'Five-course Coursera series by DeepLearning.AI on neural networks, their tuning, CNNs, sequence models and transformers.',
 'Coursera', 'BUILD_WITH_AI', 'INTERMEDIATE', NULL, 'certificate', 'self-paced'),
('https://coursera.org/learn/generative-ai-for-everyone', 'https://www.coursera.org/learn/generative-ai-for-everyone',
 'Generative AI for Everyone', 'Coursera course by DeepLearning.AI with Andrew Ng on what generative AI can and cannot do and how to use it at work, without coding.',
 'Coursera', 'USE_AI', 'BEGINNER', NULL, 'certificate', 'self-paced'),
('https://coursera.org/professional-certificates/ai-engineer', 'https://www.coursera.org/professional-certificates/ai-engineer',
 'IBM AI Engineering Professional Certificate', 'IBM programme on Coursera on machine learning, deep learning and generative AI engineering with Python, PyTorch, Keras and LLM frameworks. Included with Coursera Plus.',
 'Coursera', 'BUILD_WITH_AI', 'INTERMEDIATE', NULL, 'certificate', 'self-paced'),
-- Hugging Face Learn: free, no deadline.
('https://huggingface.co/learn/llm-course', 'https://huggingface.co/learn/llm-course',
 'Hugging Face LLM Course', 'Free course on large language models and NLP with the Hugging Face Transformers, Datasets and Tokenizers libraries and the Hub, from basics to fine-tuning. Requires good Python; no certificate yet.',
 'Hugging Face Learn', 'BUILD_WITH_AI', NULL, 'FREE', 'none', 'self-paced'),
('https://huggingface.co/learn/agents-course', 'https://huggingface.co/learn/agents-course',
 'Hugging Face AI Agents Course', 'Free course on how AI agents work and how to build them with agent frameworks, with a free fundamentals certificate and a certificate of completion.',
 'Hugging Face Learn', 'BUILD_WITH_AI', NULL, 'FREE', 'certificate', 'self-paced'),
('https://huggingface.co/learn/mcp-course', 'https://huggingface.co/learn/mcp-course',
 'Hugging Face Model Context Protocol (MCP) Course', 'Free course on the Model Context Protocol: concepts, building MCP servers and clients, and use-case projects, with free certificates.',
 'Hugging Face Learn', 'BUILD_WITH_AI', NULL, 'FREE', 'certificate', 'self-paced'),
('https://huggingface.co/learn/deep-rl-course', 'https://huggingface.co/learn/deep-rl-course',
 'Hugging Face Deep Reinforcement Learning Course', 'Free course on deep reinforcement learning, from theory to training agents with RL libraries, with a certificate of completion.',
 'Hugging Face Learn', 'BUILD_WITH_AI', NULL, 'FREE', 'certificate', 'self-paced'),
-- fast.ai
('https://course.fast.ai', 'https://course.fast.ai/',
 'Practical Deep Learning for Coders', 'Free fast.ai course of video lessons on applying deep learning to practical problems; for people with about a year of coding experience.',
 'fast.ai', 'BUILD_WITH_AI', NULL, 'FREE', NULL, 'self-paced'),
-- Google for Developers
('https://developers.google.com/machine-learning/crash-course', 'https://developers.google.com/machine-learning/crash-course',
 'Google Machine Learning Crash Course', 'Google''s introduction to machine learning with video lectures, interactive visualizations and exercises.',
 'Google for Developers', 'BUILD_WITH_AI', NULL, NULL, NULL, 'self-paced'),
-- edX
('https://edx.org/learn/artificial-intelligence/harvard-university-cs50-s-introduction-to-artificial-intelligence-with-python', 'https://www.edx.org/learn/artificial-intelligence/harvard-university-cs50-s-introduction-to-artificial-intelligence-with-python',
 'CS50''s Introduction to Artificial Intelligence with Python', 'HarvardX course on edX on the concepts and algorithms behind modern AI, from search and probability to machine learning and neural networks, with Python projects.',
 'edX', 'BUILD_WITH_AI', NULL, NULL, NULL, 'self-paced'),
('https://edx.org/certificates/professional-certificate/ibm-generative-ai-for-everyone', 'https://www.edx.org/certificates/professional-certificate/ibm-generative-ai-for-everyone',
 'IBM Generative AI for Everyone Professional Certificate', 'IBM professional certificate programme on edX introducing generative AI, prompt engineering and its uses at work.',
 'edX', 'USE_AI', NULL, NULL, 'certificate', 'self-paced'),
-- Udemy
('https://udemy.com/course/machinelearning', 'https://www.udemy.com/course/machinelearning/',
 'Machine Learning A-Z', 'Udemy course by Kirill Eremenko, Hadelin de Ponteves and the SuperDataScience team on machine learning in Python and R, from regression to deep learning, with code templates.',
 'Udemy', 'BUILD_WITH_AI', NULL, NULL, NULL, 'self-paced'),
('https://udemy.com/course/complete-ai-guide', 'https://www.udemy.com/course/complete-ai-guide/',
 'The Complete AI Guide: Learn ChatGPT, Claude & Generative AI', 'Udemy course on using generative AI tools such as ChatGPT and Claude for everyday work.',
 'Udemy', 'USE_AI', NULL, NULL, NULL, 'self-paced'),
-- O'Reilly live online courses (series pages; each run's date is on the page).
('https://oreilly.com/live-events/ai-agents-a-z/0642572007604', 'https://www.oreilly.com/live-events/ai-agents-a-z/0642572007604/',
 'AI Agents A-Z (O''Reilly live course)', 'O''Reilly live online course with Sinan Ozdemir on building, deploying and evaluating AI agents, with frameworks and from scratch.',
 'O''Reilly', 'BUILD_WITH_AI', NULL, NULL, NULL, 'live-event'),
('https://oreilly.com/live-events/retrieval-augmented-generation-rag-and-agents-using-llms/0642572007797', 'https://www.oreilly.com/live-events/retrieval-augmented-generation-rag-and-agents-using-llms/0642572007797/',
 'Retrieval-Augmented Generation (RAG) and Agents Using LLMs (O''Reilly live course)', 'O''Reilly live online course with Sinan Ozdemir on building retrieval-augmented generation systems and agents with LLMs.',
 'O''Reilly', 'BUILD_WITH_AI', 'INTERMEDIATE', NULL, NULL, 'live-event'),
('https://oreilly.com/live-events/getting-started-with-langchain/0636920098586', 'https://www.oreilly.com/live-events/getting-started-with-langchain/0636920098586/',
 'Getting Started with LangChain (O''Reilly live course)', 'O''Reilly live online course with Lucas Soares introducing LangChain for building LLM applications.',
 'O''Reilly', 'BUILD_WITH_AI', 'BEGINNER', NULL, NULL, 'live-event'),
('https://oreilly.com/live-events/hands-on-llm-engineering/0642572011365', 'https://www.oreilly.com/live-events/hands-on-llm-engineering/0642572011365/',
 'Hands-On LLM Engineering (O''Reilly live course)', 'O''Reilly live online workshop on LLM engineering with closed and open models: RAG, fine-tuning, MCP and agents.',
 'O''Reilly', 'BUILD_WITH_AI', 'INTERMEDIATE', NULL, NULL, 'live-event');

INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, tech_level, pricing, data_origin)
SELECT uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, s.canonical_url), s.canonical_url, s.url,
       s.title, s.description, 'CURATED', 'COURSE', s.audience, s.tech_level, s.pricing, 'CURATED'
FROM course_seed s
ON CONFLICT (canonical_url) DO UPDATE SET
    url = EXCLUDED.url, title = EXCLUDED.title, description = EXCLUDED.description,
    description_origin = EXCLUDED.description_origin, type = EXCLUDED.type, audience = EXCLUDED.audience,
    tech_level = EXCLUDED.tech_level, pricing = EXCLUDED.pricing, data_origin = EXCLUDED.data_origin, modified_at = now();

DELETE FROM resource_label
WHERE resource_id IN (SELECT uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, canonical_url) FROM course_seed);

INSERT INTO resource_label (resource_id, kind, value)
SELECT uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, s.canonical_url), l.kind, l.value
FROM course_seed s
CROSS JOIN LATERAL (VALUES ('PLATFORM', s.platform), ('USE_CASE', 'learning'), ('TAG', nullif(s.credential, 'none')), ('TAG', s.format)) AS l (kind, value)
WHERE l.value IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, description, type,
                           retrieved_at, fingerprint, extra)
SELECT gen_random_uuid(), 'curated', s.canonical_url,
       uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, s.canonical_url),
       s.url, s.canonical_url, s.title, s.description, 'COURSE', '2026-10-28T00:00:00Z', 'courses-v1',
       jsonb_strip_nulls(jsonb_build_object(
           'descriptionOrigin', 'CURATED', 'audience', s.audience, 'techLevel', s.tech_level, 'pricing', s.pricing,
           'useCases', '["learning"]'::jsonb, 'tags', to_jsonb(array_remove(ARRAY[nullif(s.credential, 'none'), s.format], NULL)),
           'platforms', jsonb_build_array(s.platform), 'popularity', '[]'::jsonb,
           'publishedPrecision', 'UNKNOWN', 'updatedPrecision', 'UNKNOWN',
           'credential', s.credential, 'courseFormat', s.format))
FROM course_seed s
ON CONFLICT (source_id, external_id) DO UPDATE SET
    resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url, canonical_url = EXCLUDED.canonical_url,
    title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
    retrieved_at = EXCLUDED.retrieved_at, fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra;

-- Courses removed from the list above: their curated record goes, and the resource too unless another source has it.
CREATE TEMPORARY TABLE dropped_course ON COMMIT DROP AS
SELECT external_id, resource_id FROM source_record
WHERE source_id = 'curated' AND fingerprint = 'courses-v1' AND external_id NOT IN (SELECT canonical_url FROM course_seed);
DELETE FROM source_record s USING dropped_course d WHERE s.source_id = 'curated' AND s.external_id = d.external_id;
DELETE FROM resource r USING dropped_course d
WHERE r.id = d.resource_id AND r.data_origin = 'CURATED' AND NOT EXISTS (SELECT 1 FROM source_record o WHERE o.resource_id = r.id);
