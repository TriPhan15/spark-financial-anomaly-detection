package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.ReceiptResponseDto;
import vn.edu.huit.finance.core.Dto.ReceiptRetryRequestDto;
import vn.edu.huit.finance.core.Dto.ReceiptUpdateRequestDto;
import vn.edu.huit.finance.core.Dto.TransactionRequestDto;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.Category;
import vn.edu.huit.finance.core.Entity.Receipt;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.ReceiptConflictException;
import vn.edu.huit.finance.core.Repository.CategoryRepository;
import vn.edu.huit.finance.core.Repository.ReceiptRepository;
import vn.edu.huit.finance.core.Repository.TransactionRepository;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two requests that reach the same receipt at the same moment, against a real database: the real services through their
 * real @Transactional proxies, each request in a thread and a database transaction of its own, one of them held in the
 * middle of its transaction while the other arrives. Nothing here is mocked between the services and the database, because
 * what is under test is what the database does with the second write: the conditional update of a retry (the claim), and
 * the version check of an edit or an approval; and that a retry never leaves a reading behind it did not commit.
 * <p>
 * The subclasses name the database. The same tests run on H2, like the rest of the suite, and on PostgreSQL 15, the
 * database of the deployment, so that H2 is not taken at its word for how a blocked write ends.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TransactionService.class, ReceiptProcessingService.class, OcrService.class, UserStatisticsService.class})
abstract class ReceiptConcurrencyContract {

    private static final BigDecimal WALLET = new BigDecimal("1000000.00");
    private static final long PROBE_MILLIS = 250;
    private static final long TIMEOUT_SECONDS = 10;
    private static final LocalDateTime CLIENT_DATE = LocalDateTime.of(2026, 9, 20, 9, 0);
    private static final String FULL_SCAN = "{\"seller\": {\"seller_name\": \"Bách Hóa Xanh\", \"address\": \"12 Nguyễn Trãi, Quận 1\","
            + " \"timestamp\": \"27/05/2026 10:30\", \"total_cost\": \"245.000\", \"total_cost_number\": 245000}}";
    private static final String UNREADABLE_TOTAL = "{\"seller\": {\"seller_name\": \"Highlands\"}}";
    private static final String ONE_SCAN = "{\"seller\": {\"seller_name\": \"Highlands Coffee\", \"total_cost_number\": 95000}}";
    private static final String OTHER_SCAN = "{\"seller\": {\"seller_name\": \"Highlands Coffee\", \"total_cost_number\": 59000}}";

    @Autowired private TransactionService transactionService;
    @Autowired private ReceiptProcessingService receiptService;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private OcrService ocrService;
    @MockBean private CurrentUserProvider currentUserProvider;
    @MockBean private AuditLogService auditLogService;
    @MockBean private BudgetAlertService budgetAlertService;

    private Long aliceId;

    @BeforeEach
    void setUp() {
        User alice = new User();
        alice.setUsername("alice");
        alice.setPassword("hash");
        alice.setEmail("alice@example.com");
        alice.setPhone("0900000001");
        alice.setWalletBalance(WALLET);
        aliceId = userRepository.saveAndFlush(alice).getId();

        Category food = new Category();
        food.setName("Ăn uống");
        food.setType("EXPENSE");
        categoryRepository.saveAndFlush(food);

        // Every call answers with a fresh read, inside whatever transaction the service has opened by then.
        when(currentUserProvider.getCurrentUser()).thenAnswer(invocation -> userRepository.findById(aliceId).orElseThrow());
    }

    @AfterEach
    void cleanUp() {
        transactionRepository.deleteAll();
        receiptRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---------------------------------------------------------------------------------------------------------- fixtures

    /** The checkout of the mobile app for Alice; returns the transaction id. */
    private Long checkoutScan(String rawReceiptData, String amount) {
        TransactionRequestDto request = new TransactionRequestDto();
        request.setAmount(new BigDecimal(amount));
        request.setLocation("Hóa đơn quét");
        request.setPaymentMethod("WALLET");
        request.setTransactionDate(CLIENT_DATE);
        request.setCategoryName("Ăn uống");
        request.setExpense(true);
        request.setRawReceiptData(rawReceiptData);
        return (Long) transactionService.createTransaction(request, aliceId).get("transactionId");
    }

    private Long receiptIdOf(Long transactionId) {
        return transactionRepository.findDetailedById(transactionId).orElseThrow().getReceipt().getId();
    }

    private Receipt stored(Long receiptId) {
        return receiptRepository.findDetailedById(receiptId).orElseThrow();
    }

    /** The committed status, read with SQL outside any transaction: what every other request sees at this moment. */
    private String statusInDatabase(Long receiptId) {
        return jdbc.queryForObject("select status from receipts where id = ?", String.class, receiptId);
    }

    /** What the AI answered for the receipt, reading after reading, as the attempts table holds it. */
    private List<String> answersInDatabase(Long receiptId) {
        return jdbc.queryForList("select ai_raw_data from receipt_processing_attempts where receipt_id = ? order by attempt_number",
                String.class, receiptId);
    }

    /** The idempotency keys the receipt's readings were recorded with, oldest first. */
    private List<String> keysInDatabase(Long receiptId) {
        return jdbc.queryForList("select client_id from receipt_processing_attempts where receipt_id = ? and client_id is not null"
                + " order by attempt_number", String.class, receiptId);
    }

    private BigDecimal amountOf(Long transactionId) {
        return transactionRepository.findDetailedById(transactionId).orElseThrow().getAmount();
    }

    private BigDecimal walletOfAlice() {
        return userRepository.findById(aliceId).orElseThrow().getWalletBalance();
    }

    private static ReceiptUpdateRequestDto edit(Long version, String vendorName, String total) {
        ReceiptUpdateRequestDto request = new ReceiptUpdateRequestDto();
        request.setVersion(version);
        request.setVendorName(vendorName);
        request.setExtractedTotal(total == null ? null : new BigDecimal(total));
        return request;
    }

    private static ReceiptRetryRequestDto retry(String rawReceiptData, String clientId) {
        ReceiptRetryRequestDto request = new ReceiptRetryRequestDto();
        request.setRawReceiptData(rawReceiptData);
        request.setClientId(clientId);
        return request;
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Holds a retry inside its transaction right after its claim: it holds the row there, and nothing is committed. */
    private void holdTheRetryAfterItsClaim(String rawReceiptData, CountDownLatch holdsTheClaim, CountDownLatch mayGoOn) {
        doAnswer(invocation -> {
            holdsTheClaim.countDown();
            awaitQuietly(mayGoOn);
            return invocation.callRealMethod();
        }).when(ocrService).extract(rawReceiptData);
    }

    /** Matches the audit entry of one action, whatever else the entry says. */
    private static AuditEntry entryOf(AuditAction action) {
        return argThat(entry -> entry != null && entry.action() == action);
    }

    /** Holds an edit inside its transaction right after its UPDATE was sent: the row is locked there, and nothing is committed. */
    private void holdTheEditAfterItsWrite(CountDownLatch hasWritten, CountDownLatch mayGoOn) {
        doAnswer(invocation -> {
            hasWritten.countDown();
            awaitQuietly(mayGoOn);
            return null;
        }).when(auditLogService).record(entryOf(AuditAction.RECEIPT_UPDATED));
    }

    // --------------------------------------------------------------------------------------------------- retry / retry

    @Test
    void ofTwoRetriesThatArriveTogetherOneIsRecordedAndTheOtherIsRefusedWithoutReadingAnything() throws Exception {
        Long transactionId = checkoutScan(UNREADABLE_TOTAL, "90000");
        Long receiptId = receiptIdOf(transactionId);
        CountDownLatch firstHoldsTheClaim = new CountDownLatch(1);
        CountDownLatch firstMayCommit = new CountDownLatch(1);
        holdTheRetryAfterItsClaim(ONE_SCAN, firstHoldsTheClaim, firstMayCommit);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ReceiptResponseDto recorded;
        try {
            Future<ReceiptResponseDto> first = pool.submit(() -> receiptService.retryMyReceipt(receiptId, retry(ONE_SCAN, "attempt-a")));
            assertTrue(firstHoldsTheClaim.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals("FAILED", statusInDatabase(receiptId), "a claim is a lock, not a status: nothing pretends to be in progress");

            // The other device: another answer of the AI for the same receipt, while the first retry has not committed.
            Future<ReceiptResponseDto> second = pool.submit(() -> receiptService.retryMyReceipt(receiptId, retry(OTHER_SCAN, "attempt-b")));
            assertThrows(TimeoutException.class, () -> second.get(PROBE_MILLIS, TimeUnit.MILLISECONDS),
                    "the second claim has to wait for the row the first one holds");

            firstMayCommit.countDown();
            recorded = first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ExecutionException refused = assertThrows(ExecutionException.class, () -> second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(ReceiptConflictException.class, refused.getCause(), "the claim that lost changed no row: 409");
        } finally {
            firstMayCommit.countDown();
            pool.shutdownNow();
        }

        assertEquals("COMPLETED_RAW", recorded.getStatus());
        Receipt receipt = stored(receiptId);
        assertEquals("COMPLETED_RAW", receipt.getStatus());
        assertEquals(2L, receipt.getVersion(), "one claim and one reading, not two of either");
        assertMoney("95000", receipt.getExtractedTotal());
        assertEquals(List.of(UNREADABLE_TOTAL, ONE_SCAN), answersInDatabase(receiptId), "the refused answer was never recorded");
        assertEquals(List.of("attempt-a"), keysInDatabase(receiptId));
        verify(ocrService, never()).extract(OTHER_SCAN);
        // One receipt, one transaction, and the money where the checkout left it.
        assertEquals(1, receiptRepository.count());
        assertEquals(1, transactionRepository.count());
        assertMoney("90000", amountOf(transactionId));
        assertMoney("910000", walletOfAlice());
    }

    @Test
    void aRepeatOfARetryThatArrivesWhileTheFirstIsStillRunningIsAnsweredWithItsResultAndReadsNothingAgain() throws Exception {
        Long receiptId = receiptIdOf(checkoutScan(UNREADABLE_TOTAL, "90000"));
        CountDownLatch firstHoldsTheClaim = new CountDownLatch(1);
        CountDownLatch firstMayCommit = new CountDownLatch(1);
        holdTheRetryAfterItsClaim(ONE_SCAN, firstHoldsTheClaim, firstMayCommit);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ReceiptResponseDto first;
        ReceiptResponseDto repeated;
        try {
            Future<ReceiptResponseDto> firstCall = pool.submit(() -> receiptService.retryMyReceipt(receiptId, retry(ONE_SCAN, "attempt-a")));
            assertTrue(firstHoldsTheClaim.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            // The network retry: the very same request again, same key, while the first one has not committed.
            Future<ReceiptResponseDto> repeat = pool.submit(() -> receiptService.retryMyReceipt(receiptId, retry(ONE_SCAN, "attempt-a")));
            assertThrows(TimeoutException.class, () -> repeat.get(PROBE_MILLIS, TimeUnit.MILLISECONDS),
                    "the repeat has to wait for the row the first request holds");

            firstMayCommit.countDown();
            first = firstCall.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            repeated = repeat.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            firstMayCommit.countDown();
            pool.shutdownNow();
        }

        assertEquals("COMPLETED_RAW", first.getStatus());
        assertEquals("COMPLETED_RAW", repeated.getStatus());
        assertEquals(first.getVersion(), repeated.getVersion());
        assertEquals(2L, stored(receiptId).getVersion());
        assertEquals(List.of(UNREADABLE_TOTAL, ONE_SCAN), answersInDatabase(receiptId), "one reading for the key, not two");
        verify(ocrService, times(1)).extract(ONE_SCAN);
        verify(auditLogService, times(1)).record(entryOf(AuditAction.RECEIPT_RETRIED));
        assertEquals(1, receiptRepository.count());
    }

    @Test
    void whenTheFirstOfTwoRetriesRollsBackTheOneThatWaitedForItIsRecordedAndNothingOfTheFirstRemains() throws Exception {
        Long transactionId = checkoutScan(UNREADABLE_TOTAL, "90000");
        Long receiptId = receiptIdOf(transactionId);
        CountDownLatch firstHoldsTheClaim = new CountDownLatch(1);
        CountDownLatch firstMayFail = new CountDownLatch(1);
        // The first retry dies after its claim, while it holds the row: its whole transaction is rolled back.
        doAnswer(invocation -> {
            firstHoldsTheClaim.countDown();
            awaitQuietly(firstMayFail);
            throw new IllegalStateException("the first request died after its claim");
        }).when(ocrService).extract(ONE_SCAN);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ReceiptResponseDto recorded;
        try {
            Future<ReceiptResponseDto> first = pool.submit(() -> receiptService.retryMyReceipt(receiptId, retry(ONE_SCAN, "attempt-a")));
            assertTrue(firstHoldsTheClaim.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            Future<ReceiptResponseDto> second = pool.submit(() -> receiptService.retryMyReceipt(receiptId, retry(OTHER_SCAN, "attempt-b")));
            assertThrows(TimeoutException.class, () -> second.get(PROBE_MILLIS, TimeUnit.MILLISECONDS),
                    "the second claim has to wait for the row the first one holds");

            firstMayFail.countDown();
            ExecutionException died = assertThrows(ExecutionException.class, () -> first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, died.getCause());
            recorded = second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            firstMayFail.countDown();
            pool.shutdownNow();
        }

        assertEquals("COMPLETED_RAW", recorded.getStatus(), "the row was FAILED at its old version again, so the second claimed it");
        Receipt receipt = stored(receiptId);
        assertEquals("COMPLETED_RAW", receipt.getStatus());
        assertEquals(2L, receipt.getVersion(), "the claim of the first was rolled back: only the second's claim and reading count");
        assertMoney("59000", receipt.getExtractedTotal());
        assertEquals(List.of(UNREADABLE_TOTAL, OTHER_SCAN), answersInDatabase(receiptId), "nothing of the first request remains");
        assertEquals(List.of("attempt-b"), keysInDatabase(receiptId));
        verify(auditLogService, times(1)).record(entryOf(AuditAction.RECEIPT_RETRIED));
        assertEquals(1, receiptRepository.count());
        assertEquals(1, transactionRepository.count());
        assertMoney("90000", amountOf(transactionId));
        assertMoney("910000", walletOfAlice());
    }

    // ----------------------------------------------------------------------------------------------------- edit / edit

    @Test
    void ofTwoEditsMadeFromTheSameVersionThatArriveTogetherOneIsWrittenAndTheOtherChangesNothing() throws Exception {
        Long receiptId = receiptIdOf(checkoutScan(FULL_SCAN, "200000"));
        CountDownLatch firstHasWritten = new CountDownLatch(1);
        CountDownLatch firstMayCommit = new CountDownLatch(1);
        holdTheEditAfterItsWrite(firstHasWritten, firstMayCommit);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ReceiptResponseDto> first = pool.submit(() -> receiptService.editMyReceipt(receiptId, edit(0L, "Sửa lần một", "250000")));
            assertTrue(firstHasWritten.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            // Made from the same version 0: it passes every check of the service, because the first edit is not committed.
            Future<ReceiptResponseDto> second = pool.submit(() -> receiptService.editMyReceipt(receiptId, edit(0L, "Sửa lần hai", "999000")));
            assertThrows(TimeoutException.class, () -> second.get(PROBE_MILLIS, TimeUnit.MILLISECONDS),
                    "the second write has to wait for the row the first one holds");

            firstMayCommit.countDown();
            assertEquals("EDITED", first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getStatus());
            ExecutionException refused = assertThrows(ExecutionException.class, () -> second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(OptimisticLockingFailureException.class, refused.getCause(),
                    "the version of the row is no longer the one the second edit was made from: 409");
        } finally {
            firstMayCommit.countDown();
            pool.shutdownNow();
        }

        Receipt receipt = stored(receiptId);
        assertEquals("EDITED", receipt.getStatus());
        assertEquals("Sửa lần một", receipt.getVendorName());
        assertMoney("250000", receipt.getExtractedTotal());
        assertEquals(1L, receipt.getVersion());
        assertEquals(List.of(FULL_SCAN), answersInDatabase(receiptId));
        verify(auditLogService, times(1)).record(entryOf(AuditAction.RECEIPT_UPDATED));
    }

    // ------------------------------------------------------------------------------------------------- edit / approval

    @Test
    void anApprovalThatAnEditOvertookIsRolledBackWholeSoNeitherTheEditNorTheWalletIsOverwritten() throws Exception {
        Long transactionId = checkoutScan(FULL_SCAN, "200000"); // Alice typed 200,000, the AI read 245,000
        Long receiptId = receiptIdOf(transactionId);
        CountDownLatch approvalHasMovedTheWallet = new CountDownLatch(1);
        CountDownLatch approvalMayCommit = new CountDownLatch(1);
        // The approval is held after it locked the wallet and wrote 755,000 into it, before its own rows are written.
        doAnswer(invocation -> {
            approvalHasMovedTheWallet.countDown();
            awaitQuietly(approvalMayCommit);
            return null;
        }).when(budgetAlertService).notifyAfterUpdate(any(), any());
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<?> approval = pool.submit(() -> transactionService.approveReceipt(receiptId));
            assertTrue(approvalHasMovedTheWallet.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            // Meanwhile the owner corrects the total, from the version both of them read, and commits.
            assertEquals("EDITED", receiptService.editMyReceipt(receiptId, edit(0L, null, "300000")).getStatus());

            approvalMayCommit.countDown();
            ExecutionException refused = assertThrows(ExecutionException.class, () -> approval.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(OptimisticLockingFailureException.class, refused.getCause(),
                    "the approval was worked out from a total that is no longer the one on the receipt: 409");
        } finally {
            approvalMayCommit.countDown();
            pool.shutdownNow();
        }

        Receipt receipt = stored(receiptId);
        assertEquals("EDITED", receipt.getStatus(), "not APPROVED: the approval did not happen");
        assertMoney("300000", receipt.getExtractedTotal());
        assertEquals(1L, receipt.getVersion());
        // Neither the 245,000 the approval had computed nor its 45,000 out of the wallet survived its rollback.
        assertMoney("200000", amountOf(transactionId));
        assertMoney("800000", walletOfAlice());
    }

    @Test
    void anApprovalThatArrivesWhileAnEditIsBeingWrittenWaitsForItAndIsThenRolledBackWhole() throws Exception {
        Long transactionId = checkoutScan(FULL_SCAN, "200000");
        Long receiptId = receiptIdOf(transactionId);
        CountDownLatch editHasWritten = new CountDownLatch(1);
        CountDownLatch editMayCommit = new CountDownLatch(1);
        holdTheEditAfterItsWrite(editHasWritten, editMayCommit);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ReceiptResponseDto> edit = pool.submit(() -> receiptService.editMyReceipt(receiptId, edit(0L, null, "300000")));
            assertTrue(editHasWritten.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            // The approval reads the receipt as it is committed (245,000, version 0) and works everything out from that.
            Future<?> approval = pool.submit(() -> transactionService.approveReceipt(receiptId));
            assertThrows(TimeoutException.class, () -> approval.get(PROBE_MILLIS, TimeUnit.MILLISECONDS),
                    "the approval has to wait for the receipt row the edit holds");

            editMayCommit.countDown();
            assertEquals("EDITED", edit.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getStatus());
            ExecutionException refused = assertThrows(ExecutionException.class, () -> approval.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(OptimisticLockingFailureException.class, refused.getCause());
        } finally {
            editMayCommit.countDown();
            pool.shutdownNow();
        }

        Receipt receipt = stored(receiptId);
        assertEquals("EDITED", receipt.getStatus());
        assertMoney("300000", receipt.getExtractedTotal());
        assertEquals(1L, receipt.getVersion());
        assertMoney("200000", amountOf(transactionId));
        assertMoney("800000", walletOfAlice());
    }

    // ---------------------------------------------------------------------------------------------------- edit / retry

    @Test
    void anEditThatArrivesWhileARetryIsRunningIsRefusedAndOnlyOneMadeFromTheResultOfTheRetryGoesThrough() throws Exception {
        Long receiptId = receiptIdOf(checkoutScan(UNREADABLE_TOTAL, "90000"));
        CountDownLatch retryHoldsTheClaim = new CountDownLatch(1);
        CountDownLatch retryMayCommit = new CountDownLatch(1);
        holdTheRetryAfterItsClaim(ONE_SCAN, retryHoldsTheClaim, retryMayCommit);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<ReceiptResponseDto> retry = pool.submit(() -> receiptService.retryMyReceipt(receiptId, retry(ONE_SCAN, "attempt-a")));
            assertTrue(retryHoldsTheClaim.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            // To every other request the receipt is still FAILED, and a FAILED receipt is not edited: the retry wins.
            assertThrows(ReceiptConflictException.class, () -> receiptService.editMyReceipt(receiptId, edit(0L, "Sửa", null)));

            retryMayCommit.countDown();
            assertEquals("COMPLETED_RAW", retry.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getStatus());
        } finally {
            retryMayCommit.countDown();
            pool.shutdownNow();
        }

        // The edit that was prepared before the retry is refused by its version; one made from the new reading is accepted.
        assertThrows(ReceiptConflictException.class, () -> receiptService.editMyReceipt(receiptId, edit(0L, "Sửa", null)));
        assertEquals("EDITED", receiptService.editMyReceipt(receiptId, edit(2L, "Highlands Q3", null)).getStatus());

        Receipt receipt = stored(receiptId);
        assertEquals("Highlands Q3", receipt.getVendorName());
        assertMoney("95000", receipt.getExtractedTotal());
        assertEquals(3L, receipt.getVersion());
        assertEquals(List.of(UNREADABLE_TOTAL, ONE_SCAN), answersInDatabase(receiptId),
                "the edit changed the final fields and left the readings of the AI alone");
    }
}
