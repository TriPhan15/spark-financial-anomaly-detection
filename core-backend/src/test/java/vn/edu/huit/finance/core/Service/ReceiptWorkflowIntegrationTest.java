package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vn.edu.huit.finance.core.Dto.ReceiptResponseDto;
import vn.edu.huit.finance.core.Dto.ReceiptRetryRequestDto;
import vn.edu.huit.finance.core.Dto.ReceiptUpdateRequestDto;
import vn.edu.huit.finance.core.Dto.TransactionRequestDto;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.Category;
import vn.edu.huit.finance.core.Entity.Receipt;
import vn.edu.huit.finance.core.Entity.ReceiptProcessingAttempt;
import vn.edu.huit.finance.core.Entity.Transaction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.ReceiptConflictException;
import vn.edu.huit.finance.core.Exception.TransactionAccessDeniedException;
import vn.edu.huit.finance.core.Repository.CategoryRepository;
import vn.edu.huit.finance.core.Repository.ReceiptProcessingAttemptRepository;
import vn.edu.huit.finance.core.Repository.ReceiptRepository;
import vn.edu.huit.finance.core.Repository.TransactionRepository;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;
import vn.edu.huit.finance.core.Service.Cache.StatisticsChange;
import vn.edu.huit.finance.core.Service.Cache.StatisticsChangedEvent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The life of a receipt on a real database (H2): the checkout of the mobile app, then edits, retries, feedback, approval,
 * rejection and cancellation through the real services and their real @Transactional proxies. Nothing here is wrapped in a
 * test transaction, so every call commits or rolls back for real, and every check reads the database again afterwards;
 * the readings of the AI (the attempts) and the legacy columns are read with plain SQL, past the entities.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TransactionService.class, ReceiptProcessingService.class, OcrService.class, UserStatisticsService.class})
class ReceiptWorkflowIntegrationTest {

    private static final BigDecimal WALLET = new BigDecimal("1000000.00");
    private static final LocalDateTime CLIENT_DATE = LocalDateTime.of(2026, 9, 20, 9, 0);
    private static final String CLIENT_LOCATION = "Hóa đơn quét";
    private static final String FULL_SCAN = "{\"seller\": {\"seller_name\": \"Bách Hóa Xanh\", \"address\": \"12 Nguyễn Trãi, Quận 1\","
            + " \"timestamp\": \"27/05/2026 10:30\", \"total_cost\": \"245.000\", \"total_cost_number\": 245000}}";
    private static final String UNREADABLE_TOTAL = "{\"seller\": {\"seller_name\": \"Highlands\"}}";
    private static final String NEW_SCAN = "{\"seller\": {\"seller_name\": \"Highlands Coffee\", \"timestamp\": \"2026-09-20 08:15\","
            + " \"total_cost_number\": 95000}}";
    private static final String NOISE = "{\"raw_text\": \"noise\"}";
    private static final String PHASE_ONE_ANSWER = "{\"raw_text\": \"what a retry of the first lifecycle build overwrote\"}";

    /** Hears a statistics event the way StatisticsCacheEvictor does: once the transaction that published it has committed. */
    static class CommittedStatisticsEvents {
        private final List<StatisticsChangedEvent> heard = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onStatisticsChanged(StatisticsChangedEvent event) {
            heard.add(event);
        }

        List<StatisticsChangedEvent> heard() {
            return List.copyOf(heard);
        }

        void forget() {
            heard.clear();
        }
    }

    @TestConfiguration
    static class EventLog {
        @Bean
        CommittedStatisticsEvents committedStatisticsEvents() {
            return new CommittedStatisticsEvents();
        }
    }

    @Autowired private TransactionService transactionService;
    @Autowired private ReceiptProcessingService receiptService;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CommittedStatisticsEvents events;
    @SpyBean private ReceiptRepository receiptRepository;
    @SpyBean private ReceiptProcessingAttemptRepository attemptRepository;
    @SpyBean private OcrService ocrService;
    @MockBean private CurrentUserProvider currentUserProvider;
    @MockBean private AuditLogService auditLogService;
    @MockBean private BudgetAlertService budgetAlertService;

    private Long aliceId;
    private Long bobId;
    private Long signedInId;

    @BeforeEach
    void setUp() {
        aliceId = persistUser("alice", "0900000001");
        bobId = persistUser("bob", "0900000002");
        Category food = new Category();
        food.setName("Ăn uống");
        food.setType("EXPENSE");
        categoryRepository.saveAndFlush(food);

        signedInId = aliceId;
        // Every call answers with a fresh read, inside whatever transaction the service has opened by then.
        when(currentUserProvider.getCurrentUser()).thenAnswer(invocation -> userRepository.findById(signedInId).orElseThrow());
        events.forget();
    }

    @AfterEach
    void cleanUp() {
        transactionRepository.deleteAll();
        receiptRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---------------------------------------------------------------------------------------------------------- fixtures

    private Long persistUser(String name, String phone) {
        User user = new User();
        user.setUsername(name);
        user.setPassword("hash");
        user.setEmail(name + "@example.com");
        user.setPhone(phone);
        user.setWalletBalance(WALLET);
        return userRepository.saveAndFlush(user).getId();
    }

    /** The checkout of the mobile app for Alice: the amount she confirmed plus the JSON the AI produced; returns the transaction id. */
    private Long checkoutScan(String rawReceiptData, String amount) {
        TransactionRequestDto request = new TransactionRequestDto();
        request.setAmount(new BigDecimal(amount));
        request.setLocation(CLIENT_LOCATION);
        request.setPaymentMethod("WALLET");
        request.setTransactionDate(CLIENT_DATE);
        request.setCategoryName("Ăn uống");
        request.setExpense(true);
        request.setRawReceiptData(rawReceiptData);
        request.setImageUrl("https://img.example.com/1.jpg");
        return (Long) transactionService.createTransaction(request, aliceId).get("transactionId");
    }

    private Long receiptIdOf(Long transactionId) {
        return transactionInDatabase(transactionId).getReceipt().getId();
    }

    /** Alice's receipt of a scan the AI read in full (she typed 200,000, the AI read 245,000): COMPLETED_RAW, version 0. */
    private Long readReceiptOfAlice() {
        return receiptIdOf(checkoutScan(FULL_SCAN, "200000"));
    }

    /** Alice's receipt of a scan the AI read no total for (she typed 90,000): FAILED, version 0. */
    private Long failedReceiptOfAlice() {
        return receiptIdOf(checkoutScan(UNREADABLE_TOTAL, "90000"));
    }

    /** A read receipt of Alice whose row is then written the way older code or a hand left it, past the lifecycle. */
    private Long receiptOfAliceStoredAs(String status) {
        Long receiptId = readReceiptOfAlice();
        jdbc.update("update receipts set status = ? where id = ?", status, receiptId);
        return receiptId;
    }

    /**
     * Turns a receipt back into one written before the attempts existed: no attempt, its answer in the column of the
     * transaction (where every build before the lifecycle put it) and, when given, a second one in the legacy column of
     * the receipt (where the first lifecycle build put it and a retry of that build overwrote it).
     */
    private void predatesTheAttempts(Long receiptId, String status, String answerOnTheTransaction, String answerOnTheReceipt) {
        jdbc.update("delete from receipt_processing_attempts where receipt_id = ?", receiptId);
        jdbc.update("update transactions set raw_receipt_data = ? where receipt_id = ?", answerOnTheTransaction, receiptId);
        jdbc.update("update receipts set status = ?, error_code = null, ai_raw_data = ? where id = ?",
                status, answerOnTheReceipt, receiptId);
    }

    private Receipt stored(Long receiptId) {
        return receiptRepository.findDetailedById(receiptId).orElseThrow();
    }

    private Transaction transactionInDatabase(Long transactionId) {
        return transactionRepository.findDetailedById(transactionId).orElseThrow();
    }

    /** What the AI answered for the receipt, reading after reading, as the attempts table holds it. */
    private List<String> answersInDatabase(Long receiptId) {
        return jdbc.queryForList("select ai_raw_data from receipt_processing_attempts where receipt_id = ? order by attempt_number",
                String.class, receiptId);
    }

    /** Every attempt of the receipt, oldest first, as one line each: number, status, error code, key, answer, times. */
    private List<String> attemptsInDatabase(Long receiptId) {
        return jdbc.query("select * from receipt_processing_attempts where receipt_id = ? order by attempt_number",
                (row, index) -> String.join(" | ", row.getString("attempt_number"), row.getString("status"),
                        String.valueOf(row.getString("error_code")), String.valueOf(row.getString("client_id")),
                        row.getString("ai_raw_data"), row.getString("created_at"), String.valueOf(row.getString("completed_at"))),
                receiptId);
    }

    private List<String> attemptStatusesInDatabase(Long receiptId) {
        return jdbc.queryForList("select status from receipt_processing_attempts where receipt_id = ? order by attempt_number",
                String.class, receiptId);
    }

    private BigDecimal walletOf(Long userId) {
        return userRepository.findById(userId).orElseThrow().getWalletBalance();
    }

    private void signedInAs(Long userId) {
        signedInId = userId;
    }

    private static ReceiptUpdateRequestDto edit(Long version, String vendorName, String total, LocalDate date) {
        ReceiptUpdateRequestDto request = new ReceiptUpdateRequestDto();
        request.setVersion(version);
        request.setVendorName(vendorName);
        request.setExtractedTotal(total == null ? null : new BigDecimal(total));
        request.setExtractedDate(date);
        return request;
    }

    private static ReceiptRetryRequestDto retry(String rawReceiptData, String clientId) {
        ReceiptRetryRequestDto request = new ReceiptRetryRequestDto();
        request.setRawReceiptData(rawReceiptData);
        request.setClientId(clientId);
        return request;
    }

    /** Matches the audit entry of one action by one account, whatever else the entry says. */
    private static AuditEntry entryOf(AuditAction action, Long actorId) {
        return argThat(entry -> entry != null && entry.action() == action && actorId.equals(entry.actor().userId()));
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual, "expected " + expected + " but was null");
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private void assertNothingOfTheMoneyMoved(Long transactionId, String amount, String wallet) {
        assertMoney(amount, transactionInDatabase(transactionId).getAmount());
        assertMoney(wallet, walletOf(aliceId));
    }

    // ------------------------------------------------------------- the checkout: the outcome, and its reading as attempt 1

    @Test
    void aScanThatWasReadIsCompletedRawWithTheAnswerOfTheAiAsItsFirstAttemptAndTheFinalFieldsFromIt() {
        Long transactionId = checkoutScan(FULL_SCAN, "200000");

        Receipt receipt = stored(receiptIdOf(transactionId));
        assertEquals("COMPLETED_RAW", receipt.getStatus());
        assertNull(receipt.getErrorCode());
        assertEquals(0L, receipt.getVersion(), "inserted with its outcome, never updated to reach it");
        assertEquals("Bách Hóa Xanh", receipt.getVendorName());
        assertMoney("245000", receipt.getExtractedTotal());
        assertEquals(LocalDate.of(2026, 5, 27), receipt.getExtractedDate());
        assertEquals(List.of(FULL_SCAN), answersInDatabase(receipt.getId()));
        assertEquals(List.of("COMPLETED"), attemptStatusesInDatabase(receipt.getId()));
        // The answer has one copy: neither legacy column is written any more.
        assertNull(jdbc.queryForObject("select raw_receipt_data from transactions where id = ?", String.class, transactionId));
        assertNull(jdbc.queryForObject("select ai_raw_data from receipts where id = ?", String.class, receipt.getId()));
        assertEquals(1, receiptRepository.count());
    }

    @Test
    void aScanTheAiReadNoTotalForLeavesAFailedReceiptBehindAndTheCheckoutStands() {
        Long transactionId = checkoutScan(UNREADABLE_TOTAL, "90000");

        Receipt receipt = stored(receiptIdOf(transactionId));
        assertEquals("FAILED", receipt.getStatus());
        assertEquals("AI_NO_TOTAL", receipt.getErrorCode());
        assertNull(receipt.getExtractedTotal());
        assertEquals("Highlands", receipt.getVendorName());
        assertEquals("https://img.example.com/1.jpg", receipt.getImageUrl());
        assertEquals(List.of(UNREADABLE_TOTAL), answersInDatabase(receipt.getId()), "the answer of a failed reading is kept too");
        assertEquals(List.of("FAILED"), attemptStatusesInDatabase(receipt.getId()));
        assertEquals("AI không đọc được tổng tiền trên hóa đơn", jdbc.queryForObject(
                "select safe_error_message from receipt_processing_attempts where receipt_id = ?", String.class, receipt.getId()));
        assertNothingOfTheMoneyMoved(transactionId, "90000", "910000");
        assertEquals("FAILED", receiptService.getMyReceipt(receipt.getId()).getStatus());
        assertEquals("AI không đọc được tổng tiền trên hóa đơn", receiptService.getMyReceipt(receipt.getId()).getErrorMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"raw_text\": \"noise\"}", "[]"})
    void anAnswerTheAiCouldNotStructureIsKeptAsAFailedReceiptThatSaysSo(String answer) {
        Long receiptId = receiptIdOf(checkoutScan(answer, "90000"));

        Receipt receipt = stored(receiptId);
        assertEquals("FAILED", receipt.getStatus());
        assertEquals("AI_UNREADABLE", receipt.getErrorCode());
        assertEquals(List.of(answer), answersInDatabase(receiptId));
        assertEquals("AI_UNREADABLE", jdbc.queryForObject(
                "select error_code from receipt_processing_attempts where receipt_id = ?", String.class, receiptId));
    }

    @Test
    void noFlowEverStoresProcessingNeitherInTheReceiptsNorInTheirAuditTrail() {
        Long readId = readReceiptOfAlice();
        Long failedId = failedReceiptOfAlice();
        receiptService.retryMyReceipt(failedId, retry(NOISE, "again-1"));
        receiptService.retryMyReceipt(failedId, retry(NEW_SCAN, "again-2"));
        receiptService.editMyReceipt(readId, edit(0L, "Bách Hóa Xanh Q1", null, null));
        transactionService.approveReceipt(readId);
        receiptService.rejectReceipt(failedId);
        Long feedbackId = failedReceiptOfAlice();
        receiptService.submitFeedback(feedbackId, new BigDecimal("150000"));

        // Every commit above wrote a revision of the receipt it changed (Envers): not one of them ever saw PROCESSING.
        assertEquals(0, jdbc.queryForObject("select count(*) from receipts where upper(trim(status)) = 'PROCESSING'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from receipts_aud where upper(trim(status)) = 'PROCESSING'", Integer.class));
        assertTrue(jdbc.queryForObject("select count(*) from receipts_aud", Integer.class) >= 8, "the audit trail was really written");
    }

    // ---------------------------------------------------------------------- edits change the final fields, not the readings

    @Test
    void anEditChangesTheFinalFieldsAndNeitherTheReadingsOfTheAiNorTheTransactionNorTheWallet() {
        Long transactionId = checkoutScan(FULL_SCAN, "200000");
        Long receiptId = receiptIdOf(transactionId);
        List<String> readingsBefore = attemptsInDatabase(receiptId);

        ReceiptResponseDto edited = receiptService.editMyReceipt(receiptId,
                edit(0L, "  Bách Hóa Xanh Q1 ", "250000", LocalDate.of(2026, 5, 28)));

        assertEquals("EDITED", edited.getStatus());
        assertEquals(1L, edited.getVersion(), "the answer carries the version the row has after the edit");
        Receipt receipt = stored(receiptId);
        assertEquals("EDITED", receipt.getStatus());
        assertEquals(1L, receipt.getVersion());
        assertEquals("Bách Hóa Xanh Q1", receipt.getVendorName());
        assertMoney("250000", receipt.getExtractedTotal());
        assertEquals(LocalDate.of(2026, 5, 28), receipt.getExtractedDate());
        assertEquals(readingsBefore, attemptsInDatabase(receiptId), "an edit touches no attempt");
        // The transaction keeps the amount Alice typed and the date and place of the first reading; the wallet does not move.
        Transaction transaction = transactionInDatabase(transactionId);
        assertMoney("200000", transaction.getAmount());
        assertEquals(LocalDateTime.of(2026, 5, 27, 10, 30), transaction.getTransactionDate());
        assertEquals("12 Nguyễn Trãi, Quận 1", transaction.getLocation());
        assertMoney("800000", walletOf(aliceId));
        assertEquals(1, receiptRepository.count());
        verify(auditLogService).record(entryOf(AuditAction.RECEIPT_UPDATED, aliceId));
    }

    @Test
    void anEditedReceiptIsEditedAgainAndItsReadingsStayAsTheyWere() {
        Long receiptId = readReceiptOfAlice();
        List<String> readingsBefore = attemptsInDatabase(receiptId);
        receiptService.editMyReceipt(receiptId, edit(0L, "Bách Hóa Xanh Q1", null, null));

        ReceiptResponseDto again = receiptService.editMyReceipt(receiptId, edit(1L, null, "260000", null));

        assertEquals("EDITED", again.getStatus());
        assertEquals(2L, again.getVersion());
        Receipt receipt = stored(receiptId);
        assertEquals("Bách Hóa Xanh Q1", receipt.getVendorName(), "a field the second edit left out keeps the value of the first");
        assertMoney("260000", receipt.getExtractedTotal());
        assertEquals(readingsBefore, attemptsInDatabase(receiptId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSING", "FAILED", "WAITING_FOR_REVIEW", "APPROVED", "CANCELED"})
    void aReceiptThatIsNotReadFailedWaitsOrIsSettledIsNotEditedEvenFromItsCurrentVersion(String status) {
        Long receiptId = receiptOfAliceStoredAs(status);
        events.forget();

        assertThrows(ReceiptConflictException.class,
                () -> receiptService.editMyReceipt(receiptId, edit(0L, "Sửa", "1", LocalDate.of(2026, 1, 1))));

        Receipt receipt = stored(receiptId);
        assertEquals(status, receipt.getStatus());
        assertEquals(0L, receipt.getVersion());
        assertEquals("Bách Hóa Xanh", receipt.getVendorName());
        assertMoney("245000", receipt.getExtractedTotal());
        assertEquals(LocalDate.of(2026, 5, 27), receipt.getExtractedDate());
        assertEquals(List.of(), events.heard());
        verify(auditLogService, never()).record(entryOf(AuditAction.RECEIPT_UPDATED, aliceId));
    }

    @Test
    void anApprovalCarriesTheTotalTheOwnerCorrectedOverToTheTransactionAndTheWalletAndLeavesTheReadings() {
        Long transactionId = checkoutScan(FULL_SCAN, "200000");
        Long receiptId = receiptIdOf(transactionId);
        List<String> readingsBefore = attemptsInDatabase(receiptId);
        receiptService.editMyReceipt(receiptId, edit(0L, null, "250000", null));

        transactionService.approveReceipt(receiptId);

        Receipt receipt = stored(receiptId);
        assertEquals("APPROVED", receipt.getStatus());
        assertEquals(2L, receipt.getVersion());
        assertNothingOfTheMoneyMoved(transactionId, "250000", "750000"); // 200,000 was taken at the checkout, 50,000 more now
        assertEquals(readingsBefore, attemptsInDatabase(receiptId));
    }

    // ------------------------------------------------------------------------------------------- failure and retry

    @Test
    void aRetryWithAnAnswerThatHoldsATotalCompletesTheSameReceiptAsItsSecondReadingAndTouchesNothingElse() {
        Long transactionId = checkoutScan(UNREADABLE_TOTAL, "90000");
        Long receiptId = receiptIdOf(transactionId);
        String firstReading = attemptsInDatabase(receiptId).get(0);
        long transactionsBefore = transactionRepository.count();

        ReceiptResponseDto retried = receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, null));

        assertEquals(receiptId, retried.getId());
        assertEquals("COMPLETED_RAW", retried.getStatus());
        assertEquals(2L, retried.getVersion(), "one version for the claim, one for the reading it recorded");
        Receipt receipt = stored(receiptId);
        assertEquals("COMPLETED_RAW", receipt.getStatus());
        assertNull(receipt.getErrorCode());
        assertEquals("Highlands Coffee", receipt.getVendorName());
        assertMoney("95000", receipt.getExtractedTotal());
        assertEquals(LocalDate.of(2026, 9, 20), receipt.getExtractedDate());
        // The failed reading is still there, untouched, and the new one comes after it.
        List<String> attempts = attemptsInDatabase(receiptId);
        assertEquals(2, attempts.size());
        assertEquals(firstReading, attempts.get(0));
        assertEquals(List.of(UNREADABLE_TOTAL, NEW_SCAN), answersInDatabase(receiptId));
        assertEquals(List.of("FAILED", "COMPLETED"), attemptStatusesInDatabase(receiptId));
        // No second receipt, no second transaction, no move of the wallet, and the transaction is as the checkout left it.
        assertEquals(1, receiptRepository.count());
        assertEquals(transactionsBefore, transactionRepository.count());
        Transaction transaction = transactionInDatabase(transactionId);
        assertMoney("90000", transaction.getAmount());
        assertEquals(CLIENT_DATE, transaction.getTransactionDate());
        assertEquals(CLIENT_LOCATION, transaction.getLocation());
        assertMoney("910000", walletOf(aliceId));
        verify(auditLogService).record(entryOf(AuditAction.RECEIPT_RETRIED, aliceId));
    }

    @Test
    void everyRetryAddsAReadingAndNoReadingIsEverChangedOrLost() {
        Long receiptId = failedReceiptOfAlice();

        ReceiptResponseDto second = receiptService.retryMyReceipt(receiptId, retry(NOISE, null));
        List<String> afterTheSecond = attemptsInDatabase(receiptId);
        ReceiptResponseDto third = receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, null));

        assertEquals("FAILED", second.getStatus());
        assertEquals("AI_UNREADABLE", second.getErrorCode());
        assertEquals("COMPLETED_RAW", third.getStatus());
        assertEquals(4L, third.getVersion());
        List<String> attempts = attemptsInDatabase(receiptId);
        assertEquals(afterTheSecond, attempts.subList(0, 2), "the third reading changed neither of the first two");
        assertEquals(List.of(UNREADABLE_TOTAL, NOISE, NEW_SCAN), answersInDatabase(receiptId));
        assertEquals(List.of("FAILED", "FAILED", "COMPLETED"), attemptStatusesInDatabase(receiptId));
        assertEquals(1, receiptRepository.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSING", "COMPLETED_RAW", "SUCCESS", "EDITED", "WAITING_FOR_REVIEW", "APPROVED", "CANCELED"})
    void aReceiptThatIsNotFailedIsNotRetriedAndKeepsItsReadings(String status) {
        Long receiptId = receiptOfAliceStoredAs(status);
        events.forget();

        assertThrows(ReceiptConflictException.class, () -> receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, "attempt-1")));

        Receipt receipt = stored(receiptId);
        assertEquals(status, receipt.getStatus());
        assertEquals(0L, receipt.getVersion());
        assertEquals(List.of(FULL_SCAN), answersInDatabase(receiptId));
        verify(receiptRepository, never()).claimFailedForRetry(any(), any());
        verify(ocrService, never()).extract(NEW_SCAN);
        assertEquals(List.of(), events.heard());
    }

    @Test
    void aRepeatOfARetryWithTheSameKeyIsAnsweredWithTheSameReceiptAndNothingIsReadOrWrittenAgain() {
        Long transactionId = checkoutScan(UNREADABLE_TOTAL, "90000");
        Long receiptId = receiptIdOf(transactionId);
        events.forget();

        ReceiptResponseDto first = receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, "attempt-1"));
        ReceiptResponseDto repeated = receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, " attempt-1 "));

        assertEquals("COMPLETED_RAW", first.getStatus());
        assertEquals(first.getId(), repeated.getId());
        assertEquals(first.getStatus(), repeated.getStatus());
        assertEquals(first.getVersion(), repeated.getVersion());
        assertEquals(first.getVendorName(), repeated.getVendorName());
        assertMoney("95000", repeated.getExtractedTotal());
        assertEquals(first.getExtractedDate(), repeated.getExtractedDate());
        assertEquals(2L, stored(receiptId).getVersion(), "the repeat wrote nothing");
        assertEquals(List.of(UNREADABLE_TOTAL, NEW_SCAN), answersInDatabase(receiptId), "one reading for the key, not two");
        assertEquals("attempt-1", jdbc.queryForObject(
                "select client_id from receipt_processing_attempts where receipt_id = ? and attempt_number = 2", String.class, receiptId));
        verify(ocrService, times(1)).extract(NEW_SCAN);
        verify(receiptRepository, times(1)).claimFailedForRetry(any(), any());
        verify(auditLogService, times(1)).record(entryOf(AuditAction.RECEIPT_RETRIED, aliceId));
        assertEquals(List.of(StatisticsChangedEvent.of(StatisticsChange.RECEIPT_CONTENT, aliceId)), events.heard());
        assertEquals(1, receiptRepository.count());
        assertNothingOfTheMoneyMoved(transactionId, "90000", "910000");
    }

    @Test
    void aRepeatOfAnEarlierRetryIsRecognisedAfterALaterOneToo() {
        Long receiptId = failedReceiptOfAlice();
        receiptService.retryMyReceipt(receiptId, retry(NOISE, "attempt-1"));
        receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, "attempt-2"));

        ReceiptResponseDto lateRepeat = receiptService.retryMyReceipt(receiptId, retry(NOISE, "attempt-1"));

        assertEquals("COMPLETED_RAW", lateRepeat.getStatus(), "answered with the receipt as it is now");
        assertEquals(3, answersInDatabase(receiptId).size());
        assertEquals(4L, stored(receiptId).getVersion());
    }

    @Test
    void aRepeatOfARetryWithoutAKeyIsRefusedOnceTheReceiptIsReadAndChangesNothing() {
        Long receiptId = failedReceiptOfAlice();
        receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, null));

        assertThrows(ReceiptConflictException.class, () -> receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, null)));

        Receipt receipt = stored(receiptId);
        assertEquals("COMPLETED_RAW", receipt.getStatus());
        assertEquals(2L, receipt.getVersion());
        assertEquals(2, answersInDatabase(receiptId).size());
        verify(ocrService, times(1)).extract(NEW_SCAN);
    }

    @Test
    void aRetryThatFailsWhileTheReceiptIsWrittenLeavesItFailedAsItWasWithNoNewReading() {
        Long transactionId = checkoutScan(UNREADABLE_TOTAL, "90000");
        Long receiptId = receiptIdOf(transactionId);
        List<String> readingsBefore = attemptsInDatabase(receiptId);
        events.forget();
        doThrow(new IllegalStateException("database went away")).when(receiptRepository).saveAndFlush(any(Receipt.class));

        assertThrows(IllegalStateException.class, () -> receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, "attempt-1")));

        verify(receiptRepository).claimFailedForRetry(receiptId, 0L);
        verify(attemptRepository).save(any(ReceiptProcessingAttempt.class));
        Receipt receipt = stored(receiptId);
        assertEquals("FAILED", receipt.getStatus(), "the claim was rolled back with the rest: the receipt can be retried again");
        assertEquals("AI_NO_TOTAL", receipt.getErrorCode());
        assertEquals(0L, receipt.getVersion());
        assertEquals(readingsBefore, attemptsInDatabase(receiptId), "the reading that was being recorded is gone with it");
        assertNothingOfTheMoneyMoved(transactionId, "90000", "910000");
        assertEquals(List.of(), events.heard());
    }

    @Test
    void aRetryThatFailsWhileItsReadingIsRecordedIsRolledBackAndTheSameKeyIsThenRecordedForReal() {
        Long receiptId = failedReceiptOfAlice();
        doThrow(new IllegalStateException("attempts table went away"))
                .when(attemptRepository).save(any(ReceiptProcessingAttempt.class));

        assertThrows(IllegalStateException.class, () -> receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, "attempt-1")));

        Receipt receipt = stored(receiptId);
        assertEquals("FAILED", receipt.getStatus());
        assertEquals(0L, receipt.getVersion());
        assertEquals(List.of(UNREADABLE_TOTAL), answersInDatabase(receiptId));

        // The key was never recorded, so the same request sent again is not taken for a repeat of a success.
        reset(attemptRepository);
        ReceiptResponseDto again = receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, "attempt-1"));

        assertEquals("COMPLETED_RAW", again.getStatus());
        assertEquals(List.of(UNREADABLE_TOTAL, NEW_SCAN), answersInDatabase(receiptId));
    }

    @Test
    void anEditThatFailsAfterItWasWrittenIsRolledBackWithItsAuditEntry() {
        Long receiptId = readReceiptOfAlice();
        events.forget();
        doThrow(new IllegalStateException("audit log went away")).when(auditLogService).record(any(AuditEntry.class));

        assertThrows(IllegalStateException.class,
                () -> receiptService.editMyReceipt(receiptId, edit(0L, "Bách Hóa Xanh Q1", "250000", null)));

        Receipt receipt = stored(receiptId);
        assertEquals("COMPLETED_RAW", receipt.getStatus());
        assertEquals(0L, receipt.getVersion());
        assertEquals("Bách Hóa Xanh", receipt.getVendorName());
        assertMoney("245000", receipt.getExtractedTotal());
        assertEquals(List.of(), events.heard());
    }

    // ------------------------------------------------------------------------------------------------------ ownership

    @Test
    void anotherUserNeitherReadsNorEditsNorRetriesAReceiptOfAliceAndItStaysAsItWas() {
        Long readTransactionId = checkoutScan(FULL_SCAN, "200000");
        Long readId = receiptIdOf(readTransactionId);
        Long failedId = failedReceiptOfAlice();
        events.forget();
        signedInAs(bobId);

        assertThrows(TransactionAccessDeniedException.class, () -> receiptService.getMyReceipt(readId));
        assertThrows(TransactionAccessDeniedException.class, () -> receiptService.getMyReceiptOfTransaction(readTransactionId));
        assertThrows(TransactionAccessDeniedException.class,
                () -> receiptService.editMyReceipt(readId, edit(0L, "Của Bob", "1", null)));
        assertThrows(TransactionAccessDeniedException.class, () -> receiptService.retryMyReceipt(failedId, retry(NEW_SCAN, "bob-1")));
        assertThrows(TransactionAccessDeniedException.class, () -> receiptService.submitFeedback(failedId, new BigDecimal("1")));
        assertThrows(TransactionAccessDeniedException.class, () -> transactionService.cancelMyReceipt(readId));

        Receipt read = stored(readId);
        assertEquals("COMPLETED_RAW", read.getStatus());
        assertEquals("Bách Hóa Xanh", read.getVendorName());
        assertEquals(0L, read.getVersion());
        Receipt failed = stored(failedId);
        assertEquals("FAILED", failed.getStatus());
        assertEquals(0L, failed.getVersion());
        assertEquals(List.of(UNREADABLE_TOTAL), answersInDatabase(failedId));
        assertMoney("1000000", walletOf(bobId));
        assertEquals(List.of(), events.heard());
    }

    @Test
    void theOwnerRetriesAndEditsTheirOwnReceipt() {
        Long failedId = failedReceiptOfAlice();

        ReceiptResponseDto retried = receiptService.retryMyReceipt(failedId, retry(NEW_SCAN, "alice-1"));
        ReceiptResponseDto edited = receiptService.editMyReceipt(failedId, edit(retried.getVersion(), "Highlands Q3", null, null));

        assertEquals("COMPLETED_RAW", retried.getStatus());
        assertEquals("EDITED", edited.getStatus());
        assertEquals(List.of(UNREADABLE_TOTAL, NEW_SCAN), answersInDatabase(failedId));
    }

    @Test
    void theOwnerReadsAReceiptByItsIdAndThroughItsTransaction() {
        Long transactionId = checkoutScan(FULL_SCAN, "200000");
        Long receiptId = receiptIdOf(transactionId);

        ReceiptResponseDto byId = receiptService.getMyReceipt(receiptId);
        ReceiptResponseDto byTransaction = receiptService.getMyReceiptOfTransaction(transactionId);

        assertEquals(byId, byTransaction);
        assertEquals(receiptId, byId.getId());
        assertEquals(transactionId, byId.getTransactionId());
        assertEquals("COMPLETED_RAW", byId.getStatus());
        assertEquals(0L, byId.getVersion());
        assertNotNull(byId.getCreatedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED_RAW", "FAILED"})
    void aReceiptNoTransactionCarriesIsReadEditedAndRetriedByNobody(String status) {
        Receipt orphan = new Receipt();
        orphan.setStatus(status);
        Long orphanId = receiptRepository.saveAndFlush(orphan).getId();

        for (Long userId : List.of(aliceId, bobId)) {
            signedInAs(userId);
            assertThrows(TransactionAccessDeniedException.class, () -> receiptService.getMyReceipt(orphanId));
            assertThrows(TransactionAccessDeniedException.class,
                    () -> receiptService.editMyReceipt(orphanId, edit(0L, "Của tôi", null, null)));
            assertThrows(TransactionAccessDeniedException.class, () -> receiptService.retryMyReceipt(orphanId, retry(NEW_SCAN, null)));
        }

        Receipt stored = receiptRepository.findById(orphanId).orElseThrow();
        assertEquals(status, stored.getStatus());
        assertEquals(0L, stored.getVersion());
        assertNull(stored.getVendorName());
        assertEquals(List.of(), answersInDatabase(orphanId));
    }

    @Test
    void deletingATransactionTakesItsReceiptAndTheReceiptsReadingsWithIt() {
        Long transactionId = checkoutScan(UNREADABLE_TOTAL, "90000");
        Long receiptId = receiptIdOf(transactionId);
        receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, null));

        transactionService.deleteTransaction(transactionId);

        assertTrue(receiptRepository.findById(receiptId).isEmpty());
        assertEquals(List.of(), answersInDatabase(receiptId));
        assertMoney("1000000", walletOf(aliceId));
    }

    // ------------------------------------------------------------------------------- versions: one write at a time

    @Test
    void ofTwoEditsMadeFromTheSameVersionTheSecondIsRefusedAndTheFirstIsKept() {
        Long receiptId = readReceiptOfAlice();

        receiptService.editMyReceipt(receiptId, edit(0L, "Sửa lần một", "250000", null));
        assertThrows(ReceiptConflictException.class,
                () -> receiptService.editMyReceipt(receiptId, edit(0L, "Sửa lần hai", "999000", null)));

        Receipt receipt = stored(receiptId);
        assertEquals("Sửa lần một", receipt.getVendorName());
        assertMoney("250000", receipt.getExtractedTotal());
        assertEquals(1L, receipt.getVersion());
    }

    @Test
    void anEditPreparedBeforeAnApprovalIsRefusedAfterItAndWhatWasApprovedStands() {
        Long transactionId = checkoutScan(FULL_SCAN, "200000");
        Long receiptId = receiptIdOf(transactionId);

        transactionService.approveReceipt(receiptId); // the AI's 245,000 becomes the amount: 45,000 more leave the wallet

        // Made from the version of before the approval, and made again from the current one: an approved receipt is not edited.
        assertThrows(ReceiptConflictException.class, () -> receiptService.editMyReceipt(receiptId, edit(0L, null, "300000", null)));
        assertThrows(ReceiptConflictException.class, () -> receiptService.editMyReceipt(receiptId, edit(1L, null, "300000", null)));

        Receipt receipt = stored(receiptId);
        assertEquals("APPROVED", receipt.getStatus());
        assertEquals(1L, receipt.getVersion());
        assertMoney("245000", receipt.getExtractedTotal());
        assertNothingOfTheMoneyMoved(transactionId, "245000", "755000");
    }

    @Test
    void anEditPreparedBeforeARetryIsRefusedAfterItAndAFreshOneGoesThrough() {
        Long receiptId = failedReceiptOfAlice();
        // A FAILED receipt is not edited at all: its owner retries it or sends the amount for review.
        assertThrows(ReceiptConflictException.class, () -> receiptService.editMyReceipt(receiptId, edit(0L, "Sửa", null, null)));

        receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, null));

        assertThrows(ReceiptConflictException.class, () -> receiptService.editMyReceipt(receiptId, edit(0L, "Sửa", null, null)));
        assertEquals("EDITED", receiptService.editMyReceipt(receiptId, edit(2L, "Highlands Q3", null, null)).getStatus());
        assertEquals(List.of(UNREADABLE_TOTAL, NEW_SCAN), answersInDatabase(receiptId));
    }

    // --------------------------------------------------------------------------------- rows that predate the lifecycle

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "success"})
    void aReadReceiptThatPredatesTheLifecycleIsCompletedRawToTheLifecycleAndSuccessToTheApp(String legacyStatus) {
        Long transactionId = checkoutScan(FULL_SCAN, "200000");
        Long receiptId = receiptIdOf(transactionId);
        predatesTheAttempts(receiptId, legacyStatus, FULL_SCAN, null);

        assertEquals("COMPLETED_RAW", receiptService.getMyReceipt(receiptId).getStatus());
        assertEquals("SUCCESS", transactionService.getMyTransactions().get(0).getReceiptStatus());

        ReceiptResponseDto edited = receiptService.editMyReceipt(receiptId, edit(0L, null, "250000", null));
        assertEquals("EDITED", edited.getStatus());
        assertEquals("EDITED", stored(receiptId).getStatus());
        assertEquals("SUCCESS", transactionService.getMyTransactions().get(0).getReceiptStatus());

        transactionService.approveReceipt(receiptId);
        assertEquals("APPROVED", stored(receiptId).getStatus());
        assertEquals("SUCCESS", transactionService.getMyTransactions().get(0).getReceiptStatus());
        assertNothingOfTheMoneyMoved(transactionId, "250000", "750000");
        // Neither the edit nor the approval records or imports a reading; the legacy copy of the answer stays where it was.
        assertEquals(List.of(), answersInDatabase(receiptId));
        assertEquals(FULL_SCAN, jdbc.queryForObject("select raw_receipt_data from transactions where id = ?", String.class, transactionId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "failed"})
    void aFailedReceiptThatPredatesTheAttemptsKeepsItsAnswerAsTheFirstReadingWhenItIsRetried(String legacyStatus) {
        Long transactionId = checkoutScan(UNREADABLE_TOTAL, "90000");
        Long receiptId = receiptIdOf(transactionId);
        predatesTheAttempts(receiptId, legacyStatus, UNREADABLE_TOTAL, null);

        assertEquals("FAILED", receiptService.getMyReceipt(receiptId).getStatus());
        assertNull(receiptService.getMyReceipt(receiptId).getErrorMessage(), "an old row never recorded why it failed");

        assertEquals("COMPLETED_RAW", receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, null)).getStatus());

        assertEquals(List.of(UNREADABLE_TOTAL, NEW_SCAN), answersInDatabase(receiptId), "the old answer is the first reading");
        assertEquals(List.of("LEGACY", "COMPLETED"), attemptStatusesInDatabase(receiptId));
        assertEquals(UNREADABLE_TOTAL, jdbc.queryForObject("select raw_receipt_data from transactions where id = ?", String.class,
                transactionId), "and the legacy copy is not removed");
    }

    @Test
    void aFailedReceiptOfTheFirstLifecycleBuildKeepsBothOfItsOldAnswersWhenItIsRetried() {
        Long receiptId = receiptIdOf(checkoutScan(UNREADABLE_TOTAL, "90000"));
        predatesTheAttempts(receiptId, "FAILED", UNREADABLE_TOTAL, PHASE_ONE_ANSWER);

        receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, "attempt-1"));

        assertEquals(List.of(UNREADABLE_TOTAL, PHASE_ONE_ANSWER, NEW_SCAN), answersInDatabase(receiptId));
        assertEquals(List.of("LEGACY", "LEGACY", "COMPLETED"), attemptStatusesInDatabase(receiptId));
    }

    @Test
    void aPendingReceiptThatPredatesTheLifecycleIsStillCanceledByItsOwnerAndIsFinalAfterwards() {
        Long receiptId = receiptOfAliceStoredAs("pending");
        events.forget();

        transactionService.cancelMyReceipt(receiptId);

        assertEquals("CANCELED", stored(receiptId).getStatus());
        assertEquals(List.of(StatisticsChangedEvent.of(StatisticsChange.RECEIPT_STATUS, aliceId)), events.heard());
        Long version = stored(receiptId).getVersion();
        assertThrows(ReceiptConflictException.class, () -> receiptService.editMyReceipt(receiptId, edit(version, "Sửa", null, null)));
        assertThrows(ReceiptConflictException.class, () -> receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, null)));
        assertThrows(IllegalStateException.class, () -> receiptService.submitFeedback(receiptId, new BigDecimal("1")));
        assertThrows(IllegalStateException.class, () -> receiptService.rejectReceipt(receiptId));
        assertThrows(IllegalStateException.class, () -> transactionService.approveReceipt(receiptId));
        assertThrows(IllegalStateException.class, () -> transactionService.cancelMyReceipt(receiptId));
        assertEquals("CANCELED", stored(receiptId).getStatus());
    }

    // ----------------------------------------------------------------- feedback, approval and rejection, as before

    @Test
    void theOwnerStillSendsTheAmountOfAFailedReceiptAndAnAdministratorApprovesIt() {
        Long transactionId = checkoutScan(UNREADABLE_TOTAL, "90000");
        Long receiptId = receiptIdOf(transactionId);
        List<String> readingsBefore = attemptsInDatabase(receiptId);

        receiptService.submitFeedback(receiptId, new BigDecimal("150000"));

        Receipt waiting = stored(receiptId);
        assertEquals("WAITING_FOR_REVIEW", waiting.getStatus());
        assertNull(waiting.getErrorCode());
        assertMoney("150000", waiting.getExtractedTotal());
        assertNothingOfTheMoneyMoved(transactionId, "90000", "910000");

        transactionService.approveReceipt(receiptId);

        assertEquals("APPROVED", stored(receiptId).getStatus());
        assertNothingOfTheMoneyMoved(transactionId, "150000", "850000");
        assertEquals(readingsBefore, attemptsInDatabase(receiptId), "feedback and approval leave the readings alone");
    }

    @Test
    void anAdministratorRejectsAReceiptThatWasReadAndItsOwnerGivesItANewReading() {
        Long transactionId = checkoutScan(FULL_SCAN, "200000");
        Long receiptId = receiptIdOf(transactionId);

        receiptService.rejectReceipt(receiptId);

        Receipt rejected = stored(receiptId);
        assertEquals("FAILED", rejected.getStatus());
        assertEquals("REJECTED_BY_ADMIN", rejected.getErrorCode());
        assertMoney("245000", rejected.getExtractedTotal());
        assertEquals(List.of(FULL_SCAN), answersInDatabase(receiptId));
        assertEquals(List.of("COMPLETED"), attemptStatusesInDatabase(receiptId), "the rejection is not a reading");
        assertNothingOfTheMoneyMoved(transactionId, "200000", "800000");

        ReceiptResponseDto retried = receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, null));

        assertEquals("COMPLETED_RAW", retried.getStatus());
        assertNull(retried.getErrorCode());
        assertMoney("95000", retried.getExtractedTotal());
        assertEquals(List.of(FULL_SCAN, NEW_SCAN), answersInDatabase(receiptId));
        assertNothingOfTheMoneyMoved(transactionId, "200000", "800000");
    }

    // ------------------------------------------------------------------ the statistics cache: reported after commit

    @Test
    void everyChangeOfAReceiptIsReportedOnceAfterItsCommitAndARefusalReportsNothing() {
        Long receiptId = readReceiptOfAlice();
        assertEquals(List.of(StatisticsChangedEvent.of(StatisticsChange.TRANSACTION_TOTALS, aliceId)), events.heard());
        events.forget();

        receiptService.editMyReceipt(receiptId, edit(0L, "Bách Hóa Xanh Q1", null, null));
        assertEquals(List.of(StatisticsChangedEvent.of(StatisticsChange.RECEIPT_CONTENT, aliceId)), events.heard());
        events.forget();

        assertThrows(ReceiptConflictException.class, () -> receiptService.editMyReceipt(receiptId, edit(0L, "Cũ", null, null)));
        assertThrows(ReceiptConflictException.class, () -> receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, null)));
        assertEquals(List.of(), events.heard());

        receiptService.rejectReceipt(receiptId);
        assertEquals(List.of(StatisticsChangedEvent.of(StatisticsChange.RECEIPT_STATUS, aliceId)), events.heard());
        events.forget();

        receiptService.rejectReceipt(receiptId); // FAILED already: nothing changes, nothing is reported
        assertEquals(List.of(), events.heard());

        receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, "attempt-1"));
        assertEquals(List.of(StatisticsChangedEvent.of(StatisticsChange.RECEIPT_CONTENT, aliceId)), events.heard());
        events.forget();

        receiptService.retryMyReceipt(receiptId, retry(NEW_SCAN, "attempt-1")); // a repeat: nothing changes, nothing is reported
        assertEquals(List.of(), events.heard());

        transactionService.approveReceipt(receiptId); // 95,000 instead of the 200,000 of the checkout: the totals moved
        assertEquals(List.of(StatisticsChangedEvent.of(StatisticsChange.TRANSACTION_TOTALS, aliceId)), events.heard());
    }
}
