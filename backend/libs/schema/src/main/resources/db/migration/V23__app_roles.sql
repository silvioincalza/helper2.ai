-- One database role per app (ADR 0070). The owner (the user Flyway runs as: helper2 in the chart) keeps the tables
-- and runs the migrations; the public app connects as h2_public and the back office as h2_backoffice. Neither gets
-- DDL, TRUNCATE, REFERENCES or TRIGGER, and both only read the migration history.
-- Roles are cluster-wide, so they are created only when missing. They get no login here: the migrate step opens it
-- with each app's password (AppLogins), never written in a migration.
DO $$
DECLARE
  role_name text;
BEGIN
  FOREACH role_name IN ARRAY ARRAY['h2_public', 'h2_backoffice'] LOOP
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = role_name) THEN
      EXECUTE format('CREATE ROLE %I NOLOGIN', role_name);
    END IF;
    -- Also for a role that existed already (someone made it by hand): never more than an app needs.
    EXECUTE format('ALTER ROLE %I NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT', role_name);
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), role_name);
  END LOOP;
END
$$;

-- No role but the owner creates objects in the schema or temporary tables (PostgreSQL 15+ already keeps CREATE on
-- public from PUBLIC; said again so a database made otherwise is the same). The apps use neither.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
DO $$
BEGIN
  EXECUTE format('REVOKE TEMPORARY ON DATABASE %I FROM PUBLIC', current_database());
END
$$;

GRANT USAGE ON SCHEMA public TO h2_public, h2_backoffice;

-- Both read every table, but the public app not the Scout's and the enrichment's bookkeeping, which only the back
-- office reads.
GRANT SELECT ON ALL TABLES IN SCHEMA public TO h2_public, h2_backoffice;
REVOKE SELECT ON scout_run, scout_schedule, source_cursor, discovery_host, enrichment_queue_control, maintenance_task
  FROM h2_public;

-- The public app writes only what a visit, a sign-in, a report or its own jobs write (every table is classified in
-- AppRolesGrantsIT):
--   resource, resource_label, source_record   the live results it observes (ResourceStore.upsert: upserts, labels,
--                                             categories, event dates, orphans deleted);
--   source                                    its sources at startup and their last outcome;
--   issue_report, review_item                 a user's report (ReportIssue), moved when its resource is merged;
--   live_web_query_result                     the live web results kept per query (JdbcWebResultStore), and purged;
--   app_user, bookmark, admin_audit           sign-in, saved items, account deletion, identity retention;
--   guide_similar_question, ai_cache          the guide's similar questions and the shared cache, and their purges;
--   shedlock                                  one run at a time of its jobs.
-- Foreign-key cascades run as the owner, so a delete here needs no grant on the tables it cascades to.
GRANT INSERT, UPDATE, DELETE ON resource, resource_label, live_web_query_result, app_user, guide_similar_question,
  ai_cache TO h2_public;
GRANT INSERT, UPDATE ON source, source_record, issue_report, review_item, shedlock TO h2_public;
GRANT INSERT, DELETE ON bookmark, admin_audit TO h2_public;

-- The back office writes the catalog, the review queue, the Scout's tables, the settings and the audit trail: rows on
-- every table, and the settings' identity sequence.
GRANT INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO h2_backoffice;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO h2_backoffice;

-- The apps only check the migration history (SchemaCheck): they read it and never change it.
REVOKE INSERT, UPDATE, DELETE ON ${flyway:table} FROM h2_public, h2_backoffice;

-- Tables and sequences later migrations create (default privileges of the user running them, the owner): both apps
-- read a new table, only the back office writes it. A new table the public app writes needs its own
-- GRANT ... TO h2_public in the migration that creates it, and its row in AppRolesGrantsIT.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO h2_public, h2_backoffice;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT INSERT, UPDATE, DELETE ON TABLES TO h2_backoffice;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO h2_backoffice;
