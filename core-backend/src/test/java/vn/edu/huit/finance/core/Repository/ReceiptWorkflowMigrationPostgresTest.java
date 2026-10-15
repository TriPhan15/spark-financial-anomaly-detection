package vn.edu.huit.finance.core.Repository;

import org.hibernate.envers.RevisionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
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
import vn.edu.huit.finance.core.Entity.Receipt;
import vn.edu.huit.finance.core.Entity.ReceiptAttemptStatus;
import vn.edu.huit.finance.core.Entity.ReceiptErrorCode;
import vn.edu.huit.finance.core.Entity.ReceiptProcessingAttempt;
import vn.edu.huit.finance.core.Entity.ReceiptStatus;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The migration of Receipt Workflow Phase 1 ({@code src/main/resources/db/receipt-workflow-migration.sql}) on PostgreSQL
 * 15, the database of the deployment, run the way it will be run there: the file itself, by psql, inside the database
 * container. Each test makes a database of its own, shaped like one the script will meet, fills it with legacy rows, runs
 * the script and reads everything back with plain SQL:
 * <ul>
 *   <li>the receipt tables of production as the dump of 2026-06-16 shows them: no attempts table, no error code, no
 *       version, statuses and answers of every kind;</li>
 *   <li>a database the Phase 1 build started on first: its ddl-auto added the columns and the table, and it recorded
 *       attempts of its own;</li>
 *   <li>a database without an audit table, one where two transactions carry the same receipt, and a run by a role that
 *       does not own the tables.</li>
 * </ul>
 * The application context is there for one thing: Hibernate creates the schema of the current mapping in the database
 * of the context ({@link #DATABASE}), and the migrated tables are compared with it, column by column and constraint by
 * constraint. Skipped where Docker is not available. The container, its user and its databases exist nowhere else (see
 * ReceiptConcurrencyPostgresTest for why that matters).
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReceiptWorkflowMigrationPostgresTest {

    /** Keep in step with the db service of docker-compose.yml. */
    private static final String IMAGE = "postgres:15-alpine";
    private static final int PORT = 5432;
    /** The database of the application context, where Hibernate creates the schema of the current mapping. */
    private static final String DATABASE = "receipt_migration_it";
    private static final String USERNAME = "receipt_migration_it";
    private static final String PASSWORD = "integration-test-only";
    /** The server logs this once while it initialises the data directory and once more when it really accepts clients. */
    private static final String READY_LOG = ".*database system is ready to accept connections.*\\n";
    private static final int READY_LOG_TIMES = 2;

    private static final String MIGRATION = "db/receipt-workflow-migration.sql";
    private static final String MIGRATION_IN_CONTAINER = "/tmp/receipt-workflow-migration.sql";
    /** What psql answers when ON_ERROR_STOP ended a script at a failing statement. */
    private static final int PSQL_STOPPED_ON_ERROR = 3;
    /** The heading of the last list of the REPORT, and the rows of that list (they start with a receipt id). */
    private static final String REVIEW_HEADING = "receipts to review by hand";
    private static final Pattern REVIEW_ROW = Pattern.compile("(?m)^\\s*(\\d+) \\|");
    /** The database stamps a receipt that has no creation time with its own clock, in its own time zone. */
    private static final Duration CLOCKS_AND_ZONES_APART = Duration.ofDays(1);

    private static final String ATTEMPTS = "receipt_processing_attempts";
    private static final String RECEIPTS = "receipts";
    private static final String RECEIPTS_AUDIT = "receipts_aud";
    private static final String TRANSACTIONS = "transactions";
    /** The columns of a June receipt that the migration has no reason to touch (status is mapped, see the tests). */
    private static final String RECEIPT_FIELDS_LEFT_ALONE = "id, created_at, updated_at, extracted_date, extracted_total, image_url, vendor_name";
    private static final String JUNE_AUDIT_FIELDS = "id, rev, revtype, created_at, updated_at, extracted_date, extracted_total, image_url, status, vendor_name";

    /**
     * The receipt tables exactly as the production dump of 2026-06-16 (core-backend/backups, schema only) shows them:
     * columns, types, nullability and constraints, under their real names. The tables they reference and the migration
     * never reads (users, categories) are left out, and so are the foreign keys to them.
     */
    private static final String JUNE_SCHEMA = """
            CREATE TABLE custom_revision_entity (
                id integer NOT NULL,
                "timestamp" bigint NOT NULL,
                username varchar(255),
                CONSTRAINT custom_revision_entity_pkey PRIMARY KEY (id)
            );
            CREATE TABLE receipts (
                id bigint NOT NULL,
                created_at timestamp(6),
                updated_at timestamp(6),
                extracted_date date,
                extracted_total numeric(38,2),
                image_url varchar(255),
                status varchar(255) NOT NULL,
                vendor_name varchar(255),
                CONSTRAINT receipts_pkey PRIMARY KEY (id)
            );
            CREATE TABLE receipts_aud (
                id bigint NOT NULL,
                rev integer NOT NULL,
                revtype smallint,
                created_at timestamp(6),
                updated_at timestamp(6),
                extracted_date date,
                extracted_total numeric(38,2),
                image_url varchar(255),
                status varchar(255),
                vendor_name varchar(255),
                CONSTRAINT receipts_aud_pkey PRIMARY KEY (rev, id),
                CONSTRAINT fkjh9mcfdc8mlil0trff94t0kec FOREIGN KEY (rev) REFERENCES custom_revision_entity (id)
            );
            CREATE TABLE transactions (
                id bigint NOT NULL,
                created_at timestamp(6),
                updated_at timestamp(6),
                additional_images text,
                amount numeric(38,2) NOT NULL,
                category_name varchar(255),
                is_expense boolean NOT NULL,
                location varchar(255),
                note varchar(255),
                payment_method varchar(255),
                raw_receipt_data text,
                status varchar(255),
                transaction_date timestamp(6) NOT NULL,
                voucher_code varchar(255),
                category_id bigint NOT NULL,
                receipt_id bigint,
                user_id bigint NOT NULL,
                CONSTRAINT transactions_pkey PRIMARY KEY (id),
                CONSTRAINT uk_jkr7i9kla9knrfdt0gwav47n8 UNIQUE (receipt_id),
                CONSTRAINT fk7gu0iqanceg5bb779ymahu79f FOREIGN KEY (receipt_id) REFERENCES receipts (id)
            );
            """;

    /** What the ddl-auto of the Phase 1 build does to the June tables of receipts, as Hibernate 6.4 words it. */
    private static final String PHASE_ONE_DDL_AUTO_ON_RECEIPTS = """
            alter table if exists receipts add column ai_raw_data TEXT;
            alter table if exists receipts add column error_code varchar(40);
            alter table if exists receipts add column version bigint default 0 not null;
            alter table if exists receipts_aud add column error_code varchar(40);
            """;

    private static final String SUCCESS = ReceiptStatus.LEGACY_SUCCESS;
    private static final String PENDING = ReceiptStatus.PENDING.name();
    private static final String COMPLETED_RAW = ReceiptStatus.COMPLETED_RAW.name();
    private static final String EDITED = ReceiptStatus.EDITED.name();
    private static final String FAILED = ReceiptStatus.FAILED.name();
    private static final String WAITING_FOR_REVIEW = ReceiptStatus.WAITING_FOR_REVIEW.name();
    private static final String APPROVED = ReceiptStatus.APPROVED.name();
    private static final String CANCELED = ReceiptStatus.CANCELED.name();
    private static final String LEGACY = ReceiptAttemptStatus.LEGACY.name();
    private static final String COMPLETED = ReceiptAttemptStatus.COMPLETED.name();
    private static final String REJECTED_BY_ADMIN = ReceiptErrorCode.REJECTED_BY_ADMIN.name();
    private static final String AI_NO_TOTAL = ReceiptErrorCode.AI_NO_TOTAL.name();
    private static final String AI_UNREADABLE = ReceiptErrorCode.AI_UNREADABLE.name();
    /** Statuses older rows hold because they were written by hand. */
    private static final String PENDING_WRITTEN_BY_HAND = "pending";
    private static final String FAILED_WRITTEN_BY_HAND = " Failed\t";
    private static final String SUCCESS_WRITTEN_BY_HAND = "success";
    private static final String STATUS_NO_BUILD_KNEW = "DONE";

    // The June database: one receipt of every kind. A transaction that carries a receipt has the id of that receipt.
    /** SUCCESS, inserted as SUCCESS by the checkout: read by the AI, nothing more. */
    private static final long READ_AT_CHECKOUT = 1;
    /** SUCCESS again after an administrator rejected it and its owner sent the amount: approved. */
    private static final long APPROVED_AFTER_REVIEW = 2;
    /** FAILED after it was SUCCESS: rejected by an administrator. */
    private static final long REJECTED_BY_AN_ADMIN = 3;
    /** FAILED from its insert on: the checkout of a build that failed a receipt when the AI read no total. */
    private static final long FAILED_AT_CHECKOUT = 4;
    /** FAILED with no audit trail at all (written by hand): why it failed cannot be known. */
    private static final long FAILED_WITHOUT_AUDIT = 5;
    private static final long WAITING_FOR_ITS_REVIEW = 6;
    /** PENDING, and its transaction holds no answer. */
    private static final long PENDING_WITHOUT_ANSWER = 7;
    /** CANCELED, with an answer far longer than the API accepts today. */
    private static final long CANCELED_WITH_A_HUGE_ANSWER = 8;
    /** "pending" written by hand, no answer. */
    private static final long PENDING_IN_LOWER_CASE = 9;
    /** " Failed\t" written by hand, no audit trail. */
    private static final long FAILED_WITH_BLANKS_AROUND = 10;
    /** "success" written by hand, without a creation time. */
    private static final long SUCCESS_IN_LOWER_CASE = 11;
    /** SUCCESS, carried by no transaction: no answer anywhere. */
    private static final long ORPHAN_READ = 12;
    /** FAILED after SUCCESS (rejected), carried by no transaction. */
    private static final long ORPHAN_REJECTED = 13;
    /** A status no build ever knew. */
    private static final long UNKNOWN_STATUS = 14;
    /** SUCCESS, and its transaction holds blanks only. */
    private static final long BLANK_ANSWER = 15;
    /** SUCCESS, and its transaction holds an empty text. */
    private static final long EMPTY_ANSWER = 16;
    /** A transaction that holds an answer but carries no receipt: the answer has nowhere to go and stays where it is. */
    private static final long TRANSACTION_WITHOUT_RECEIPT = 17;

    // Answers of every shape the legacy columns can hold; none may come out of the migration different by one character.
    private static final String ANSWER_WITH_BLANKS_AROUND = "  {\"seller\": {\"seller_name\": \"Bách Hóa Xanh\", \"address\": "
            + "\"12 Nguyễn Trãi, Quận 1\", \"total_cost\": \"245.000\", \"total_cost_number\": 245000}}\n";
    private static final String COMPACT_ANSWER = "{\"seller\":{\"seller_name\":\"Highlands\",\"total_cost_number\":95000}}";
    /** Key order, number spellings and a 2.50 that any JSON parser would write back differently. */
    private static final String ANSWER_A_PARSER_WOULD_REWRITE = "{\"z\": 1, \"a\": {\"b\": [1, 2.50, 3e2, 1.0]}, "
            + "\"raw_text\": \"Tổng cộng: 59.000đ\"}";
    private static final String ANSWER_WITHOUT_TOTAL = "{\"seller\": {\"seller_name\": \"Highlands\", \"total_cost\": \"\"}}";
    private static final String NOT_JSON = "not json at all {";
    private static final String ESCAPES_AND_EMOJI = "{\"note\": \"\\u00e1 \\\\ \\\"quoted\\\"\", \"emoji\": \"🧾\"}";
    private static final String HUGE_ANSWER = "{\"raw_text\": \"" + "x".repeat(3 * Receipt.AI_RAW_DATA_MAX_LENGTH) + "\"}";
    private static final String ANSWER_OF_A_ROW_WRITTEN_BY_HAND = "{\"seller\": {\"seller_name\": \"Circle K\"}}";
    private static final String ANSWER_WITHOUT_CREATION_TIME = "{\"seller\": {\"total_cost_number\": 30000}}";
    private static final String ANSWER_OF_AN_UNKNOWN_STATUS = "{\"seller\": {\"seller_name\": \"GS25\"}}";
    private static final String BLANKS_ONLY = "  \n\t ";
    private static final String NO_TEXT = "";
    private static final String ANSWER_NO_RECEIPT_CARRIES = "{\"seller\": {\"seller_name\": \"7-Eleven\"}}";

    /** The answer each June receipt that holds one holds (always on its transaction: no June receipt has a column for it). */
    private static final Map<Long, String> JUNE_ANSWERS = Map.ofEntries(
            entry(READ_AT_CHECKOUT, ANSWER_WITH_BLANKS_AROUND),
            entry(APPROVED_AFTER_REVIEW, COMPACT_ANSWER),
            entry(REJECTED_BY_AN_ADMIN, ANSWER_A_PARSER_WOULD_REWRITE),
            entry(FAILED_AT_CHECKOUT, ANSWER_WITHOUT_TOTAL),
            entry(FAILED_WITHOUT_AUDIT, NOT_JSON),
            entry(WAITING_FOR_ITS_REVIEW, ESCAPES_AND_EMOJI),
            entry(CANCELED_WITH_A_HUGE_ANSWER, HUGE_ANSWER),
            entry(FAILED_WITH_BLANKS_AROUND, ANSWER_OF_A_ROW_WRITTEN_BY_HAND),
            entry(SUCCESS_IN_LOWER_CASE, ANSWER_WITHOUT_CREATION_TIME),
            entry(UNKNOWN_STATUS, ANSWER_OF_AN_UNKNOWN_STATUS));

    // The database the Phase 1 build started on first.
    /** FAILED (AI_NO_TOTAL): the checkout's answer on the transaction, a Phase 1 retry's answer over receipts.ai_raw_data. */
    private static final long OVERWRITTEN_BY_A_RETRY = 21;
    /** COMPLETED_RAW, the same answer in both legacy columns. */
    private static final long SAME_ANSWER_TWICE = 22;
    /** Retried by the new build already: its legacy answer imported as attempt 1, the retry's reading as attempt 2. */
    private static final long RETRIED_BY_THE_NEW_BUILD = 23;
    /** Created by the new build: attempt 1 COMPLETED, no legacy answer. */
    private static final long READ_BY_THE_NEW_BUILD = 24;
    /** FAILED (AI_UNREADABLE) by Phase 1, carried by no transaction, without a creation time, its answer on the receipt. */
    private static final long ORPHAN_OF_PHASE_ONE = 25;
    /** SUCCESS now, but audited as COMPLETED_RAW and then EDITED by a lifecycle build (statuses mapped back by hand). */
    private static final long EDITED_THEN_MAPPED_BACK = 26;

    private static final String CHECKOUT_ANSWER = "{\"seller\": {\"seller_name\": \"Phúc Long\"}}";
    private static final String OVERWRITTEN_ANSWER = "{\"seller\": {\"seller_name\": \"Phúc Long\", \"total_cost\": \"\"}}";
    private static final String SAME_ANSWER = "{\"seller\": {\"seller_name\": \"Co.op Mart\", \"total_cost_number\": 120000}}";
    private static final String IMPORTED_BY_THE_RETRY = "{\"seller\": {\"seller_name\": \"Lotte\"}}";
    private static final String READ_BY_THE_RETRY = "{\"seller\": {\"seller_name\": \"Lotte\", \"total_cost_number\": 80000}}";
    private static final String RETRY_KEY = "retry-key-of-receipt-23";
    private static final String NEW_BUILD_ANSWER = "{\"seller\": {\"seller_name\": \"Big C\", \"total_cost_number\": 64000}}";
    private static final String PHASE_ONE_ORPHAN_ANSWER = "{\"raw_text\": \"noise\"}";
    private static final String ANSWER_OF_AN_EDITED_RECEIPT = "{\"seller\": {\"seller_name\": \"Katinat\", \"total_cost_number\": 55000}}";

    private static final LocalDateTime JUNE = LocalDateTime.of(2026, 6, 1, 8, 0);
    private static final BigDecimal AMOUNT = new BigDecimal("50000.00");
    private static final long ANY_USER_ID = 1;
    private static final long ANY_CATEGORY_ID = 1;
    private static final String REVISION_USER = "System_AI";
    private static final String ANOTHER_SUPERUSER = "migration_operator";

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

    /** The database of the context: the schema of the current mapping, as Hibernate created it. */
    @Autowired private JdbcTemplate jdbc;

    private record AttemptRow(long id, long receiptId, int number, String status, String aiRawData, String errorCode,
                              String safeErrorMessage, String clientId, LocalDateTime createdAt, LocalDateTime completedAt) {
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Building the databases
    // ------------------------------------------------------------------------------------------------------------------

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(PORT) + "/" + database;
    }

    /** An empty database of the container; every statement on it opens and closes a connection of its own. */
    private JdbcTemplate freshDatabase(String name) {
        jdbc.execute("drop database if exists " + name);
        jdbc.execute("create database " + name);
        return new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(name), USERNAME, PASSWORD));
    }

    private JdbcTemplate juneDatabase(String name) {
        JdbcTemplate db = freshDatabase(name);
        db.execute(JUNE_SCHEMA);
        fillWithJuneReceipts(db);
        return db;
    }

    /** The June tables after the Phase 1 build started on them: its ddl-auto added the columns and the attempts table. */
    private JdbcTemplate databaseThePhaseOneBuildStartedOn(String name) throws IOException, InterruptedException {
        JdbcTemplate db = freshDatabase(name);
        db.execute(JUNE_SCHEMA);
        db.execute(PHASE_ONE_DDL_AUTO_ON_RECEIPTS);
        // The attempts table exactly as Hibernate created it for the current mapping, copied from the context's database.
        ExecResult copy = POSTGRES.execInContainer("sh", "-c", String.format(
                "set -o pipefail; pg_dump -U %1$s -d %2$s --schema-only --table=%3$s | psql -X -q -v ON_ERROR_STOP=1 -U %1$s -d %4$s",
                USERNAME, DATABASE, ATTEMPTS, name));
        assertEquals(0, copy.getExitCode(), copy.getStderr());
        return db;
    }

    /** Every kind of receipt the June database can hold; see the constants for what each one is. */
    private static void fillWithJuneReceipts(JdbcTemplate db) {
        receipt(db, READ_AT_CHECKOUT, SUCCESS);
        audited(db, READ_AT_CHECKOUT, SUCCESS);
        carry(db, READ_AT_CHECKOUT, ANSWER_WITH_BLANKS_AROUND);

        receipt(db, APPROVED_AFTER_REVIEW, SUCCESS);
        audited(db, APPROVED_AFTER_REVIEW, SUCCESS, FAILED, WAITING_FOR_REVIEW, SUCCESS);
        carry(db, APPROVED_AFTER_REVIEW, COMPACT_ANSWER);

        receipt(db, REJECTED_BY_AN_ADMIN, FAILED);
        audited(db, REJECTED_BY_AN_ADMIN, SUCCESS, FAILED);
        carry(db, REJECTED_BY_AN_ADMIN, ANSWER_A_PARSER_WOULD_REWRITE);

        receipt(db, FAILED_AT_CHECKOUT, FAILED);
        audited(db, FAILED_AT_CHECKOUT, FAILED);
        carry(db, FAILED_AT_CHECKOUT, ANSWER_WITHOUT_TOTAL);

        receipt(db, FAILED_WITHOUT_AUDIT, FAILED);
        carry(db, FAILED_WITHOUT_AUDIT, NOT_JSON);

        receipt(db, WAITING_FOR_ITS_REVIEW, WAITING_FOR_REVIEW);
        audited(db, WAITING_FOR_ITS_REVIEW, SUCCESS, FAILED, WAITING_FOR_REVIEW);
        carry(db, WAITING_FOR_ITS_REVIEW, ESCAPES_AND_EMOJI);

        receipt(db, PENDING_WITHOUT_ANSWER, PENDING);
        carry(db, PENDING_WITHOUT_ANSWER, null);

        receipt(db, CANCELED_WITH_A_HUGE_ANSWER, CANCELED);
        audited(db, CANCELED_WITH_A_HUGE_ANSWER, PENDING, CANCELED);
        carry(db, CANCELED_WITH_A_HUGE_ANSWER, HUGE_ANSWER);

        receipt(db, PENDING_IN_LOWER_CASE, PENDING_WRITTEN_BY_HAND);
        carry(db, PENDING_IN_LOWER_CASE, null);

        receipt(db, FAILED_WITH_BLANKS_AROUND, FAILED_WRITTEN_BY_HAND);
        carry(db, FAILED_WITH_BLANKS_AROUND, ANSWER_OF_A_ROW_WRITTEN_BY_HAND);

        db.update("insert into receipts (id, status) values (?, ?)", SUCCESS_IN_LOWER_CASE, SUCCESS_WRITTEN_BY_HAND);
        carry(db, SUCCESS_IN_LOWER_CASE, ANSWER_WITHOUT_CREATION_TIME);

        receipt(db, ORPHAN_READ, SUCCESS);
        audited(db, ORPHAN_READ, SUCCESS);

        receipt(db, ORPHAN_REJECTED, FAILED);
        audited(db, ORPHAN_REJECTED, SUCCESS, FAILED);

        receipt(db, UNKNOWN_STATUS, STATUS_NO_BUILD_KNEW);
        carry(db, UNKNOWN_STATUS, ANSWER_OF_AN_UNKNOWN_STATUS);

        receipt(db, BLANK_ANSWER, SUCCESS);
        audited(db, BLANK_ANSWER, SUCCESS);
        carry(db, BLANK_ANSWER, BLANKS_ONLY);

        receipt(db, EMPTY_ANSWER, SUCCESS);
        audited(db, EMPTY_ANSWER, SUCCESS);
        carry(db, EMPTY_ANSWER, NO_TEXT);

        transaction(db, TRANSACTION_WITHOUT_RECEIPT, null, ANSWER_NO_RECEIPT_CARRIES);
    }

    /** The receipts of the database the Phase 1 build started on; see the constants for what each one is. */
    private static void fillWithPhaseOneReceipts(JdbcTemplate db) {
        phaseOneReceipt(db, OVERWRITTEN_BY_A_RETRY, FAILED, createdAt(OVERWRITTEN_BY_A_RETRY), AI_NO_TOTAL, OVERWRITTEN_ANSWER);
        carry(db, OVERWRITTEN_BY_A_RETRY, CHECKOUT_ANSWER);

        phaseOneReceipt(db, SAME_ANSWER_TWICE, COMPLETED_RAW, createdAt(SAME_ANSWER_TWICE), null, SAME_ANSWER);
        carry(db, SAME_ANSWER_TWICE, SAME_ANSWER);

        phaseOneReceipt(db, RETRIED_BY_THE_NEW_BUILD, COMPLETED_RAW, createdAt(RETRIED_BY_THE_NEW_BUILD), null, null);
        carry(db, RETRIED_BY_THE_NEW_BUILD, IMPORTED_BY_THE_RETRY);
        attempt(db, RETRIED_BY_THE_NEW_BUILD, 1, LEGACY, IMPORTED_BY_THE_RETRY, null, null);
        attempt(db, RETRIED_BY_THE_NEW_BUILD, 2, COMPLETED, READ_BY_THE_RETRY, RETRY_KEY, JUNE.plusMonths(4));

        phaseOneReceipt(db, READ_BY_THE_NEW_BUILD, COMPLETED_RAW, createdAt(READ_BY_THE_NEW_BUILD), null, null);
        carry(db, READ_BY_THE_NEW_BUILD, null);
        attempt(db, READ_BY_THE_NEW_BUILD, 1, COMPLETED, NEW_BUILD_ANSWER, null, createdAt(READ_BY_THE_NEW_BUILD));

        phaseOneReceipt(db, ORPHAN_OF_PHASE_ONE, FAILED, null, AI_UNREADABLE, PHASE_ONE_ORPHAN_ANSWER);

        phaseOneReceipt(db, EDITED_THEN_MAPPED_BACK, SUCCESS, createdAt(EDITED_THEN_MAPPED_BACK), null, null);
        audited(db, EDITED_THEN_MAPPED_BACK, COMPLETED_RAW, EDITED);
        carry(db, EDITED_THEN_MAPPED_BACK, ANSWER_OF_AN_EDITED_RECEIPT);
    }

    private static LocalDateTime createdAt(long receiptId) {
        return JUNE.plusDays(receiptId);
    }

    private static void receipt(JdbcTemplate db, long id, String status) {
        db.update("insert into receipts (id, status, created_at, updated_at, extracted_date, extracted_total, image_url, vendor_name) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, status, createdAt(id), createdAt(id), createdAt(id).toLocalDate(), AMOUNT,
                "https://res.cloudinary.com/demo/image/upload/receipt-" + id + ".jpg", "Vendor " + id);
    }

    private static void phaseOneReceipt(JdbcTemplate db, long id, String status, LocalDateTime createdAt, String errorCode,
                                        String aiRawData) {
        db.update("insert into receipts (id, status, created_at, error_code, ai_raw_data) values (?, ?, ?, ?, ?)",
                id, status, createdAt, errorCode, aiRawData);
    }

    /** The audit trail Envers writes for a receipt: its insert, then one revision per later status. */
    private static void audited(JdbcTemplate db, long receiptId, String... statuses) {
        for (int i = 0; i < statuses.length; i++) {
            Integer revision = db.queryForObject("select coalesce(max(id), 0) + 1 from custom_revision_entity", Integer.class);
            db.update("insert into custom_revision_entity (id, \"timestamp\", username) values (?, ?, ?)",
                    revision, revision.longValue(), REVISION_USER);
            RevisionType type = i == 0 ? RevisionType.ADD : RevisionType.MOD;
            db.update("insert into receipts_aud (id, rev, revtype, status) values (?, ?, ?, ?)",
                    receiptId, revision, type.getRepresentation(), statuses[i]);
        }
    }

    /** The transaction (same id) that carries the receipt, with what it holds in raw_receipt_data. */
    private static void carry(JdbcTemplate db, long receiptId, String rawReceiptData) {
        transaction(db, receiptId, receiptId, rawReceiptData);
    }

    private static void transaction(JdbcTemplate db, long id, Long receiptId, String rawReceiptData) {
        db.update("insert into transactions (id, amount, is_expense, transaction_date, category_id, user_id, receipt_id, "
                        + "raw_receipt_data) values (?, ?, true, ?, ?, ?, ?, ?)",
                id, AMOUNT, JUNE.plusDays(id), ANY_CATEGORY_ID, ANY_USER_ID, receiptId, rawReceiptData);
    }

    private static void attempt(JdbcTemplate db, long receiptId, int number, String status, String aiRawData, String clientId,
                                LocalDateTime completedAt) {
        db.update("insert into receipt_processing_attempts (receipt_id, attempt_number, status, ai_raw_data, client_id, "
                        + "created_at, completed_at) values (?, ?, ?, ?, ?, ?, ?)",
                receiptId, number, status, aiRawData, clientId, createdAt(receiptId), completedAt);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Running the script
    // ------------------------------------------------------------------------------------------------------------------

    private static ExecResult migrate(String database, String role) throws IOException, InterruptedException {
        POSTGRES.copyFileToContainer(MountableFile.forClasspathResource(MIGRATION), MIGRATION_IN_CONTAINER);
        return POSTGRES.execInContainer("psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", role, "-d", database,
                "-f", MIGRATION_IN_CONTAINER);
    }

    /** Runs the script as the owner of the tables, requires it to succeed, and returns all psql printed (with the NOTICEs). */
    private static String migrated(String database) throws IOException, InterruptedException {
        ExecResult result = migrate(database, USERNAME);
        assertEquals(0, result.getExitCode(), result.getStderr());
        assertFalse(result.getStderr().contains("ERROR"), result.getStderr());
        return result.getStdout() + result.getStderr();
    }

    /** The receipt ids the REPORT lists for review by hand, in its order. */
    private static List<Long> reviewedReceiptIdsIn(String output) {
        int heading = output.indexOf(REVIEW_HEADING);
        assertTrue(heading >= 0, output);
        Matcher row = REVIEW_ROW.matcher(output.substring(heading));
        List<Long> ids = new ArrayList<>();
        while (row.find()) {
            ids.add(Long.valueOf(row.group(1)));
        }
        return ids;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Reading the databases back
    // ------------------------------------------------------------------------------------------------------------------

    private static Map<Long, String> statusesIn(JdbcTemplate db) {
        return columnByReceipt(db, "select id, status from receipts");
    }

    /** Only the receipts that have an error code. */
    private static Map<Long, String> errorCodesIn(JdbcTemplate db) {
        return columnByReceipt(db, "select id, error_code from receipts where error_code is not null");
    }

    private static Map<Long, String> versionsIn(JdbcTemplate db) {
        return columnByReceipt(db, "select id, version::text from receipts");
    }

    private static Map<Long, String> columnByReceipt(JdbcTemplate db, String query) {
        Map<Long, String> values = new TreeMap<>();
        db.query(query, row -> {
            values.put(row.getLong(1), row.getString(2));
        });
        return values;
    }

    private static List<AttemptRow> attemptsIn(JdbcTemplate db) {
        return db.query("select * from receipt_processing_attempts order by receipt_id, attempt_number", (row, index) ->
                new AttemptRow(row.getLong("id"), row.getLong("receipt_id"), row.getInt("attempt_number"),
                        row.getString("status"), row.getString("ai_raw_data"), row.getString("error_code"),
                        row.getString("safe_error_message"), row.getString("client_id"),
                        row.getObject("created_at", LocalDateTime.class), row.getObject("completed_at", LocalDateTime.class)));
    }

    /** What the AI answered for each receipt, reading after reading, as the attempts hold it. */
    private static Map<Long, List<String>> answersByReceipt(List<AttemptRow> attempts) {
        Map<Long, List<String>> answers = new TreeMap<>();
        for (AttemptRow attempt : attempts) {
            answers.computeIfAbsent(attempt.receiptId(), receipt -> new ArrayList<>()).add(attempt.aiRawData());
        }
        return answers;
    }

    /** Every row of the table, as JSON, with only the given columns, in a stable order. */
    private static List<String> rowsOf(JdbcTemplate db, String table, String columns) {
        return db.queryForList("select row_to_json(t)::text from (select " + columns + " from " + table + ") t order by 1",
                String.class);
    }

    /** One line per column: name, type, length, precision, scale, nullability, default. */
    private static List<String> columnsOf(JdbcTemplate db, String table) {
        return db.queryForList("""
                select format('%s %s length=%s precision=%s scale=%s datetime=%s nullable=%s default=%s', column_name,
                              data_type, coalesce(character_maximum_length::text, '-'), coalesce(numeric_precision::text, '-'),
                              coalesce(numeric_scale::text, '-'), coalesce(datetime_precision::text, '-'), is_nullable,
                              coalesce(column_default, '-'))
                  from information_schema.columns
                 where table_schema = 'public' and table_name = ?
                 order by column_name""", String.class, table);
    }

    private static String columnOf(JdbcTemplate db, String table, String column) {
        return columnsOf(db, table).stream().filter(line -> line.startsWith(column + " ")).findFirst().orElse(null);
    }

    private static List<String> columnNamesOf(JdbcTemplate db, String table) {
        return db.queryForList("select column_name from information_schema.columns where table_schema = 'public' "
                + "and table_name = ? order by column_name", String.class, table);
    }

    private static List<String> constraintsOf(JdbcTemplate db, String table) {
        return db.queryForList("select conname || ': ' || pg_get_constraintdef(oid) from pg_constraint "
                + "where conrelid = to_regclass(?) order by conname", String.class, table);
    }

    private static List<String> indexesOf(JdbcTemplate db, String table) {
        return db.queryForList("select indexdef from pg_indexes where schemaname = 'public' and tablename = ? order by indexname",
                String.class, table);
    }

    /** The table's name when it exists, null when it does not. */
    private static String regclass(JdbcTemplate db, String table) {
        return db.queryForObject("select to_regclass(?)::text", String.class, table);
    }

    private static String ownerOf(JdbcTemplate db, String table) {
        return db.queryForObject("select pg_get_userbyid(relowner) from pg_class where oid = to_regclass(?)", String.class, table);
    }

    /** Schema and rows of every table the script works on, for comparing a database before and after a run. */
    private static List<String> snapshotOf(JdbcTemplate db) {
        List<String> snapshot = new ArrayList<>();
        for (String table : List.of(RECEIPTS, RECEIPTS_AUDIT, TRANSACTIONS, ATTEMPTS)) {
            if (regclass(db, table) == null) {
                snapshot.add(table + ": no such table");
                continue;
            }
            snapshot.add(table + ":");
            snapshot.addAll(columnsOf(db, table));
            snapshot.addAll(constraintsOf(db, table));
            snapshot.addAll(indexesOf(db, table));
            snapshot.addAll(rowsOf(db, table, "*"));
        }
        return snapshot;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The June database
    // ------------------------------------------------------------------------------------------------------------------

    @Test
    void noReceiptTransactionOrAuditRowIsLostAndNotOneCharacterOfALegacyColumnChanges() throws Exception {
        JdbcTemplate db = juneDatabase("june_nothing_lost");
        List<String> receipts = rowsOf(db, RECEIPTS, RECEIPT_FIELDS_LEFT_ALONE);
        List<String> transactions = rowsOf(db, TRANSACTIONS, "*");
        List<String> auditTrail = rowsOf(db, RECEIPTS_AUDIT, JUNE_AUDIT_FIELDS);
        List<String> receiptColumns = columnsOf(db, RECEIPTS);
        List<String> auditColumns = columnsOf(db, RECEIPTS_AUDIT);
        List<String> transactionColumns = columnsOf(db, TRANSACTIONS);

        migrated("june_nothing_lost");

        assertEquals(receipts, rowsOf(db, RECEIPTS, RECEIPT_FIELDS_LEFT_ALONE));
        // Every transaction, every column, raw_receipt_data included: the legacy answers stay where they were.
        assertEquals(transactions, rowsOf(db, TRANSACTIONS, "*"));
        assertEquals(auditTrail, rowsOf(db, RECEIPTS_AUDIT, JUNE_AUDIT_FIELDS));
        // Additive: the columns that were there are still there, as they were (image_url too, still 255 wide).
        assertEquals(transactionColumns, columnsOf(db, TRANSACTIONS));
        assertTrue(columnsOf(db, RECEIPTS).containsAll(receiptColumns));
        assertEquals(receiptColumns.size() + 3, columnsOf(db, RECEIPTS).size());
        assertTrue(columnsOf(db, RECEIPTS_AUDIT).containsAll(auditColumns));
        assertEquals(auditColumns.size() + 1, columnsOf(db, RECEIPTS_AUDIT).size());
        // receipts.ai_raw_data exists because the entity reads it; a June receipt has nothing in it.
        assertEquals(0, db.queryForObject("select count(*) from receipts where ai_raw_data is not null", Integer.class));
    }

    @Test
    void everyLegacyAnswerBecomesAttemptOneOfItsReceiptCharacterForCharacterAndNoAttemptIsInvented() throws Exception {
        JdbcTemplate db = juneDatabase("june_attempts");

        migrated("june_attempts");

        Map<Long, String> imported = new TreeMap<>();
        for (AttemptRow attempt : attemptsIn(db)) {
            assertNull(imported.put(attempt.receiptId(), attempt.aiRawData()), "a second attempt for " + attempt.receiptId());
            assertEquals(ReceiptProcessingAttempt.FIRST_ATTEMPT_NUMBER, attempt.number());
            assertEquals(ReceiptAttemptStatus.LEGACY, ReceiptAttemptStatus.valueOf(attempt.status()));
            assertNull(attempt.errorCode());
            assertNull(attempt.safeErrorMessage());
            assertNull(attempt.clientId());
            assertNull(attempt.completedAt(), "when a legacy reading ended was never recorded");
            if (attempt.receiptId() == SUCCESS_IN_LOWER_CASE) {
                // No creation time to copy: the moment of the migration, as the application does it (LocalDateTime.now()).
                Duration fromNow = Duration.between(attempt.createdAt(), LocalDateTime.now()).abs();
                assertTrue(fromNow.compareTo(CLOCKS_AND_ZONES_APART) < 0, attempt.createdAt().toString());
            } else {
                assertEquals(createdAt(attempt.receiptId()), attempt.createdAt());
            }
        }
        // Exactly the receipts that hold an answer, each with exactly that text: blanks around it, key order, number
        // spellings, escapes, an emoji, text that is no JSON at all, and an answer three times longer than the API takes.
        assertEquals(new TreeMap<>(JUNE_ANSWERS), imported);
        assertEquals(0, db.queryForObject("select count(*) from receipt_processing_attempts a join transactions t "
                + "on t.receipt_id = a.receipt_id where md5(a.ai_raw_data) <> md5(t.raw_receipt_data)", Integer.class));
    }

    @Test
    void aLegacyStatusBecomesALifecycleStatusOnlyAsFarAsTheAuditTrailProvesIt() throws Exception {
        JdbcTemplate db = juneDatabase("june_statuses");

        String output = migrated("june_statuses");

        assertEquals(Map.ofEntries(
                entry(READ_AT_CHECKOUT, COMPLETED_RAW),
                // SUCCESS after another status: only an administrator's approval ever led there.
                entry(APPROVED_AFTER_REVIEW, APPROVED),
                entry(REJECTED_BY_AN_ADMIN, FAILED),
                entry(FAILED_AT_CHECKOUT, FAILED),
                entry(FAILED_WITHOUT_AUDIT, FAILED),
                entry(WAITING_FOR_ITS_REVIEW, WAITING_FOR_REVIEW),
                entry(PENDING_WITHOUT_ANSWER, PENDING),
                entry(CANCELED_WITH_A_HUGE_ANSWER, CANCELED),
                entry(PENDING_IN_LOWER_CASE, PENDING),
                entry(FAILED_WITH_BLANKS_AROUND, FAILED),
                // No audit trail to prove an approval: read by the AI, nothing more is claimed.
                entry(SUCCESS_IN_LOWER_CASE, COMPLETED_RAW),
                entry(ORPHAN_READ, COMPLETED_RAW),
                entry(ORPHAN_REJECTED, FAILED),
                entry(UNKNOWN_STATUS, STATUS_NO_BUILD_KNEW),
                entry(BLANK_ANSWER, COMPLETED_RAW),
                entry(EMPTY_ANSWER, COMPLETED_RAW)), statusesIn(db));
        // A changed row is one version further (an edit prepared from the old one is refused); the others are untouched.
        assertEquals(Map.ofEntries(
                entry(READ_AT_CHECKOUT, "1"), entry(APPROVED_AFTER_REVIEW, "1"), entry(REJECTED_BY_AN_ADMIN, "1"),
                entry(FAILED_AT_CHECKOUT, "1"), entry(FAILED_WITHOUT_AUDIT, "0"), entry(WAITING_FOR_ITS_REVIEW, "0"),
                entry(PENDING_WITHOUT_ANSWER, "0"), entry(CANCELED_WITH_A_HUGE_ANSWER, "0"), entry(PENDING_IN_LOWER_CASE, "1"),
                entry(FAILED_WITH_BLANKS_AROUND, "1"), entry(SUCCESS_IN_LOWER_CASE, "1"), entry(ORPHAN_READ, "1"),
                entry(ORPHAN_REJECTED, "1"), entry(UNKNOWN_STATUS, "0"), entry(BLANK_ANSWER, "1"), entry(EMPTY_ANSWER, "1")),
                versionsIn(db));
        // Stored as the application writes them: every status but the unknown one is exactly the name of a lifecycle status.
        statusesIn(db).forEach((receiptId, status) -> {
            if (receiptId != UNKNOWN_STATUS) {
                assertEquals(status, ReceiptStatus.fromStored(status).map(Enum::name).orElse(null), "receipt " + receiptId);
            }
        });
        assertTrue(output.contains("status SUCCESS->COMPLETED_RAW=5 SUCCESS->EDITED=0 SUCCESS->APPROVED=1 respelled=2"), output);
    }

    @Test
    void aFailedReceiptGetsOnlyTheReasonItsAuditTrailProvesAndNoReasonIsGuessed() throws Exception {
        JdbcTemplate db = juneDatabase("june_reasons");

        String output = migrated("june_reasons");

        assertEquals(Map.of(
                REJECTED_BY_AN_ADMIN, REJECTED_BY_ADMIN,
                ORPHAN_REJECTED, REJECTED_BY_ADMIN,
                FAILED_AT_CHECKOUT, AI_NO_TOTAL), errorCodesIn(db));
        errorCodesIn(db).values().forEach(code -> assertTrue(ReceiptErrorCode.fromStored(code).isPresent(), code));
        // Nothing proves why these failed: still FAILED, with no reason rather than an invented one.
        assertEquals(FAILED, statusesIn(db).get(FAILED_WITHOUT_AUDIT));
        assertEquals(FAILED, statusesIn(db).get(FAILED_WITH_BLANKS_AROUND));
        assertTrue(output.contains("error_code REJECTED_BY_ADMIN=2 AI_NO_TOTAL=1"), output);
        assertTrue(output.contains("manual_review failed_without_error_code=2 unknown_status=1"), output);
    }

    @Test
    void theRunCountsWhatItCouldNotImportAndListsByIdWhatAPersonHasToDecide() throws Exception {
        juneDatabase("june_report");

        String output = migrated("june_report");

        assertTrue(output.contains("receipts=16 transactions=15 attempts_before=0 attempts_created=10"), output);
        // No answer at all: PENDING without one, "pending", both orphans. Blanks only: '  \n\t ' and ''.
        assertTrue(output.contains("not_backfilled no_answer=4 blank_answer=2"), output);
        assertTrue(output.contains("info orphan_receipts=2 transactions_with_answer_without_receipt=1"), output);
        assertTrue(output.contains("manual_review failed_without_error_code=2 unknown_status=1"), output);
        assertEquals(List.of(FAILED_WITHOUT_AUDIT, FAILED_WITH_BLANKS_AROUND, UNKNOWN_STATUS, BLANK_ANSWER, EMPTY_ANSWER),
                reviewedReceiptIdsIn(output));
    }

    @Test
    void aSecondRunChangesNoRowAndNoSchemaObject() throws Exception {
        JdbcTemplate db = juneDatabase("june_twice");
        migrated("june_twice");
        List<String> afterTheFirstRun = snapshotOf(db);

        String output = migrated("june_twice");

        assertEquals(afterTheFirstRun, snapshotOf(db));
        assertTrue(output.contains("attempts_before=10 attempts_created=0"), output);
        assertTrue(output.contains("status SUCCESS->COMPLETED_RAW=0 SUCCESS->EDITED=0 SUCCESS->APPROVED=0 respelled=0"), output);
        assertTrue(output.contains("error_code REJECTED_BY_ADMIN=0 AI_NO_TOTAL=0"), output);
    }

    @Test
    void theMigratedTablesAreWhatTheCurrentMappingDefinesColumnByColumnAndConstraintByConstraint() throws Exception {
        JdbcTemplate db = juneDatabase("june_schema");

        migrated("june_schema");

        // Against the schema Hibernate creates for the entities today.
        assertEquals(columnsOf(jdbc, ATTEMPTS), columnsOf(db, ATTEMPTS));
        assertEquals(constraintsOf(jdbc, ATTEMPTS), constraintsOf(db, ATTEMPTS));
        assertEquals(indexesOf(jdbc, ATTEMPTS), indexesOf(db, ATTEMPTS));
        assertEquals(columnNamesOf(jdbc, RECEIPTS), columnNamesOf(db, RECEIPTS));
        assertEquals(columnNamesOf(jdbc, RECEIPTS_AUDIT), columnNamesOf(db, RECEIPTS_AUDIT));
        for (String column : List.of("ai_raw_data", "error_code", "version")) {
            assertEquals(columnOf(jdbc, RECEIPTS, column), columnOf(db, RECEIPTS, column));
        }
        assertEquals(columnOf(jdbc, RECEIPTS_AUDIT, "error_code"), columnOf(db, RECEIPTS_AUDIT, "error_code"));

        // And spelled out, so that a change on both sides cannot pass unnoticed.
        assertEquals("version bigint length=- precision=64 scale=0 datetime=- nullable=NO default=0",
                columnOf(db, RECEIPTS, "version"));
        assertEquals("error_code character varying length=40 precision=- scale=- datetime=- nullable=YES default=-",
                columnOf(db, RECEIPTS, "error_code"));
        assertEquals("ai_raw_data text length=- precision=- scale=- datetime=- nullable=YES default=-",
                columnOf(db, RECEIPTS, "ai_raw_data"));
        assertEquals(List.of(
                "ai_raw_data text length=- precision=- scale=- datetime=- nullable=NO default=-",
                "attempt_number integer length=- precision=32 scale=0 datetime=- nullable=NO default=-",
                "client_id character varying length=64 precision=- scale=- datetime=- nullable=YES default=-",
                "completed_at timestamp without time zone length=- precision=- scale=- datetime=6 nullable=YES default=-",
                "created_at timestamp without time zone length=- precision=- scale=- datetime=6 nullable=NO default=-",
                "error_code character varying length=40 precision=- scale=- datetime=- nullable=YES default=-",
                "id bigint length=- precision=64 scale=0 datetime=- nullable=NO "
                        + "default=nextval('receipt_processing_attempts_id_seq'::regclass)",
                "receipt_id bigint length=- precision=64 scale=0 datetime=- nullable=NO default=-",
                "safe_error_message character varying length=255 precision=- scale=- datetime=- nullable=YES default=-",
                "status character varying length=20 precision=- scale=- datetime=- nullable=NO default=-"),
                columnsOf(db, ATTEMPTS));
        assertEquals(List.of(
                "fk_receipt_attempts_receipt: FOREIGN KEY (receipt_id) REFERENCES receipts(id) ON DELETE CASCADE",
                "receipt_processing_attempts_pkey: PRIMARY KEY (id)",
                "uk_receipt_attempts_receipt_client_id: UNIQUE (receipt_id, client_id)",
                "uk_receipt_attempts_receipt_number: UNIQUE (receipt_id, attempt_number)"), constraintsOf(db, ATTEMPTS));
        // The unique key on (receipt_id, attempt_number) is the index of every lookup by receipt: receipt_id leads it.
        assertEquals(List.of(
                "CREATE UNIQUE INDEX receipt_processing_attempts_pkey ON public.receipt_processing_attempts USING btree (id)",
                "CREATE UNIQUE INDEX uk_receipt_attempts_receipt_client_id ON public.receipt_processing_attempts "
                        + "USING btree (receipt_id, client_id)",
                "CREATE UNIQUE INDEX uk_receipt_attempts_receipt_number ON public.receipt_processing_attempts "
                        + "USING btree (receipt_id, attempt_number)"), indexesOf(db, ATTEMPTS));
        // Not audited (no _aud table), and owned by the role the application uses, like every other table.
        assertNull(regclass(db, ATTEMPTS + "_aud"));
        assertEquals(ownerOf(db, RECEIPTS), ownerOf(db, ATTEMPTS));
    }

    @Test
    void theConstraintsOfTheAttemptsHoldOnTheMigratedDatabase() throws Exception {
        JdbcTemplate db = juneDatabase("june_constraints");
        migrated("june_constraints");
        LocalDateTime now = LocalDateTime.now();
        String insert = "insert into receipt_processing_attempts (receipt_id, attempt_number, status, ai_raw_data, client_id, "
                + "created_at, completed_at) values (?, ?, ?, ?, ?, ?, ?)";

        // One attempt per number and receipt: attempt 1 of the first receipt is its legacy answer already.
        assertThrows(DataIntegrityViolationException.class,
                () -> db.update(insert, READ_AT_CHECKOUT, 1, COMPLETED, COMPACT_ANSWER, null, now, now));
        // A retry key once per receipt; the same key on another receipt is another retry.
        db.update(insert, READ_AT_CHECKOUT, 2, COMPLETED, COMPACT_ANSWER, RETRY_KEY, now, now);
        assertThrows(DataIntegrityViolationException.class,
                () -> db.update(insert, READ_AT_CHECKOUT, 3, COMPLETED, COMPACT_ANSWER, RETRY_KEY, now, now));
        db.update(insert, APPROVED_AFTER_REVIEW, 2, COMPLETED, COMPACT_ANSWER, RETRY_KEY, now, now);
        // Any number of attempts without a key (NULLs never collide in a PostgreSQL unique key).
        db.update(insert, READ_AT_CHECKOUT, 3, COMPLETED, COMPACT_ANSWER, null, now, now);
        db.update(insert, READ_AT_CHECKOUT, 4, COMPLETED, COMPACT_ANSWER, null, now, now);
        // No attempt of a receipt that does not exist, none without an answer.
        assertThrows(DataIntegrityViolationException.class,
                () -> db.update(insert, 999_999L, 1, COMPLETED, COMPACT_ANSWER, null, now, now));
        assertThrows(DataIntegrityViolationException.class,
                () -> db.update(insert, READ_AT_CHECKOUT, 5, COMPLETED, null, null, now, now));
        // The attempts go with their receipt.
        db.update(insert, ORPHAN_READ, 1, COMPLETED, COMPACT_ANSWER, null, now, now);
        db.update("delete from receipts where id = ?", ORPHAN_READ);
        assertEquals(0, db.queryForObject("select count(*) from receipt_processing_attempts where receipt_id = ?",
                Integer.class, ORPHAN_READ));
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Other databases the script can meet
    // ------------------------------------------------------------------------------------------------------------------

    @Test
    void onADatabaseThePhaseOneBuildStartedOnFirstItsAttemptsStayAndOnlyTheMissingOnesAreAdded() throws Exception {
        JdbcTemplate db = databaseThePhaseOneBuildStartedOn("phase_one_first");
        fillWithPhaseOneReceipts(db);
        List<AttemptRow> recordedByTheNewBuild = attemptsIn(db);

        String output = migrated("phase_one_first");

        List<AttemptRow> attempts = attemptsIn(db);
        assertTrue(attempts.containsAll(recordedByTheNewBuild), "an attempt the new build recorded was changed or removed");
        assertEquals(Map.of(
                // The checkout's answer first, then the one a Phase 1 retry wrote over it.
                OVERWRITTEN_BY_A_RETRY, List.of(CHECKOUT_ANSWER, OVERWRITTEN_ANSWER),
                SAME_ANSWER_TWICE, List.of(SAME_ANSWER),
                RETRIED_BY_THE_NEW_BUILD, List.of(IMPORTED_BY_THE_RETRY, READ_BY_THE_RETRY),
                READ_BY_THE_NEW_BUILD, List.of(NEW_BUILD_ANSWER),
                ORPHAN_OF_PHASE_ONE, List.of(PHASE_ONE_ORPHAN_ANSWER),
                EDITED_THEN_MAPPED_BACK, List.of(ANSWER_OF_AN_EDITED_RECEIPT)), answersByReceipt(attempts));
        long lastRecorded = recordedByTheNewBuild.stream().mapToLong(AttemptRow::id).max().orElseThrow();
        attempts.stream().filter(attempt -> attempt.id() > lastRecorded)
                .forEach(added -> assertEquals(LEGACY, added.status(), added.toString()));
        assertEquals(Map.of(
                OVERWRITTEN_BY_A_RETRY, FAILED,
                SAME_ANSWER_TWICE, COMPLETED_RAW,
                RETRIED_BY_THE_NEW_BUILD, COMPLETED_RAW,
                READ_BY_THE_NEW_BUILD, COMPLETED_RAW,
                ORPHAN_OF_PHASE_ONE, FAILED,
                // The audit trail of a lifecycle build names the status the receipt really had.
                EDITED_THEN_MAPPED_BACK, EDITED), statusesIn(db));
        assertEquals(Map.of(OVERWRITTEN_BY_A_RETRY, AI_NO_TOTAL, ORPHAN_OF_PHASE_ONE, AI_UNREADABLE), errorCodesIn(db));
        assertTrue(output.contains("attempts_before=3 attempts_created=5"), output);

        List<String> afterTheFirstRun = snapshotOf(db);
        migrated("phase_one_first");
        assertEquals(afterTheFirstRun, snapshotOf(db));
    }

    @Test
    void withoutAnAuditTableEverySuccessIsCompletedRawNoReasonIsInventedAndNoAuditTableIsCreated() throws Exception {
        JdbcTemplate db = freshDatabase("no_audit_trail");
        db.execute(JUNE_SCHEMA);
        db.execute("drop table receipts_aud; drop table custom_revision_entity");
        receipt(db, READ_AT_CHECKOUT, SUCCESS);
        carry(db, READ_AT_CHECKOUT, COMPACT_ANSWER);
        receipt(db, SUCCESS_IN_LOWER_CASE, SUCCESS_WRITTEN_BY_HAND);
        carry(db, SUCCESS_IN_LOWER_CASE, ANSWER_WITHOUT_CREATION_TIME);
        // Rejected by an administrator in fact, but no audit trail says so.
        receipt(db, REJECTED_BY_AN_ADMIN, FAILED);
        carry(db, REJECTED_BY_AN_ADMIN, ANSWER_A_PARSER_WOULD_REWRITE);

        migrated("no_audit_trail");

        assertEquals(Map.of(READ_AT_CHECKOUT, COMPLETED_RAW, SUCCESS_IN_LOWER_CASE, COMPLETED_RAW, REJECTED_BY_AN_ADMIN, FAILED),
                statusesIn(db));
        assertEquals(Map.of(), errorCodesIn(db));
        assertNull(regclass(db, RECEIPTS_AUDIT));
        assertEquals(Map.of(
                READ_AT_CHECKOUT, List.of(COMPACT_ANSWER),
                SUCCESS_IN_LOWER_CASE, List.of(ANSWER_WITHOUT_CREATION_TIME),
                REJECTED_BY_AN_ADMIN, List.of(ANSWER_A_PARSER_WOULD_REWRITE)), answersByReceipt(attemptsIn(db)));
    }

    /**
     * Two transactions carry the first receipt (a database whose unique key on transactions.receipt_id was never made).
     * The script stops before the attempts, when PART A has added the columns and the table and PART B has rewritten
     * statuses: one transaction, so none of it stays.
     */
    @Test
    void aRunThatStopsHalfwayLeavesTheDatabaseExactlyAsItWas() throws Exception {
        JdbcTemplate db = juneDatabase("two_carriers");
        db.execute("alter table transactions drop constraint uk_jkr7i9kla9knrfdt0gwav47n8");
        transaction(db, TRANSACTION_WITHOUT_RECEIPT + 1, READ_AT_CHECKOUT, COMPACT_ANSWER);
        List<String> before = snapshotOf(db);

        ExecResult result = migrate("two_carriers", USERNAME);

        assertEquals(PSQL_STOPPED_ON_ERROR, result.getExitCode(), result.getStdout() + result.getStderr());
        assertTrue(result.getStderr().contains("receipt(s) 1 carried by more than one transaction"), result.getStderr());
        assertTrue(result.getStdout().contains("ALTER TABLE") && result.getStdout().contains("UPDATE 2"),
                "PART A and PART B had run: " + result.getStdout());
        assertEquals(before, snapshotOf(db));
        assertNull(regclass(db, ATTEMPTS));
    }

    @Test
    void aRoleThatDoesNotOwnTheTablesIsRefusedAndChangesNothing() throws Exception {
        JdbcTemplate db = juneDatabase("wrong_role");
        jdbc.execute("drop role if exists " + ANOTHER_SUPERUSER);
        jdbc.execute("create role " + ANOTHER_SUPERUSER + " superuser login");
        List<String> before = snapshotOf(db);

        ExecResult result = migrate("wrong_role", ANOTHER_SUPERUSER);

        assertEquals(PSQL_STOPPED_ON_ERROR, result.getExitCode(), result.getStdout() + result.getStderr());
        assertTrue(result.getStderr().contains("run this script as " + USERNAME + ", the owner of receipts (current user: "
                + ANOTHER_SUPERUSER + ")"), result.getStderr());
        assertEquals(before, snapshotOf(db));
    }
}
