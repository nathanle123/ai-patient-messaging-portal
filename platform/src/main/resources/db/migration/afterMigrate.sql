-- Flyway callback: runs after every migrate. Sets (or rotates) the search_svc login password from
-- SEARCH_DB_PASSWORD. With no password configured the role stays NOLOGIN.
DO $$
DECLARE
    pw text := '${search_db_password}';
BEGIN
    IF pw = '' THEN
        ALTER ROLE search_svc NOLOGIN;
    ELSE
        EXECUTE format('ALTER ROLE search_svc LOGIN PASSWORD %L', pw);
    END IF;
END
$$;
