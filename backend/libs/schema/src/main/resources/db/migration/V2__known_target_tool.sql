-- A known target can be a tool: an application a resource runs in or plugs into (VS Code, Claude Desktop, Cursor).
-- ADR 0019 §2. The stated kind the enrichment model gives stays PRODUCT, VENDOR or SERVICE: a person decides TOOL.

ALTER TABLE known_target DROP CONSTRAINT known_target_kind_check;
ALTER TABLE known_target ADD CONSTRAINT known_target_kind_check CHECK (kind IN ('VENDOR', 'SERVICE', 'TOOL'));

ALTER TABLE relationship DROP CONSTRAINT relationship_target_kind_check;
ALTER TABLE relationship ADD CONSTRAINT relationship_target_kind_check
    CHECK (target_kind IN ('VENDOR', 'SERVICE', 'TOOL'));
