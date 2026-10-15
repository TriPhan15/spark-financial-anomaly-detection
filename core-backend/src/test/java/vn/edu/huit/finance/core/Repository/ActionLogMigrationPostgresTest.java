package vn.edu.huit.finance.core.Repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The migration of the activity log ({@code src/main/resources/db/action-log-audit-migration.sql}) on PostgreSQL 15, the
 * database of the deployment, run the way it will be run there: the file itself, by psql, inside the database container.
 * Each test makes a database of its own, shaped like one the script will meet (action_logs as the production dump of
 * 2026-06-16 shows it, with rows; or as the new build's ddl-auto created it), runs the script and reads everything back.
 * The application context is there for one thing: Hibernate creates action_logs of the current mapping in the database of
 * the context, and the migrated table is compared with it, column by column and index by index. Skipped where Docker is
 * not available. The container, its user and its databases exist nowhere else (see ReceiptConcurrencyPostgresTest).
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActionLogMigrationPostgresTest {

    /** Keep in step with the db service of docker-compose.yml. */
    private static final String IMAGE = "postgres:15-alpine";
    private static final int PORT = 5432;
    private static final String DATABASE = "action_log_migration_it";
    private static final String USERNAME = "action_log_migration_it";
    private static final String PASSWORD = "integration-test-only";
    private static final String READY_LOG = ".*database system is ready to accept connections.*\\n";
    private static final int READY_LOG_TIMES = 2;
    private static final String ANOTHER_SUPERUSER = "migration_operator";

    private static final String MIGRATION = "db/action-log-audit-migration.sql";
    private static final String MIGRATION_IN_CONTAINER = "/tmp/action-log-audit-migration.sql";
    /** What psql answers when ON_ERROR_STOP ended a script at a failing statement. */
    private static final int PSQL_STOPPED_ON_ERROR = 3;
    private static final String TABLE = "action_logs";
    private static final String COLUMNS_EVERY_BUILD_WROTE = "id, action, description, ip_address, \"timestamp\", user_email, user_id";
    private static final LocalDateTime JUNE = LocalDateTime.of(2026, 6, 16, 17, 46);

    /** action_logs exactly as the production dump of 2026-06-16 (core-backend/backups, schema only) shows it. */
    private static final String JUNE_SCHEMA = """
            CREATE TABLE action_logs (
                id bigserial NOT NULL,
                action character varying(255),
                description character varying(1000),
                ip_address character varying(255),
                "timestamp" timestamp(6) without time zone,
                user_email character varying(255),
                user_id bigint,
                CONSTRAINT action_logs_pkey PRIMARY KEY (id)
            );
            """;

    @Container
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withExposedPorts(PORT)
            .withEnv("POSTGRES_DB", DATABASE)
            .withEnv("POSTGRES_USER", USERNAME)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .waitingFor(Wait.forLogMessage(READY_LOG, READY_LOG_TIMES));

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> jdbcUrl(DATABASE));
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
    }

    /** The database of the context: action_logs of the current mapping, as Hibernate created it. */
    @Autowired private JdbcTemplate jdbc;

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(PORT) + "/" + database;
    }

    /** An empty database of the container; every statement on it opens and closes a connection of its own. */
    private JdbcTemplate freshDatabase(String name) {
        jdbc.execute("drop database if exists " + name);
        jdbc.execute("create database " + name);
        return new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(name), USERNAME, PASSWORD));
    }

    /** action_logs of June, with the kinds of rows the builds of the time wrote. */
    private JdbcTemplate juneDatabase(String name) {
        JdbcTemplate db = freshDatabase(name);
        db.execute(JUNE_SCHEMA);
        String insert = "insert into action_logs (action, description, ip_address, \"timestamp\", user_email, user_id) "
                + "values (?, ?, ?, ?, ?, ?)";
        db.update(insert, "LOGIN_SUCCESS", "Đăng nhập hợp lệ vào App Mobile", "203.0.113.7", JUNE, "alice@example.com", null);
        db.update(insert, "LOGIN_LOCKED", "Hệ thống tự động kích hoạt khóa IP do phát hiện Brute-force", "198.51.100.4",
                JUNE.plusMinutes(1), "mallory@example.com", null);
        db.update(insert, "CREATE_PLAN", "Tạo gói hội viên mới: VIP", null, JUNE.plusMinutes(2), "root", null);
        db.update(insert, "AUTO_WARNING", "x".repeat(1000), null, JUNE.plusMinutes(3), "HỆ THỐNG TỰ ĐỘNG", null);
        db.update(insert, "CHANGE_PASSWORD", null, null, null, null, 7L);
        return db;
    }

    private static ExecResult migrate(String database, String role) throws IOException, InterruptedException {
        POSTGRES.copyFileToContainer(MountableFile.forClasspathResource(MIGRATION), MIGRATION_IN_CONTAINER);
        return POSTGRES.execInContainer("psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", role, "-d", database,
                "-f", MIGRATION_IN_CONTAINER);
    }

    /** Runs the script as the owner of the table, requires it to succeed, and returns all psql printed (with the NOTICE). */
    private static String migrated(String database) throws IOException, InterruptedException {
        ExecResult result = migrate(database, USERNAME);
        assertEquals(0, result.getExitCode(), result.getStdout() + result.getStderr());
        assertFalse(result.getStderr().contains("ERROR"), result.getStderr());
        return result.getStdout() + result.getStderr();
    }

    private static ExecResult refused(String database, String role) throws IOException, InterruptedException {
        ExecResult result = migrate(database, role);
        assertEquals(PSQL_STOPPED_ON_ERROR, result.getExitCode(), result.getStdout() + result.getStderr());
        return result;
    }

    /** One line per column: name, type, length, precision, scale, nullability, default. */
    private static List<String> columnsOf(JdbcTemplate db) {
        return db.queryForList("""
                select format('%s %s length=%s precision=%s scale=%s datetime=%s nullable=%s default=%s', column_name,
                              data_type, coalesce(character_maximum_length::text, '-'), coalesce(numeric_precision::text, '-'),
                              coalesce(numeric_scale::text, '-'), coalesce(datetime_precision::text, '-'), is_nullable,
                              coalesce(column_default, '-'))
                  from information_schema.columns
                 where table_schema = 'public' and table_name = ?
                 order by column_name""", String.class, TABLE);
    }

    private static List<String> indexesOf(JdbcTemplate db) {
        return db.queryForList("select indexdef from pg_indexes where schemaname = 'public' and tablename = ? order by indexname",
                String.class, TABLE);
    }

    private static List<String> constraintsOf(JdbcTemplate db) {
        return db.queryForList("select conname || ': ' || pg_get_constraintdef(oid) from pg_constraint "
                + "where conrelid = to_regclass(?) order by conname", String.class, TABLE);
    }

    private static List<String> rowsOf(JdbcTemplate db, String columns) {
        return db.queryForList("select row_to_json(t)::text from (select " + columns + " from action_logs) t order by 1",
                String.class);
    }

    private static List<String> snapshotOf(JdbcTemplate db) {
        if (db.queryForObject("select to_regclass('action_logs')::text", String.class) == null) {
            return List.of("no action_logs");
        }
        List<String> snapshot = new ArrayList<>();
        snapshot.addAll(columnsOf(db));
        snapshot.addAll(constraintsOf(db));
        snapshot.addAll(indexesOf(db));
        snapshot.addAll(rowsOf(db, "*"));
        return snapshot;
    }

    // ------------------------------------------------------------------------------------------------------------------

    @Test
    void theJuneTableBecomesTheTableOfTheCurrentMapping_columnByColumnAndIndexByIndex() throws Exception {
        JdbcTemplate db = juneDatabase("june_schema");

        String output = migrated("june_schema");

        assertEquals(columnsOf(jdbc), columnsOf(db));
        assertEquals(indexesOf(jdbc), indexesOf(db));
        assertEquals(constraintsOf(jdbc), constraintsOf(db));
        // And spelled out, so that a change on both sides cannot pass unnoticed.
        assertTrue(columnsOf(db).containsAll(List.of(
                "result character varying length=16 precision=- scale=- datetime=- nullable=YES default=-",
                "target_id bigint length=- precision=64 scale=0 datetime=- nullable=YES default=-",
                "target_type character varying length=32 precision=- scale=- datetime=- nullable=YES default=-")),
                columnsOf(db).toString());
        assertEquals(List.of(
                "CREATE UNIQUE INDEX action_logs_pkey ON public.action_logs USING btree (id)",
                "CREATE INDEX idx_action_logs_action_timestamp ON public.action_logs USING btree (action, \"timestamp\" DESC, id DESC)",
                "CREATE INDEX idx_action_logs_target_timestamp ON public.action_logs USING btree (target_type, target_id, \"timestamp\" DESC, id DESC)",
                "CREATE INDEX idx_action_logs_timestamp ON public.action_logs USING btree (\"timestamp\" DESC, id DESC)",
                "CREATE INDEX idx_action_logs_user_email_timestamp ON public.action_logs USING btree (user_email, \"timestamp\" DESC, id DESC)",
                "CREATE INDEX idx_action_logs_user_id_timestamp ON public.action_logs USING btree (user_id, \"timestamp\" DESC, id DESC)"),
                indexesOf(db));
        assertTrue(output.contains("action-log-audit-migration: ok rows=5 rows_with_target=0"), output);
    }

    @Test
    void noRowIsLostOrChanged_andTheHistoryKeepsItsCodes_withoutATargetOrAResultItNeverHad() throws Exception {
        JdbcTemplate db = juneDatabase("june_rows");
        List<String> before = rowsOf(db, COLUMNS_EVERY_BUILD_WROTE);

        migrated("june_rows");

        assertEquals(before, rowsOf(db, COLUMNS_EVERY_BUILD_WROTE));
        assertEquals(List.of("LOGIN_SUCCESS", "LOGIN_LOCKED", "CREATE_PLAN", "AUTO_WARNING", "CHANGE_PASSWORD"),
                db.queryForList("select action from action_logs order by id", String.class));
        assertEquals(0, db.queryForObject(
                "select count(*) from action_logs where target_type is not null or target_id is not null or result is not null",
                Integer.class));
    }

    @Test
    void aSecondRunChangesNothing() throws Exception {
        JdbcTemplate db = juneDatabase("june_twice");
        migrated("june_twice");
        List<String> afterTheFirstRun = snapshotOf(db);

        migrated("june_twice");

        assertEquals(afterTheFirstRun, snapshotOf(db));
    }

    @Test
    void theTableTheNewBuildCreatedIsAcceptedAsItIs() throws Exception {
        JdbcTemplate db = freshDatabase("created_by_the_new_build");
        ExecResult copy = POSTGRES.execInContainer("sh", "-c", String.format(
                "set -o pipefail; pg_dump -U %1$s -d %2$s --schema-only --table=%3$s | psql -X -q -v ON_ERROR_STOP=1 -U %1$s -d %4$s",
                USERNAME, DATABASE, TABLE, "created_by_the_new_build"));
        assertEquals(0, copy.getExitCode(), copy.getStderr());
        db.update("insert into action_logs (action, user_id, user_email, target_type, target_id, result, \"timestamp\") "
                + "values ('TRANSACTION_CREATED', 7, 'alice@example.com', 'TRANSACTION', 42, 'SUCCESS', now())");
        List<String> before = snapshotOf(db);

        migrated("created_by_the_new_build");

        assertEquals(before, snapshotOf(db));
    }

    @Test
    void afterTheMigrationTheOldBuildStillWritesItsRows_andTheNewBuildItsOwn() throws Exception {
        JdbcTemplate db = juneDatabase("both_builds");
        migrated("both_builds");

        db.update("insert into action_logs (action, description, ip_address, \"timestamp\", user_email, user_id) "
                + "values ('LOGIN_FAILED', 'Nhập sai mật khẩu tài khoản', '203.0.113.7', now(), 'bob@example.com', null)");
        db.update("insert into action_logs (action, user_id, user_email, target_type, target_id, result, \"timestamp\") "
                + "values ('AUTH_LOGIN_FAILED', null, 'bob@example.com', null, null, 'FAILURE', now())");

        assertEquals(2, db.queryForObject("select count(*) from action_logs where user_email = 'bob@example.com'", Integer.class));
    }

    @Test
    void anIndexLeftInvalidByAFailedConcurrentBuildStopsTheRunWithItsNameAndHowToRepairIt() throws Exception {
        JdbcTemplate db = juneDatabase("invalid_index");
        migrated("invalid_index");
        // What a CREATE INDEX CONCURRENTLY that failed halfway leaves behind (the container's user is a superuser).
        db.update("update pg_index set indisvalid = false where indexrelid = 'idx_action_logs_user_email_timestamp'::regclass");

        ExecResult result = refused("invalid_index", USERNAME);

        assertTrue(result.getStderr().contains("index idx_action_logs_user_email_timestamp is INVALID"), result.getStderr());
        assertTrue(result.getStderr().contains("DROP INDEX CONCURRENTLY idx_action_logs_user_email_timestamp"), result.getStderr());
    }

    @Test
    void aColumnOfAnotherTypeStopsTheRun_insteadOfBeingTakenForTheOneTheMappingExpects() throws Exception {
        JdbcTemplate db = juneDatabase("wrong_type");
        db.execute("alter table action_logs add column target_id character varying(64)");

        ExecResult result = refused("wrong_type", USERNAME);

        assertTrue(result.getStderr().contains("action_logs.target_id is character varying(64), expected bigint"),
                result.getStderr());
    }

    @Test
    void aRoleThatDoesNotOwnTheTableIsRefusedAndChangesNothing() throws Exception {
        JdbcTemplate db = juneDatabase("wrong_role");
        jdbc.execute("drop role if exists " + ANOTHER_SUPERUSER);
        jdbc.execute("create role " + ANOTHER_SUPERUSER + " superuser login");
        List<String> before = snapshotOf(db);

        ExecResult result = refused("wrong_role", ANOTHER_SUPERUSER);

        assertTrue(result.getStderr().contains("run this script as " + USERNAME + ", the owner of action_logs (current user: "
                + ANOTHER_SUPERUSER + ")"), result.getStderr());
        assertEquals(before, snapshotOf(db));
    }

    @Test
    void aDatabaseWithoutTheTableIsLeftAlone_theNewBuildCreatesItComplete() throws Exception {
        JdbcTemplate db = freshDatabase("no_table");

        ExecResult result = refused("no_table", USERNAME);

        assertTrue(result.getStderr().contains("there is no action_logs table here"), result.getStderr());
        assertEquals(List.of("no action_logs"), snapshotOf(db));
    }
}
