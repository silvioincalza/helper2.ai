ALTER TABLE list_seed RENAME COLUMN repository TO address;
ALTER TABLE list_seed DROP CONSTRAINT list_seed_repository_check;
ALTER TABLE list_seed ADD COLUMN kind text NOT NULL DEFAULT 'GITHUB_REPOSITORY'
    CHECK (kind IN ('GITHUB_REPOSITORY', 'WEB_PAGE'));
ALTER TABLE list_seed ALTER COLUMN kind DROP DEFAULT;
ALTER TABLE list_seed ADD COLUMN read_tried_at timestamptz;
ALTER TABLE list_seed ADD CONSTRAINT list_seed_address_check CHECK (
    (kind = 'GITHUB_REPOSITORY' AND address ~ '^[a-z0-9-]{1,39}/[a-z0-9._-]{1,100}$')
    OR (kind = 'WEB_PAGE' AND length(address) <= 300
        AND address ~ '^https://[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?\.[a-z]{2,63}(/[^[:space:]#?]*)?(\?[^[:space:]#]*)?$'));
ALTER TABLE list_seed ADD CONSTRAINT list_seed_read_tried_at_check
    CHECK (read_tried_at IS NULL OR kind = 'WEB_PAGE');
