-- =====================================================================================================================
-- Application audit log: the target and result columns of action_logs, and the indexes of the admin audit page and API.
-- core-backend, PostgreSQL 15 (docker-compose service "db", image postgres:15-alpine).                    2026-10-01
--
-- HOW TO RUN (psql only: the script uses psql meta-commands)
--     psql -X -v ON_ERROR_STOP=1 -U <owner of action_logs> -d <database> -f action-log-audit-migration.sql
--   * As the role that owns action_logs (DB_USERNAME, which is also POSTGRES_USER of the "db" service): an index belongs
--     to the owner of its table, and only that role may add columns. Any other role is refused.
--   * Without -1/--single-transaction: CREATE INDEX CONCURRENTLY cannot run inside a transaction block. PART A opens and
--     commits its own short transaction.
--   * Before the build that maps ActionLog.targetType/targetId/result starts, ideally. The running (older) build keeps
--     working meanwhile: it never reads the new columns and leaves them NULL in the rows it writes. Without this script,
--     that build's ddl-auto=update adds the same columns and creates the same indexes (same names) when it starts, but
--     not CONCURRENTLY: on a large action_logs the start-up then waits for the index builds.
--   * After a backup (pg_dump), as before any change to production data.
--
-- WHAT IT GUARANTEES
--   * Additive: no table, column, index or row is dropped, deleted or updated. The history is not rewritten: rows written
--     before keep their action codes (LOGIN_SUCCESS, CREATE_USER...: the application reads them as legacy codes of the
--     new actions) and get NULL target_type, target_id and result.
--   * Idempotent: a second run changes nothing.
--   * Writes never wait for it: the columns are added in one short metadata-only transaction (nullable, no default),
--     and the indexes are built CONCURRENTLY, so the INSERT of every sign-in and every change goes on meanwhile.
--   * A concurrent build that fails leaves an INVALID index behind; PART C finds it and stops with its name. Drop it
--     (DROP INDEX CONCURRENTLY <name>;) and run the script again.
--
--   PART 0  guard     right role, the table and the columns the application has always mapped
--   PART A  schema    target_type varchar(32), target_id bigint, result varchar(16)
--   PART B  indexes   five indexes, CONCURRENTLY, IF NOT EXISTS
--   PART C  checks    the columns with their types, the indexes valid and with the expected definitions; a NOTICE summary
-- =====================================================================================================================

\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------------------------------------------------------
-- PART 0. The table exists, has the columns every build has mapped, and the role that owns it runs the script.
-- ---------------------------------------------------------------------------------------------------------------------
DO $$
DECLARE
    table_owner text;
    missing text;
BEGIN
    IF to_regclass('action_logs') IS NULL THEN
        RAISE EXCEPTION 'action-log-audit-migration: there is no action_logs table here; the new build creates it complete (ddl-auto), nothing to migrate';
    END IF;
    table_owner := (SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'action_logs'::regclass);
    IF table_owner <> current_user THEN
        RAISE EXCEPTION 'action-log-audit-migration: run this script as %, the owner of action_logs (current user: %)',
            table_owner, current_user;
    END IF;
    SELECT string_agg(expected.column_name, ', ') INTO missing
    FROM (VALUES ('id'), ('user_id'), ('user_email'), ('action'), ('description'), ('ip_address'), ('timestamp'))
             AS expected(column_name)
    WHERE NOT EXISTS (SELECT 1 FROM information_schema.columns c
                      WHERE c.table_schema = current_schema() AND c.table_name = 'action_logs'
                        AND c.column_name = expected.column_name);
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'action-log-audit-migration: action_logs lacks the column(s) %; this is not the table the application maps',
            missing;
    END IF;
END
$$;

-- ---------------------------------------------------------------------------------------------------------------------
-- PART A. The columns ActionLog maps, with the types Hibernate 6.4 gives them on PostgreSQL. Nullable without default:
-- adding them only changes the catalog, no row is rewritten.
-- ---------------------------------------------------------------------------------------------------------------------
BEGIN;
-- Give up rather than queue behind the application: ADD COLUMN takes a short exclusive lock on action_logs.
SET LOCAL lock_timeout = '10s';
ALTER TABLE action_logs ADD COLUMN IF NOT EXISTS target_type varchar(32);
ALTER TABLE action_logs ADD COLUMN IF NOT EXISTS target_id bigint;
ALTER TABLE action_logs ADD COLUMN IF NOT EXISTS result varchar(16);
COMMIT;

-- ---------------------------------------------------------------------------------------------------------------------
-- PART B. The indexes of ActionLog (@Table(indexes)), with the names and column orders Hibernate uses, so ddl-auto finds
-- them and creates nothing more. Every list is newest first (timestamp, then id, both descending), as the audit page and
-- the API read it:
--   idx_action_logs_timestamp             the newest rows, and a time range
--   idx_action_logs_action_timestamp      one action (with its legacy codes), and the security / data tabs
--   idx_action_logs_user_id_timestamp     one account that acted
--   idx_action_logs_user_email_timestamp  one email (failed sign-ins carry only the email that was typed in)
--   idx_action_logs_target_timestamp      one kind of record, or one record
-- ---------------------------------------------------------------------------------------------------------------------
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_action_logs_timestamp
    ON action_logs ("timestamp" DESC, id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_action_logs_action_timestamp
    ON action_logs (action, "timestamp" DESC, id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_action_logs_user_id_timestamp
    ON action_logs (user_id, "timestamp" DESC, id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_action_logs_user_email_timestamp
    ON action_logs (user_email, "timestamp" DESC, id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_action_logs_target_timestamp
    ON action_logs (target_type, target_id, "timestamp" DESC, id DESC);

-- ---------------------------------------------------------------------------------------------------------------------
-- PART C. Checks. Anything unexpected stops the script with the reason: a column with another type, an index missing,
-- INVALID (a failed concurrent build) or defined on other columns than the application expects.
-- ---------------------------------------------------------------------------------------------------------------------
DO $$
DECLARE
    expected record;
    found_type text;
    index_valid boolean;
    index_columns text;
    total bigint;
    with_target bigint;
BEGIN
    FOR expected IN
        SELECT * FROM (VALUES ('target_type', 'character varying(32)'),
                              ('target_id', 'bigint'),
                              ('result', 'character varying(16)')) AS c(column_name, column_type)
    LOOP
        SELECT format_type(a.atttypid, a.atttypmod) INTO found_type
        FROM pg_attribute a
        WHERE a.attrelid = 'action_logs'::regclass AND a.attname = expected.column_name AND NOT a.attisdropped;
        IF found_type IS DISTINCT FROM expected.column_type THEN
            RAISE EXCEPTION 'action-log-audit-migration: action_logs.% is %, expected %',
                expected.column_name, coalesce(found_type, 'missing'), expected.column_type;
        END IF;
    END LOOP;

    FOR expected IN
        SELECT * FROM (VALUES ('idx_action_logs_timestamp', '("timestamp" DESC, id DESC)'),
                              ('idx_action_logs_action_timestamp', '(action, "timestamp" DESC, id DESC)'),
                              ('idx_action_logs_user_id_timestamp', '(user_id, "timestamp" DESC, id DESC)'),
                              ('idx_action_logs_user_email_timestamp', '(user_email, "timestamp" DESC, id DESC)'),
                              ('idx_action_logs_target_timestamp', '(target_type, target_id, "timestamp" DESC, id DESC)'))
                 AS i(index_name, column_list)
    LOOP
        SELECT x.indisvalid, substring(pg_get_indexdef(x.indexrelid) FROM ' USING btree (.*)$')
        INTO index_valid, index_columns
        FROM pg_index x
                 JOIN pg_class c ON c.oid = x.indexrelid
        WHERE x.indrelid = 'action_logs'::regclass AND c.relname = expected.index_name;
        IF index_valid IS NULL THEN
            RAISE EXCEPTION 'action-log-audit-migration: index % is missing', expected.index_name;
        END IF;
        IF NOT index_valid THEN
            RAISE EXCEPTION 'action-log-audit-migration: index % is INVALID (a concurrent build failed): DROP INDEX CONCURRENTLY %; then run the script again',
                expected.index_name, expected.index_name;
        END IF;
        IF index_columns IS DISTINCT FROM expected.column_list THEN
            RAISE EXCEPTION 'action-log-audit-migration: index % is on %, expected %',
                expected.index_name, index_columns, expected.column_list;
        END IF;
    END LOOP;

    SELECT count(*), count(target_type) INTO total, with_target FROM action_logs;
    RAISE NOTICE 'action-log-audit-migration: ok rows=% rows_with_target=% columns=target_type,target_id,result indexes=5',
        total, with_target;
END
$$;
