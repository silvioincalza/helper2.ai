CREATE TABLE directory_rating (
    list_url    text         NOT NULL CHECK (length(list_url) <= 300),
    resource_id uuid         NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    host        text         NOT NULL CHECK (length(host) BETWEEN 1 AND 253),
    page_url    text         NOT NULL CHECK (length(page_url) <= 300),
    average     numeric(5,2) CHECK (average > 0 AND average <= scale),
    scale       smallint     CHECK (scale IN (5, 10, 100)),
    votes       integer      CHECK (votes >= 1),
    read_at     timestamptz,
    tried_at    timestamptz  NOT NULL,
    PRIMARY KEY (list_url, resource_id),
    CHECK ((average IS NULL) = (scale IS NULL) AND (average IS NULL) = (votes IS NULL)
        AND (average IS NULL) = (read_at IS NULL))
);
CREATE INDEX directory_rating_resource_idx ON directory_rating (resource_id) WHERE average IS NOT NULL;
CREATE INDEX directory_rating_tried_idx ON directory_rating (tried_at) WHERE page_url <> list_url;
GRANT SELECT ON directory_rating TO h2_public;
