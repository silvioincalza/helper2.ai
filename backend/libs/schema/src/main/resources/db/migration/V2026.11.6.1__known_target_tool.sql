ALTER TABLE known_target DROP CONSTRAINT known_target_kind_check;
ALTER TABLE known_target ADD CONSTRAINT known_target_kind_check CHECK (kind IN ('VENDOR', 'SERVICE', 'TOOL'));

ALTER TABLE relationship DROP CONSTRAINT relationship_target_kind_check;
ALTER TABLE relationship ADD CONSTRAINT relationship_target_kind_check
    CHECK (target_kind IN ('VENDOR', 'SERVICE', 'TOOL'));
