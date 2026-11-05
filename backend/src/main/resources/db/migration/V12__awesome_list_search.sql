-- Search inside awesome lists (ADR 0055). A search also finds a resource by the words a list uses around it (its entry
-- line and its heading path), and a list by its headings. Only tsvectors are kept: the README text is never stored.
-- heading_path is the one piece of list text kept as text ("Tools > Vector databases", at most 200 characters), for
-- "Found in <list> under <heading>". Both tables are derived data: the awesome-list collector replaces a list's rows on
-- every pass, and they go with the list or the resource (ON DELETE CASCADE), or when no pass refreshed them for
-- H2_RETENTION_LIST_INDEX (ADR 0034).

-- One row per (list, entry). Entry words weigh A, heading words B.
CREATE TABLE awesome_list_entry (
    list_id       uuid        NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    resource_id   uuid        NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    heading_path  text        NOT NULL CHECK (length(heading_path) <= 200),
    search_vector tsvector    NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (list_id, resource_id),
    CHECK (list_id <> resource_id)
);
CREATE INDEX awesome_list_entry_search_idx ON awesome_list_entry USING gin (search_vector);
CREATE INDEX awesome_list_entry_resource_idx ON awesome_list_entry (resource_id);
CREATE INDEX awesome_list_entry_updated_idx ON awesome_list_entry (updated_at);

-- One row per list: its headings, so a list is found by the topics it covers.
CREATE TABLE awesome_list (
    list_id       uuid PRIMARY KEY REFERENCES resource (id) ON DELETE CASCADE,
    search_vector tsvector    NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX awesome_list_search_idx ON awesome_list USING gin (search_vector);
CREATE INDEX awesome_list_updated_idx ON awesome_list (updated_at);
