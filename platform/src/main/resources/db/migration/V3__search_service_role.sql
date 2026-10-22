-- Database role for the similarity-search service. It may read and write message_embedding only:
-- no access to message, message_original, users, or audit data. The login password is applied by
-- the afterMigrate callback from SEARCH_DB_PASSWORD, so no secret lives in a migration.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'search_svc') THEN
        CREATE ROLE search_svc NOLOGIN;
    END IF;
END
$$;

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM search_svc;
GRANT USAGE ON SCHEMA public TO search_svc;
GRANT SELECT, INSERT, UPDATE ON message_embedding TO search_svc;
