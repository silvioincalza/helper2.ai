-- The example searches under the home search box (ADR 0049). The home page picks 2 + 2 at random from the served
-- ones: ACTIVE, and (once checked) an expected resource in the top 3 of the example's own search.
-- expected_resource_ids are catalog resource ids (UUIDv5 of the canonical URL, ai.helper2.shared.ResourceId), kept
-- without a foreign key: a resource that leaves the catalog makes the check fail, it does not delete the example.
CREATE TABLE home_example (
    id                    uuid PRIMARY KEY,
    label                 text        NOT NULL CHECK (length(label) BETWEEN 2 AND 32),
    query                 text        NOT NULL CHECK (length(query) BETWEEN 3 AND 200),
    path                  text        NOT NULL CHECK (path IN ('USE_AI', 'BUILD_WITH_AI')),
    locale                text        NOT NULL DEFAULT 'en' CHECK (locale ~ '^[a-z]{2}$'),
    status                text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PAUSED')),
    expected_resource_ids uuid[]      NOT NULL CHECK (cardinality(expected_resource_ids) BETWEEN 1 AND 3),
    origin                text        NOT NULL CHECK (origin IN ('BUILT_IN', 'CURATED', 'AI_DRAFTED')),
    model                 text,
    check_outcome         text        NOT NULL DEFAULT 'PENDING' CHECK (check_outcome IN ('PENDING', 'PASS', 'WEAK', 'FAIL')),
    check_best_rank       integer     CHECK (check_best_rank > 0),
    check_result_count    integer     CHECK (check_result_count >= 0),
    checked_at            timestamptz,
    version               integer     NOT NULL DEFAULT 0,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CHECK ((origin = 'AI_DRAFTED') = (model IS NOT NULL))
);
-- One example per text and locale, whatever the case: the texts are stored normalised (NFC, single spaces).
CREATE UNIQUE INDEX home_example_label_idx ON home_example (locale, lower(label));
CREATE UNIQUE INDEX home_example_query_idx ON home_example (locale, lower(query));

ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check CHECK (action IN
    ('RESOLVE_REVIEW_ITEM', 'START_SCOUT_RUN', 'ADD_HOME_EXAMPLE', 'CHANGE_HOME_EXAMPLE', 'REMOVE_HOME_EXAMPLE'));

-- The built-in pool (frontend/src/lib/examples.ts), checked on staging on 5 Nov 2026. Each expected resource is the
-- result that made the example pass, by its canonical URL (ai.helper2.shared.CanonicalUrl: lower case, no www).
CREATE TEMPORARY TABLE seed (label text, query text, path text, urls text[]) ON COMMIT DROP;
INSERT INTO seed VALUES
('Summarise research papers', 'summarise research papers', 'USE_AI', '{https://elicit.com}'),
('Take meeting notes', 'take meeting notes', 'USE_AI', '{https://otter.ai}'),
('Make images from text', 'make images from text', 'USE_AI', '{https://midjourney.com,https://canva.com/magic,https://firefly.adobe.com}'),
('Remove a photo background', 'remove image background', 'USE_AI', '{https://huggingface.co/spaces/openskyml/remove-background-on-image}'),
('Edit videos with AI', 'edit videos with AI', 'USE_AI', '{https://runwayml.com}'),
('Postgres MCP server', 'postgres mcp server', 'BUILD_WITH_AI', '{https://npmjs.com/package/@microsoft/postgres-mcp,https://npmjs.com/package/enhanced-postgres-mcp-server}'),
('Vector database', 'vector database', 'BUILD_WITH_AI', '{https://github.com/milvus-io/milvus,https://github.com/qdrant/qdrant,https://github.com/weaviate/weaviate}'),
('Agent framework', 'agent framework', 'BUILD_WITH_AI', '{https://github.com/microsoft/agent-framework}'),
('Run LLMs locally', 'run llms locally', 'BUILD_WITH_AI', '{https://github.com/llamaedge/llamaedge,https://github.com/nomic-ai/gpt4all}'),
('RAG framework', 'rag framework', 'BUILD_WITH_AI', '{https://github.com/hkuds/rag-anything}'),
('AI coding assistant', 'AI coding assistant', 'BUILD_WITH_AI', '{https://github.com/github/copilotforxcode,https://npmjs.com/package/@qwen-code/qwen-code}');

INSERT INTO home_example (id, label, query, path, expected_resource_ids, origin)
SELECT gen_random_uuid(), s.label, s.query, s.path,
       ARRAY(SELECT uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, u) FROM unnest(s.urls) AS u),
       'BUILT_IN'
FROM seed s;
