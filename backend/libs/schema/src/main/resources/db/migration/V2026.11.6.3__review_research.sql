CREATE TABLE review_research (
    id                uuid PRIMARY KEY,
    review_item_id    uuid        NOT NULL REFERENCES review_item (id) ON DELETE CASCADE,
    status            text        NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED')),
    model             text        NOT NULL CHECK (length(model) BETWEEN 1 AND 200),
    requested_by      text        NOT NULL,
    requested_at      timestamptz NOT NULL DEFAULT now(),
    started_at        timestamptz,
    finished_at       timestamptz,
    heartbeat_at      timestamptz,
    steps             integer     NOT NULL DEFAULT 0,
    prompt_tokens     integer     NOT NULL DEFAULT 0,
    completion_tokens integer     NOT NULL DEFAULT 0,
    result            jsonb CHECK (jsonb_typeof(result) = 'object'),
    error             text CHECK (length(error) <= 2000),
    CHECK ((status = 'DONE') = (result IS NOT NULL)),
    CHECK ((status IN ('DONE', 'FAILED', 'CANCELLED')) = (finished_at IS NOT NULL))
);
CREATE UNIQUE INDEX review_research_one_active_idx ON review_research (review_item_id)
    WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX review_research_queue_idx ON review_research (requested_at) WHERE status = 'QUEUED';
CREATE INDEX review_research_item_idx ON review_research (review_item_id, requested_at DESC);
CREATE INDEX review_research_finished_idx ON review_research (finished_at) WHERE finished_at IS NOT NULL;

CREATE TABLE review_research_step (
    research_id       uuid        NOT NULL REFERENCES review_research (id) ON DELETE CASCADE,
    seq               integer     NOT NULL CHECK (seq >= 1),
    kind              text        NOT NULL CHECK (kind IN ('MODEL_CALL', 'TOOL_CALL', 'CHECK')),
    name              text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    request           text        NOT NULL CHECK (length(request) <= 200100),
    response          text CHECK (length(response) <= 200100),
    error             text CHECK (length(error) <= 2000),
    duration_ms       integer     NOT NULL CHECK (duration_ms >= 0),
    prompt_tokens     integer,
    completion_tokens integer,
    created_at        timestamptz NOT NULL,
    PRIMARY KEY (research_id, seq)
);

REVOKE SELECT ON review_research, review_research_step FROM h2_public;
