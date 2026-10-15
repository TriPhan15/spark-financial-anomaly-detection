-- =====================================================================================================================
-- Receipt Workflow Phase 1: receipt processing attempts, and the AI's answers kept apart from a receipt's final fields.
-- core-backend, PostgreSQL 15 (docker-compose service "db", image postgres:15-alpine).                    2026-10-01
--
-- HOW TO RUN (psql only: the script uses psql meta-commands)
--     psql -X -v ON_ERROR_STOP=1 -U <owner of the tables> -d <database> -f receipt-workflow-migration.sql
--   * As the role that owns the tables (DB_USERNAME, which is also POSTGRES_USER of the "db" service): the new table
--     must belong to the role the application uses. Any other role is refused.
--   * With core-backend stopped; then start the build that maps ReceiptProcessingAttempt. (That build may also start
--     first: it reads every legacy status, and its ddl-auto creates the same columns and table.) Never let a build older
--     than Phase 1 serve after this script: it knows neither COMPLETED_RAW, EDITED nor APPROVED.
--   * Without -1/--single-transaction: the script opens and commits its own transaction.
--   * After a backup (pg_dump), as before any change to production data.
--
-- WHAT IT GUARANTEES
--   * Additive: no table, column, constraint, index or row is dropped or deleted. transactions.raw_receipt_data and
--     receipts.ai_raw_data (the two LEGACY copies of the AI's answers) keep exactly what they hold.
--   * Atomic: one transaction. When a statement or a check fails, nothing at all is applied.
--   * Idempotent: a second run changes no row and no schema object.
--   * It writes fixed names and exact copies of the legacy answers only: no exception text, password, token or secret.
--
--   PART 0  guard        right role
--   PART A  schema       receipts.error_code/version/ai_raw_data, receipts_aud.error_code, receipt_processing_attempts
--   PART B  statuses     retired and misspelled names -> lifecycle names; a FAILED receipt's reason where the audit trail
--                        proves it
--   PART C  attempts     each legacy AI answer -> a LEGACY attempt (1, and 2 where the first lifecycle build overwrote it)
--   PART D  checks       rows and answers before/after; the transaction aborts on any surprise; a summary (NOTICE)
--   REPORT               after COMMIT, read-only: receipts per status, attempts per status, receipts to review by hand
-- =====================================================================================================================

\set ON_ERROR_STOP on

BEGIN;

-- Give up rather than queue behind a running application: the ALTERs need a short exclusive lock on receipts.
SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------------------------------------------------
-- PART 0. The role that owns receipts runs the script, so the new table and its sequence belong to it, exactly as if
-- Hibernate (ddl-auto, connected as that role) had created them.
-- ---------------------------------------------------------------------------------------------------------------------
DO $$
DECLARE
    receipts_owner text := (SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'receipts'::regclass);
BEGIN
    IF receipts_owner <> current_user THEN
        RAISE EXCEPTION 'receipt-workflow-migration: run this script as %, the owner of receipts (current user: %)',
            receipts_owner, current_user;
    END IF;
END
$$;

-- Helpers for this psql session only (pg_temp).
-- How the application reads a stored status (ReceiptStatus.fromStored): String.trim (every character up to U+0020) and
-- letter case are ignored.
CREATE OR REPLACE FUNCTION pg_temp.receipt_status_name(stored text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT
AS $fn$ SELECT upper(regexp_replace(stored, '^[\x01-\x20]+|[\x01-\x20]+$', '', 'g')) $fn$;

-- The AI answer a legacy column holds: its text exactly as stored, or NULL when it holds none (NULL, '' or blanks only).
-- Never parsed, never re-serialised.
CREATE OR REPLACE FUNCTION pg_temp.receipt_ai_answer(stored text) RETURNS text
    LANGUAGE sql IMMUTABLE
AS $fn$ SELECT CASE WHEN stored ~ '[^[:space:]]' THEN stored END $fn$;

-- ---------------------------------------------------------------------------------------------------------------------
-- PART A. Schema: the columns and the table the entities map, with the types, defaults and constraint names Hibernate
-- 6.4 gives them on PostgreSQL.
-- ---------------------------------------------------------------------------------------------------------------------

-- A1. receipts: Receipt.errorCode, Receipt.version (optimistic lock) and Receipt.aiRawData (LEGACY, read-only; the
--     entity still selects it, so the column has to exist even where it stays empty).
ALTER TABLE receipts ADD COLUMN IF NOT EXISTS error_code varchar(40);
ALTER TABLE receipts ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
ALTER TABLE receipts ADD COLUMN IF NOT EXISTS ai_raw_data text;

-- A2. Envers: receipts_aud follows the audited fields of Receipt, so it gains error_code. version (the optimistic lock)
--     and ai_raw_data (@NotAudited) are not audited. Nothing is created where there is no audit table.
DO $$
BEGIN
    IF to_regclass('receipts_aud') IS NOT NULL THEN
        ALTER TABLE receipts_aud ADD COLUMN IF NOT EXISTS error_code varchar(40);
    END IF;
END
$$;

-- A3. One row per reading of a receipt by the AI (ReceiptProcessingAttempt, @Immutable, not audited: the table is its
--     own insert-only history, so it gets no _aud table).
CREATE TABLE IF NOT EXISTS receipt_processing_attempts (
    id                 bigserial    NOT NULL,
    receipt_id         bigint       NOT NULL,
    attempt_number     integer      NOT NULL,
    status             varchar(20)  NOT NULL,
    ai_raw_data        text         NOT NULL,
    error_code         varchar(40),
    safe_error_message varchar(255),
    client_id          varchar(64),
    created_at         timestamp(6) NOT NULL,
    completed_at       timestamp(6),
    CONSTRAINT receipt_processing_attempts_pkey PRIMARY KEY (id)
);

-- A4. Its constraints, under the names the entity gives them, each added only where it is missing (a table that ddl-auto
--     created already has them). PART D checks every definition.
--     Indexes: the unique key (receipt_id, attempt_number) starts with receipt_id, so it serves every lookup by receipt
--     and the ON DELETE CASCADE from receipts; a separate index on receipt_id would only repeat it. No query filters
--     attempts by status, so status gets no index.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'receipt_processing_attempts'::regclass
                      AND conname = 'uk_receipt_attempts_receipt_number') THEN
        ALTER TABLE receipt_processing_attempts
            ADD CONSTRAINT uk_receipt_attempts_receipt_number UNIQUE (receipt_id, attempt_number);
    END IF;
    -- client_id is NULL for every attempt that no retry key recorded; PostgreSQL never counts two NULLs as equal in a
    -- unique key, so only a key that was really sent twice for the same receipt is refused.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'receipt_processing_attempts'::regclass
                      AND conname = 'uk_receipt_attempts_receipt_client_id') THEN
        ALTER TABLE receipt_processing_attempts
            ADD CONSTRAINT uk_receipt_attempts_receipt_client_id UNIQUE (receipt_id, client_id);
    END IF;
    -- @OnDelete(CASCADE): the attempts go with their receipt, and with nothing else.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'receipt_processing_attempts'::regclass
                      AND conname = 'fk_receipt_attempts_receipt') THEN
        ALTER TABLE receipt_processing_attempts
            ADD CONSTRAINT fk_receipt_attempts_receipt
            FOREIGN KEY (receipt_id) REFERENCES receipts (id) ON DELETE CASCADE;
    END IF;
END
$$;

COMMENT ON TABLE receipt_processing_attempts IS
    'One reading of a receipt by the AI: the answer exactly as received and its outcome. Insert-only; goes with its receipt.';
COMMENT ON COLUMN receipt_processing_attempts.status IS
    'COMPLETED | FAILED | LEGACY (an answer imported from a legacy column: the outcome of that reading was never recorded)';
COMMENT ON COLUMN receipts.ai_raw_data IS
    'LEGACY, read-only: the AI answer as the first lifecycle build kept it (overwritten by its retries). '
    'Imported into receipt_processing_attempts; never written again; to be dropped by a later migration.';
COMMENT ON COLUMN transactions.raw_receipt_data IS
    'LEGACY, read-only: the AI answer as the builds before the receipt lifecycle kept it. '
    'Imported into receipt_processing_attempts as attempt 1; never written again; to be dropped by a later migration.';

-- ---------------------------------------------------------------------------------------------------------------------
-- Starting point, for PART D (temporary, gone at COMMIT). PART A changed no row, so this is every row as it was before
-- the script.
-- ---------------------------------------------------------------------------------------------------------------------

-- Fingerprint of a legacy answer column (row id + md5 of the text, in id order): equal before and after = not one
-- character of it changed.
CREATE OR REPLACE FUNCTION pg_temp.transaction_answers_fingerprint() RETURNS text
    LANGUAGE sql STABLE
AS $fn$ SELECT md5(coalesce(string_agg(id || ':' || coalesce(md5(raw_receipt_data), '-'), ',' ORDER BY id), ''))
          FROM transactions $fn$;

CREATE OR REPLACE FUNCTION pg_temp.receipt_answers_fingerprint() RETURNS text
    LANGUAGE sql STABLE
AS $fn$ SELECT md5(coalesce(string_agg(id || ':' || coalesce(md5(ai_raw_data), '-'), ',' ORDER BY id), ''))
          FROM receipts $fn$;

CREATE TEMP TABLE receipt_migration_start ON COMMIT DROP AS
SELECT (SELECT count(*) FROM receipts)                                AS receipts,
       (SELECT count(*) FROM transactions)                            AS transactions,
       (SELECT count(*) FROM receipt_processing_attempts)             AS attempts,
       (SELECT coalesce(max(id), 0) FROM receipt_processing_attempts) AS last_attempt_id,
       pg_temp.transaction_answers_fingerprint()                      AS transaction_answers,
       pg_temp.receipt_answers_fingerprint()                          AS receipt_answers;

CREATE TEMP TABLE receipt_migration_rows ON COMMIT DROP AS
SELECT id, status, error_code FROM receipts;

-- ---------------------------------------------------------------------------------------------------------------------
-- PART B. Statuses. The application already reads every legacy spelling (ReceiptStatus.fromStored); this stores the
-- names the lifecycle writes. Every changed row gets version + 1. The audit trail itself is never rewritten.
-- ---------------------------------------------------------------------------------------------------------------------

-- B1. Letter case and stray blanks around a lifecycle name ('pending', ' Failed '). A text that is no lifecycle name
--     at all is left as it is and listed in the REPORT.
UPDATE receipts
   SET status  = pg_temp.receipt_status_name(status),
       version = version + 1
 WHERE status <> pg_temp.receipt_status_name(status)
   AND pg_temp.receipt_status_name(status) IN ('PENDING', 'PROCESSING', 'COMPLETED_RAW', 'EDITED', 'FAILED',
                                                'WAITING_FOR_REVIEW', 'APPROVED', 'CANCELED');

-- B2. The retired SUCCESS, in any spelling. Every released build reached SUCCESS in exactly two ways: the checkout
--     inserted it, or an administrator approved the receipt from whatever status it had. So:
--       latest audited status is COMPLETED_RAW, EDITED or APPROVED -> that status (audited by a lifecycle build)
--       the audit trail shows any status other than SUCCESS       -> APPROVED (only an approval led to SUCCESS)
--       anything else, or no audit table                          -> COMPLETED_RAW (read by the AI at the checkout)
DO $$
BEGIN
    IF to_regclass('receipts_aud') IS NOT NULL THEN
        UPDATE receipts r
           SET status  = CASE
                             WHEN evidence.last_audited IN ('COMPLETED_RAW', 'EDITED', 'APPROVED')
                                 THEN evidence.last_audited
                             WHEN evidence.was_something_else
                                 THEN 'APPROVED'
                             ELSE 'COMPLETED_RAW'
                         END,
               version = r.version + 1
          FROM (SELECT s.id,
                       (SELECT pg_temp.receipt_status_name(a.status)
                          FROM receipts_aud a
                         WHERE a.id = s.id AND a.status IS NOT NULL
                         ORDER BY a.rev DESC
                         LIMIT 1) AS last_audited,
                       EXISTS (SELECT 1
                                 FROM receipts_aud a
                                WHERE a.id = s.id AND a.status IS NOT NULL
                                  AND pg_temp.receipt_status_name(a.status) <> 'SUCCESS') AS was_something_else
                  FROM receipts s
                 WHERE pg_temp.receipt_status_name(s.status) = 'SUCCESS') evidence
         WHERE r.id = evidence.id;
    ELSE
        UPDATE receipts
           SET status  = 'COMPLETED_RAW',
               version = version + 1
         WHERE pg_temp.receipt_status_name(status) = 'SUCCESS';
    END IF;
END
$$;

-- B3. Why a FAILED receipt failed, where nothing recorded it (no build before Phase 1 stored a reason), and only where
--     the audit trail proves it. Every released build failed a receipt in exactly two ways:
--       the audit trail shows any status other than FAILED -> REJECTED_BY_ADMIN (only a rejection moved one to FAILED)
--       it was inserted as FAILED (revtype 0)              -> AI_NO_TOTAL (the checkout, when the AI read no total)
--       no such proof (or no audit table)                  -> left empty, listed in the REPORT
--     A FAILED receipt without a reason still works as before (retry, feedback); the API shows no error message for it.
DO $$
BEGIN
    IF to_regclass('receipts_aud') IS NOT NULL THEN
        UPDATE receipts r
           SET error_code = proof.error_code,
               version    = r.version + 1
          FROM (SELECT f.id,
                       CASE
                           WHEN EXISTS (SELECT 1
                                          FROM receipts_aud a
                                         WHERE a.id = f.id AND a.status IS NOT NULL
                                           AND pg_temp.receipt_status_name(a.status) <> 'FAILED')
                               THEN 'REJECTED_BY_ADMIN'
                           WHEN EXISTS (SELECT 1
                                          FROM receipts_aud a
                                         WHERE a.id = f.id AND a.revtype = 0
                                           AND pg_temp.receipt_status_name(a.status) = 'FAILED')
                               THEN 'AI_NO_TOTAL'
                       END AS error_code
                  FROM receipts f
                 WHERE f.status = 'FAILED' AND f.error_code IS NULL) proof
         WHERE r.id = proof.id
           AND proof.error_code IS NOT NULL;
    END IF;
END
$$;

-- ---------------------------------------------------------------------------------------------------------------------
-- PART C. Attempts: the rule of ReceiptProcessingService.legacyAnswersOf, which imports these answers itself when a
-- receipt that has no attempt yet is retried: the transaction's answer first, then the receipt's when it differs. A
-- receipt that has any attempt already is never touched; one without an answer gets no attempt (none is invented).
-- ---------------------------------------------------------------------------------------------------------------------

-- C0. The application maps one transaction per receipt (transactions.receipt_id is unique in every schema Hibernate
--     made). A receipt carried by two would have two candidates for its first answer: refuse rather than guess.
DO $$
DECLARE
    carried_twice text;
BEGIN
    SELECT string_agg(receipt_id::text, ', ' ORDER BY receipt_id)
      INTO carried_twice
      FROM (SELECT receipt_id
              FROM transactions
             WHERE receipt_id IS NOT NULL
             GROUP BY receipt_id
            HAVING count(*) > 1) shared;
    IF carried_twice IS NOT NULL THEN
        RAISE EXCEPTION 'receipt-workflow-migration: receipt(s) % carried by more than one transaction; fix them by hand, then run the script again',
            carried_twice;
    END IF;
END
$$;

-- C1. Attempt 1: the answer the checkout kept on the transaction, or else the one the receipt kept. created_at is when
--     the receipt was created (now, for a row without that time, as the application does); when the reading ended was
--     never recorded, so completed_at stays empty.
INSERT INTO receipt_processing_attempts
       (receipt_id, attempt_number, status, ai_raw_data, error_code, safe_error_message, client_id, created_at,
        completed_at)
SELECT r.id, 1, 'LEGACY', legacy.answer, NULL, NULL, NULL, coalesce(r.created_at, localtimestamp), NULL
  FROM receipts r
  LEFT JOIN transactions t ON t.receipt_id = r.id
 CROSS JOIN LATERAL (SELECT coalesce(pg_temp.receipt_ai_answer(t.raw_receipt_data),
                                     pg_temp.receipt_ai_answer(r.ai_raw_data)) AS answer) legacy
 WHERE legacy.answer IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM receipt_processing_attempts a WHERE a.receipt_id = r.id)
 ORDER BY r.id;

-- C2. Attempt 2: the answer a retry of the first lifecycle build wrote over receipts.ai_raw_data, where the transaction
--     still holds the first one and the two differ.
INSERT INTO receipt_processing_attempts
       (receipt_id, attempt_number, status, ai_raw_data, error_code, safe_error_message, client_id, created_at,
        completed_at)
SELECT r.id, 2, 'LEGACY', r.ai_raw_data, NULL, NULL, NULL, coalesce(r.created_at, localtimestamp), NULL
  FROM receipts r
  JOIN transactions t ON t.receipt_id = r.id
  JOIN receipt_processing_attempts first_attempt
    ON first_attempt.receipt_id = r.id AND first_attempt.attempt_number = 1
 WHERE pg_temp.receipt_ai_answer(t.raw_receipt_data) IS NOT NULL
   AND pg_temp.receipt_ai_answer(r.ai_raw_data) IS NOT NULL
   AND r.ai_raw_data <> t.raw_receipt_data
   AND first_attempt.status = 'LEGACY'
   AND first_attempt.ai_raw_data = t.raw_receipt_data
   AND NOT EXISTS (SELECT 1 FROM receipt_processing_attempts a WHERE a.receipt_id = r.id AND a.attempt_number >= 2)
 ORDER BY r.id;

-- ---------------------------------------------------------------------------------------------------------------------
-- PART D. Checks: any surprise raises an exception and the whole transaction is rolled back. Then a summary (NOTICE).
-- ---------------------------------------------------------------------------------------------------------------------
DO $$
DECLARE
    at_start record;
    n     bigint;
BEGIN
    SELECT * INTO at_start FROM receipt_migration_start;

    -- D1. Nothing lost: every receipt, every transaction, every earlier attempt and both legacy answer columns.
    SELECT count(*) INTO n FROM receipts;
    IF n <> at_start.receipts THEN
        RAISE EXCEPTION 'receipt-workflow-migration: % receipt(s) before, % after', at_start.receipts, n;
    END IF;
    SELECT count(*) INTO n FROM transactions;
    IF n <> at_start.transactions THEN
        RAISE EXCEPTION 'receipt-workflow-migration: % transaction(s) before, % after', at_start.transactions, n;
    END IF;
    SELECT count(*) INTO n FROM receipt_processing_attempts WHERE id <= at_start.last_attempt_id;
    IF n <> at_start.attempts THEN
        RAISE EXCEPTION 'receipt-workflow-migration: % attempt(s) before, % of them left', at_start.attempts, n;
    END IF;
    IF pg_temp.transaction_answers_fingerprint() <> at_start.transaction_answers THEN
        RAISE EXCEPTION 'receipt-workflow-migration: transactions.raw_receipt_data changed';
    END IF;
    IF pg_temp.receipt_answers_fingerprint() <> at_start.receipt_answers THEN
        RAISE EXCEPTION 'receipt-workflow-migration: receipts.ai_raw_data changed';
    END IF;

    -- D2. Statuses: only the moves of PART B, and no SUCCESS left in any spelling.
    SELECT count(*) INTO n
      FROM receipts r
      JOIN receipt_migration_rows o ON o.id = r.id
     WHERE r.status <> o.status
       AND NOT (r.status = pg_temp.receipt_status_name(o.status)
                AND r.status IN ('PENDING', 'PROCESSING', 'COMPLETED_RAW', 'EDITED', 'FAILED', 'WAITING_FOR_REVIEW',
                                 'APPROVED', 'CANCELED'))
       AND NOT (pg_temp.receipt_status_name(o.status) = 'SUCCESS'
                AND r.status IN ('COMPLETED_RAW', 'EDITED', 'APPROVED'));
    IF n > 0 THEN
        RAISE EXCEPTION 'receipt-workflow-migration: % receipt(s) got a status PART B does not give', n;
    END IF;
    SELECT count(*) INTO n FROM receipts WHERE pg_temp.receipt_status_name(status) = 'SUCCESS';
    IF n > 0 THEN
        RAISE EXCEPTION 'receipt-workflow-migration: % receipt(s) still hold the retired status SUCCESS', n;
    END IF;

    -- D3. Error codes: only ever added, only to a FAILED receipt, only a reason the audit trail proves.
    SELECT count(*) INTO n
      FROM receipts r
      JOIN receipt_migration_rows o ON o.id = r.id
     WHERE r.error_code IS DISTINCT FROM o.error_code
       AND NOT coalesce(o.error_code IS NULL AND r.status = 'FAILED'
                        AND r.error_code IN ('REJECTED_BY_ADMIN', 'AI_NO_TOTAL'), false);
    IF n > 0 THEN
        RAISE EXCEPTION 'receipt-workflow-migration: % receipt(s) got an error code PART B does not give', n;
    END IF;

    -- D4. Attempts: every receipt that holds an answer has one, and every attempt this run added is a LEGACY copy of
    --     exactly the text it came from.
    SELECT count(*) INTO n
      FROM receipts r
      LEFT JOIN transactions t ON t.receipt_id = r.id
     WHERE coalesce(pg_temp.receipt_ai_answer(t.raw_receipt_data), pg_temp.receipt_ai_answer(r.ai_raw_data)) IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM receipt_processing_attempts a WHERE a.receipt_id = r.id);
    IF n > 0 THEN
        RAISE EXCEPTION 'receipt-workflow-migration: % receipt(s) hold a legacy AI answer but no attempt', n;
    END IF;
    SELECT count(*) INTO n
      FROM receipt_processing_attempts a
      JOIN receipts r ON r.id = a.receipt_id
      LEFT JOIN transactions t ON t.receipt_id = r.id
     WHERE a.id > at_start.last_attempt_id
       AND NOT coalesce(a.status = 'LEGACY' AND a.error_code IS NULL AND a.safe_error_message IS NULL
                        AND a.client_id IS NULL AND a.completed_at IS NULL
                        AND ((a.attempt_number = 1
                              AND a.ai_raw_data = coalesce(pg_temp.receipt_ai_answer(t.raw_receipt_data),
                                                           pg_temp.receipt_ai_answer(r.ai_raw_data)))
                          OR (a.attempt_number = 2
                              AND a.ai_raw_data = r.ai_raw_data
                              AND a.ai_raw_data <> t.raw_receipt_data)), false);
    IF n > 0 THEN
        RAISE EXCEPTION 'receipt-workflow-migration: % new attempt(s) differ from the legacy answer they copy', n;
    END IF;

    -- D5. The constraints of the attempts table, exactly as the entity defines them.
    SELECT count(*) INTO n
      FROM pg_constraint
     WHERE conrelid = 'receipt_processing_attempts'::regclass
       AND (conname::text, pg_get_constraintdef(oid)) IN (
               ('receipt_processing_attempts_pkey', 'PRIMARY KEY (id)'),
               ('uk_receipt_attempts_receipt_number', 'UNIQUE (receipt_id, attempt_number)'),
               ('uk_receipt_attempts_receipt_client_id', 'UNIQUE (receipt_id, client_id)'),
               ('fk_receipt_attempts_receipt', 'FOREIGN KEY (receipt_id) REFERENCES receipts(id) ON DELETE CASCADE'));
    IF n <> 4 THEN
        RAISE EXCEPTION 'receipt-workflow-migration: % of the 4 constraints of receipt_processing_attempts are as the entity defines them', n;
    END IF;

    -- D6. Summary. "not_backfilled" receipts got no attempt because they hold no answer (none, or blanks only);
    --     "manual_review" counts what the REPORT lists by id.
    RAISE NOTICE 'receipt-workflow-migration: receipts=% transactions=% attempts_before=% attempts_created=%',
        at_start.receipts, at_start.transactions, at_start.attempts,
        (SELECT count(*) FROM receipt_processing_attempts WHERE id > at_start.last_attempt_id);
    RAISE NOTICE 'receipt-workflow-migration: status SUCCESS->COMPLETED_RAW=% SUCCESS->EDITED=% SUCCESS->APPROVED=% respelled=%',
        (SELECT count(*) FROM receipts r JOIN receipt_migration_rows o ON o.id = r.id
          WHERE pg_temp.receipt_status_name(o.status) = 'SUCCESS' AND r.status = 'COMPLETED_RAW'),
        (SELECT count(*) FROM receipts r JOIN receipt_migration_rows o ON o.id = r.id
          WHERE pg_temp.receipt_status_name(o.status) = 'SUCCESS' AND r.status = 'EDITED'),
        (SELECT count(*) FROM receipts r JOIN receipt_migration_rows o ON o.id = r.id
          WHERE pg_temp.receipt_status_name(o.status) = 'SUCCESS' AND r.status = 'APPROVED'),
        (SELECT count(*) FROM receipts r JOIN receipt_migration_rows o ON o.id = r.id
          WHERE r.status <> o.status AND pg_temp.receipt_status_name(o.status) <> 'SUCCESS');
    RAISE NOTICE 'receipt-workflow-migration: error_code REJECTED_BY_ADMIN=% AI_NO_TOTAL=%',
        (SELECT count(*) FROM receipts r JOIN receipt_migration_rows o ON o.id = r.id
          WHERE o.error_code IS NULL AND r.error_code = 'REJECTED_BY_ADMIN'),
        (SELECT count(*) FROM receipts r JOIN receipt_migration_rows o ON o.id = r.id
          WHERE o.error_code IS NULL AND r.error_code = 'AI_NO_TOTAL');
    RAISE NOTICE 'receipt-workflow-migration: not_backfilled no_answer=% blank_answer=%',
        (SELECT count(*) FROM receipts r LEFT JOIN transactions t ON t.receipt_id = r.id
          WHERE NOT EXISTS (SELECT 1 FROM receipt_processing_attempts a WHERE a.receipt_id = r.id)
            AND t.raw_receipt_data IS NULL AND r.ai_raw_data IS NULL),
        (SELECT count(*) FROM receipts r LEFT JOIN transactions t ON t.receipt_id = r.id
          WHERE NOT EXISTS (SELECT 1 FROM receipt_processing_attempts a WHERE a.receipt_id = r.id)
            AND (t.raw_receipt_data IS NOT NULL OR r.ai_raw_data IS NOT NULL));
    RAISE NOTICE 'receipt-workflow-migration: info orphan_receipts=% transactions_with_answer_without_receipt=%',
        (SELECT count(*) FROM receipts r WHERE NOT EXISTS (SELECT 1 FROM transactions t WHERE t.receipt_id = r.id)),
        (SELECT count(*) FROM transactions
          WHERE receipt_id IS NULL AND pg_temp.receipt_ai_answer(raw_receipt_data) IS NOT NULL);
    RAISE NOTICE 'receipt-workflow-migration: manual_review failed_without_error_code=% unknown_status=%',
        (SELECT count(*) FROM receipts WHERE status = 'FAILED' AND error_code IS NULL),
        (SELECT count(*) FROM receipts
          WHERE pg_temp.receipt_status_name(status) NOT IN ('PENDING', 'PROCESSING', 'COMPLETED_RAW', 'EDITED', 'FAILED',
                                                            'WAITING_FOR_REVIEW', 'APPROVED', 'CANCELED'));
END
$$;

COMMIT;

-- ---------------------------------------------------------------------------------------------------------------------
-- REPORT (read-only).
-- ---------------------------------------------------------------------------------------------------------------------
\echo
\echo 'receipt-workflow-migration: receipts per stored status'
SELECT status, count(*) AS receipts FROM receipts GROUP BY status ORDER BY status;

\echo 'receipt-workflow-migration: attempts per status'
SELECT status, count(*) AS attempts FROM receipt_processing_attempts GROUP BY status ORDER BY status;

\echo 'receipt-workflow-migration: receipts to review by hand (no row: nothing to review)'
SELECT r.id AS receipt_id, r.status, review.reason
  FROM receipts r
  LEFT JOIN transactions t ON t.receipt_id = r.id
 CROSS JOIN LATERAL (VALUES
           (CASE WHEN r.status = 'FAILED' AND r.error_code IS NULL
                 THEN 'FAILED, reason not provable from the audit trail: error_code left empty' END),
           (CASE WHEN pg_temp.receipt_status_name(r.status) NOT IN ('PENDING', 'PROCESSING', 'COMPLETED_RAW', 'EDITED',
                                                                   'FAILED', 'WAITING_FOR_REVIEW', 'APPROVED', 'CANCELED')
                 THEN 'status is no lifecycle status: left as it is' END),
           (CASE WHEN NOT EXISTS (SELECT 1 FROM receipt_processing_attempts a WHERE a.receipt_id = r.id)
                      AND (t.raw_receipt_data IS NOT NULL OR r.ai_raw_data IS NOT NULL)
                 THEN 'legacy AI answer is blank: no attempt created' END)) AS review(reason)
 WHERE review.reason IS NOT NULL
 ORDER BY r.id, review.reason;
