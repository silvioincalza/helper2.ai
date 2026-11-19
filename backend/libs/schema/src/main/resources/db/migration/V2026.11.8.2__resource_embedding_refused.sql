CREATE TABLE resource_embedding_refused (
    resource_id uuid        NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    model       text        NOT NULL,
    text_hash   text        NOT NULL,
    refused_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (resource_id, model)
);
