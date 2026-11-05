-- A display setting of a signed-in user: whether the Home radar moves between its sources (ADR 0042). It lives in the
-- app_user row, so deleting the account or its retention purge (ADR 0034) removes it too.
ALTER TABLE app_user ADD COLUMN radar_motion boolean NOT NULL DEFAULT true;
