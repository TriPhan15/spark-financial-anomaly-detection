package vn.edu.huit.finance.core.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.huit.finance.core.Dto.TransactionRequestDto;
import vn.edu.huit.finance.core.Dto.TransactionUpdateRequestDto;
import vn.edu.huit.finance.core.Entity.Category;
import vn.edu.huit.finance.core.Entity.Receipt;
import vn.edu.huit.finance.core.Entity.ReceiptProcessingAttempt;
import vn.edu.huit.finance.core.Entity.Transaction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.InsufficientBalanceException;
import vn.edu.huit.finance.core.Exception.InvalidCategoryException;
import vn.edu.huit.finance.core.Exception.TransactionAccessDeniedException;
import vn.edu.huit.finance.core.Repository.CategoryRepository;
import vn.edu.huit.finance.core.Repository.ReceiptProcessingAttemptRepository;
import vn.edu.huit.finance.core.Repository.ReceiptRepository;
import vn.edu.huit.finance.core.Repository.TransactionRepository;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The money stays right when things go wrong. Nothing here is wrapped in a test transaction (that would hide the very
 * commits and rollbacks under test): the service runs through its real @Transactional proxy on H2, and every check reads
 * the database again afterwards.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TransactionService.class, ReceiptProcessingService.class, OcrService.class, UserStatisticsService.class})
class TransactionServiceRollbackTest {

    private static final BigDecimal WALLET = new BigDecimal("1000000.00");
    private static final long PROBE_MILLIS = 250;
    private static final long TIMEOUT_SECONDS = 10;

    @Autowired private TransactionService service;
    @Autowired private ReceiptProcessingService receiptService;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ReceiptProcessingAttemptRepository attemptRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;
    @SpyBean private TransactionRepository transactionRepository;
    @SpyBean private ReceiptRepository receiptRepository;
    @MockBean private CurrentUserProvider currentUserProvider;
    @MockBean private AuditLogService auditLogService;
    // The alert has its own end-to-end test on H2 (BudgetAlertRollbackTest); here it would only add statements.
    @MockBean private BudgetAlertService budgetAlertService;

    private Long aliceId;
    private Category food;
    private Transaction expense;

    @BeforeEach
    void setUp() {
        User alice = new User();
        alice.setUsername("alice");
        alice.setPassword("hash");
        alice.setEmail("alice@example.com");
        alice.setPhone("0900000001");
        alice.setWalletBalance(WALLET);
        aliceId = userRepository.saveAndFlush(alice).getId();

        food = new Category();
        food.setName("Ăn uống");
        food.setType("EXPENSE");
        food = categoryRepository.saveAndFlush(food);

        // An expense of 100,000; the wallet is what it left, so the test starts from a consistent state.
        expense = persistExpense("100000.00");
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

    private Transaction persistExpense(String amount) {
        Transaction transaction = new Transaction();
        transaction.setUser(userRepository.findById(aliceId).orElseThrow());
        transaction.setCategory(food);
        transaction.setCategoryName(food.getName());
        transaction.setAmount(new BigDecimal(amount));
        transaction.setExpense(true);
        transaction.setNote("Bún bò");
        transaction.setPaymentMethod("Ví chính");
        transaction.setTransactionDate(LocalDateTime.of(2026, 9, 1, 12, 0));
        return transactionRepository.saveAndFlush(transaction);
    }

    private static TransactionUpdateRequestDto amount(String value) {
        TransactionUpdateRequestDto request = new TransactionUpdateRequestDto();
        request.setAmount(new BigDecimal(value));
        return request;
    }

    private BigDecimal walletInDatabase() {
        return userRepository.findById(aliceId).orElseThrow().getWalletBalance();
    }

    private Transaction transactionInDatabase(Long id) {
        return transactionRepository.findDetailedById(id).orElseThrow();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private long walletRowsWritten() {
        return statistics().getEntityStatistics(User.class.getName()).getUpdateCount();
    }

    // --------------------------------------------------------------------------------------------------- the good path

    @Test
    void aSuccessfulEditCommitsTheWalletAndTheTransactionTogether() {
        service.updateTransaction(expense.getId(), amount("150000"));

        assertMoney("950000", walletInDatabase());
        assertMoney("150000", transactionInDatabase(expense.getId()).getAmount());
    }

    @Test
    void aSuccessfulDeleteCommitsTheRefundAndTheRemovalTogether() {
        service.deleteTransaction(expense.getId());

        assertMoney("1100000", walletInDatabase());
        assertTrue(transactionRepository.findById(expense.getId()).isEmpty(), "the transaction must really be gone");
    }

    @Test
    void lockingTheWalletDoesNotLoadTheOtherTransactionsOfTheUser() {
        for (int i = 0; i < 5; i++) {
            persistExpense("1000.00");
        }
        statistics().clear();

        service.updateTransaction(expense.getId(), amount("150000"));

        // The user, its transaction, the lock, the two writes and the transaction: a handful, not one per transaction.
        assertTrue(statistics().getPrepareStatementCount() <= 12,
                "statements: " + statistics().getPrepareStatementCount());
        assertEquals(1, statistics().getEntityStatistics(Transaction.class.getName()).getUpdateCount());
    }

    // ------------------------------------------------------------------------------------------------ rollback: update

    @Test
    void whenSavingTheTransactionFailsTheWalletThatWasAlreadyWrittenIsRolledBack() {
        doThrow(new IllegalStateException("database went away")).when(transactionRepository).save(any(Transaction.class));
        statistics().clear();

        assertThrows(IllegalStateException.class, () -> service.updateTransaction(expense.getId(), amount("150000")));

        assertTrue(walletRowsWritten() >= 1,
                "the wallet row was really written before the failure, so it is the rollback that undoes it");
        assertMoney("1000000", walletInDatabase());
        assertMoney("100000", transactionInDatabase(expense.getId()).getAmount());
    }

    @Test
    void aCategoryCreatedByTheFailedEditIsRolledBackWithIt() {
        doThrow(new IllegalStateException("database went away")).when(transactionRepository).save(any(Transaction.class));
        TransactionUpdateRequestDto request = amount("150000");
        request.setCategoryName("Freelance");

        assertThrows(IllegalStateException.class, () -> service.updateTransaction(expense.getId(), request));

        assertTrue(categoryRepository.findByName("Freelance").isEmpty(), "the new category must not survive the failed edit");
        assertMoney("1000000", walletInDatabase());
    }

    @Test
    void aTypeChangeThatFailsLeavesBothTheTypeAndTheWalletAsTheyWere() {
        doThrow(new IllegalStateException("database went away")).when(transactionRepository).save(any(Transaction.class));
        TransactionUpdateRequestDto request = new TransactionUpdateRequestDto();
        request.setExpense(false);

        assertThrows(IllegalStateException.class, () -> service.updateTransaction(expense.getId(), request));

        assertMoney("1000000", walletInDatabase());
        assertTrue(transactionInDatabase(expense.getId()).isExpense());
    }

    @Test
    void anEditTheWalletCannotAffordChangesNothing() {
        User alice = userRepository.findById(aliceId).orElseThrow();
        alice.setWalletBalance(new BigDecimal("20000.00"));
        userRepository.saveAndFlush(alice);

        assertThrows(InsufficientBalanceException.class, () -> service.updateTransaction(expense.getId(), amount("150000")));

        assertMoney("20000", walletInDatabase());
        assertMoney("100000", transactionInDatabase(expense.getId()).getAmount());
    }

    @Test
    void anUnknownCategoryChangesNothingEvenWhenTheAmountWouldHave() {
        TransactionUpdateRequestDto request = amount("150000");
        request.setCategoryId(-99L);

        assertThrows(InvalidCategoryException.class, () -> service.updateTransaction(expense.getId(), request));

        assertMoney("1000000", walletInDatabase());
        assertMoney("100000", transactionInDatabase(expense.getId()).getAmount());
    }

    // ------------------------------------------------------------------------------------------------ rollback: delete

    @Test
    void whenDeletingFailsTheRefundIsRolledBack() {
        doThrow(new IllegalStateException("database went away")).when(transactionRepository).delete(any(Transaction.class));
        statistics().clear();

        assertThrows(IllegalStateException.class, () -> service.deleteTransaction(expense.getId()));

        assertTrue(walletRowsWritten() >= 1, "the refund was really written before the failure");
        assertMoney("1000000", walletInDatabase());
        assertTrue(transactionRepository.findById(expense.getId()).isPresent());
    }

    // ------------------------------------------------------------------------ scanned checkout: wallet, transaction, receipt

    private static final LocalDateTime CLIENT_DATE = LocalDateTime.of(2026, 9, 20, 9, 0);
    private static final String FULL_SCAN = "{\"seller\": {\"seller_name\": \"Bách Hóa Xanh\", \"address\": \"12 Nguyễn Trãi, Quận 1\","
            + " \"timestamp\": \"27/05/2026 10:30\", \"total_cost\": \"245.000\", \"total_cost_number\": 245000}}";
    private static final String UNREADABLE_TOTAL = "{\"seller\": {\"seller_name\": \"Highlands\"}}";

    private static TransactionRequestDto scan(String amount, LocalDateTime date, String rawReceiptData) {
        TransactionRequestDto request = new TransactionRequestDto();
        request.setAmount(new BigDecimal(amount));
        request.setLocation("Hóa đơn quét");
        request.setPaymentMethod("WALLET");
        request.setTransactionDate(date);
        request.setCategoryName("Ăn uống");
        request.setExpense(true);
        request.setRawReceiptData(rawReceiptData);
        request.setImageUrl("https://img.example.com/1.jpg");
        return request;
    }

    /** The checkout of the mobile app: the amount the user confirmed plus the JSON the AI produced; returns the transaction id. */
    private Long checkoutScan(String rawReceiptData, String amount) {
        return (Long) service.createTransaction(scan(amount, CLIENT_DATE, rawReceiptData), aliceId).get("transactionId");
    }

    private Long failedReceiptOfAlice() {
        Receipt receipt = transactionInDatabase(checkoutScan(UNREADABLE_TOTAL, "90000")).getReceipt();
        assertEquals("FAILED", receipt.getStatus(), "an AI that read no total leaves a FAILED receipt");
        return receipt.getId();
    }

    private User persistBob() {
        User bob = new User();
        bob.setUsername("bob");
        bob.setPassword("hash");
        bob.setEmail("bob@example.com");
        bob.setPhone("0900000002");
        bob.setWalletBalance(WALLET);
        return userRepository.saveAndFlush(bob);
    }

    @Test
    void aScannedCheckoutCommitsTheWalletTheTransactionAndTheLinkedReceiptTogether() {
        Long transactionId = checkoutScan(FULL_SCAN, "200000");

        Transaction saved = transactionInDatabase(transactionId);
        Receipt receipt = saved.getReceipt();
        assertNotNull(receipt, "the transaction must carry its receipt");
        assertEquals("COMPLETED_RAW", receipt.getStatus());
        List<ReceiptProcessingAttempt> readings = attemptRepository.findByReceiptIdOrderByAttemptNumberAsc(receipt.getId());
        assertEquals(1, readings.size(), "the reading of the checkout is committed with the receipt");
        assertEquals(FULL_SCAN, readings.get(0).getAiRawData(), "the receipt keeps what the AI answered, in its first attempt");
        assertEquals("COMPLETED", readings.get(0).getStatus());
        assertEquals("Bách Hóa Xanh", receipt.getVendorName());
        assertMoney("245000", receipt.getExtractedTotal());
        assertEquals(LocalDateTime.of(2026, 5, 27, 10, 30), saved.getTransactionDate());
        assertEquals("12 Nguyễn Trãi, Quận 1", saved.getLocation());
        assertMoney("800000", walletInDatabase());
        assertEquals(transactionId, receiptRepository.findDetailedById(receipt.getId()).orElseThrow().getTransaction().getId(),
                "the receipt must point back at the transaction that carries it");
        assertEquals(1, receiptRepository.count());
    }

    @Test
    void whenSavingTheScannedTransactionFailsNeitherTheReceiptNorTheWalletSurvives() {
        doThrow(new IllegalStateException("database went away")).when(transactionRepository).save(any(Transaction.class));

        assertThrows(IllegalStateException.class, () -> checkoutScan(FULL_SCAN, "200000"));

        assertMoney("1000000", walletInDatabase());
        assertEquals(0, receiptRepository.count(), "no receipt without its transaction");
        assertEquals(0, attemptRepository.count(), "and no reading without its receipt");
        assertEquals(1, transactionRepository.count(), "only the transaction of the fixture is left");
    }

    @Test
    void aDatabaseFailureOnTheTransactionRowRollsBackTheReceiptAndTheWalletToo() {
        // No date from the client and none on the receipt: the transaction row is rejected by its NOT NULL column.
        TransactionRequestDto request = scan("200000", null, "{\"seller\": {\"seller_name\": \"Highlands\", \"total_cost_number\": 90000}}");

        assertThrows(RuntimeException.class, () -> service.createTransaction(request, aliceId));

        assertMoney("1000000", walletInDatabase());
        assertEquals(0, receiptRepository.count(), "the receipt written before the failure must be rolled back with it");
        assertEquals(0, attemptRepository.count(), "and so must the reading inserted with it");
        assertEquals(1, transactionRepository.count());
    }

    @Test
    void aWalletThatCannotAffordTheScannedPurchaseWritesNoReceipt() {
        User alice = userRepository.findById(aliceId).orElseThrow();
        alice.setWalletBalance(new BigDecimal("20000.00"));
        userRepository.saveAndFlush(alice);

        assertThrows(RuntimeException.class, () -> checkoutScan(FULL_SCAN, "200000"));

        assertMoney("20000", walletInDatabase());
        assertEquals(0, receiptRepository.count());
        assertEquals(1, transactionRepository.count());
    }

    // ---------------------------------------------------------------------------- feedback and approval of a receipt

    @Test
    void theOwnerSendsTheAmountOfAFailedReceiptAndItIsCommitted() {
        Long receiptId = failedReceiptOfAlice();

        receiptService.submitFeedback(receiptId, new BigDecimal("90000"));

        Receipt stored = receiptRepository.findById(receiptId).orElseThrow();
        assertEquals("WAITING_FOR_REVIEW", stored.getStatus());
        assertMoney("90000", stored.getExtractedTotal());
    }

    @Test
    void anotherUserGets403ForTheReceiptOfAliceAndItIsUntouched() {
        Long receiptId = failedReceiptOfAlice();
        doReturn(persistBob()).when(currentUserProvider).getCurrentUser();

        assertThrows(TransactionAccessDeniedException.class, () -> receiptService.submitFeedback(receiptId, new BigDecimal("1")));

        Receipt stored = receiptRepository.findById(receiptId).orElseThrow();
        assertEquals("FAILED", stored.getStatus());
        assertNull(stored.getExtractedTotal());
    }

    @Test
    void whenSavingTheFeedbackFailsTheReceiptStaysAsItWas() {
        Long receiptId = failedReceiptOfAlice();
        doThrow(new IllegalStateException("database went away")).when(receiptRepository).save(any(Receipt.class));

        assertThrows(IllegalStateException.class, () -> receiptService.submitFeedback(receiptId, new BigDecimal("90000")));

        Receipt stored = receiptRepository.findById(receiptId).orElseThrow();
        assertEquals("FAILED", stored.getStatus());
        assertNull(stored.getExtractedTotal());
    }

    private Long transactionIdOf(Long receiptId) {
        return receiptRepository.findDetailedById(receiptId).orElseThrow().getTransaction().getId();
    }

    /** The whole road of a receipt the AI could not read: FAILED, the owner sends 150,000 for a checkout of 90,000, WAITING_FOR_REVIEW. */
    private Long receiptOfAliceWaitingForReview() {
        Long receiptId = failedReceiptOfAlice();
        receiptService.submitFeedback(receiptId, new BigDecimal("150000"));
        return receiptId;
    }

    @Test
    void approvingAScannedReceiptWritesWhatTheAiReadIntoTheTransactionAndTheWalletFollows() {
        Long transactionId = checkoutScan(FULL_SCAN, "200000"); // the user typed 200,000, the AI read 245,000
        Long receiptId = transactionInDatabase(transactionId).getReceipt().getId();
        assertMoney("800000", walletInDatabase());

        service.approveReceipt(receiptId);

        assertMoney("245000", transactionInDatabase(transactionId).getAmount());
        assertMoney("755000", walletInDatabase());
        assertEquals("APPROVED", receiptRepository.findById(receiptId).orElseThrow().getStatus());
    }

    @Test
    void theAmountTheOwnerCorrectedIsWhatTheApprovalWritesIntoTheTransactionAndTheWallet() {
        Long receiptId = receiptOfAliceWaitingForReview();
        Long transactionId = transactionIdOf(receiptId);
        assertMoney("90000", transactionInDatabase(transactionId).getAmount());
        assertMoney("910000", walletInDatabase());

        service.approveReceipt(receiptId);

        assertMoney("150000", transactionInDatabase(transactionId).getAmount());
        assertMoney("850000", walletInDatabase());
        assertEquals("APPROVED", receiptRepository.findById(receiptId).orElseThrow().getStatus());
    }

    @Test
    void approvingTheSameReceiptTwiceMovesTheWalletOnce() {
        Long receiptId = receiptOfAliceWaitingForReview();

        service.approveReceipt(receiptId);
        service.approveReceipt(receiptId);

        assertMoney("850000", walletInDatabase());
        assertMoney("150000", transactionInDatabase(transactionIdOf(receiptId)).getAmount());
    }

    @Test
    void aWalletThatCannotAffordTheApprovedTotalKeepsTheAmountTheWalletAndTheStatusAsTheyWere() {
        Long receiptId = receiptOfAliceWaitingForReview();
        Long transactionId = transactionIdOf(receiptId);
        User alice = userRepository.findById(aliceId).orElseThrow();
        alice.setWalletBalance(new BigDecimal("20000.00")); // 60,000 more are due
        userRepository.saveAndFlush(alice);

        assertThrows(InsufficientBalanceException.class, () -> service.approveReceipt(receiptId));

        assertMoney("20000", walletInDatabase());
        assertMoney("90000", transactionInDatabase(transactionId).getAmount());
        assertEquals("WAITING_FOR_REVIEW", receiptRepository.findById(receiptId).orElseThrow().getStatus());
    }

    @Test
    void whenSavingTheReceiptFailsTheWalletAndTheAmountThatWereAlreadyWrittenAreRolledBack() {
        Long receiptId = receiptOfAliceWaitingForReview();
        Long transactionId = transactionIdOf(receiptId);
        doThrow(new IllegalStateException("database went away")).when(receiptRepository).save(any(Receipt.class));
        statistics().clear();

        assertThrows(IllegalStateException.class, () -> service.approveReceipt(receiptId));

        assertTrue(walletRowsWritten() >= 1,
                "the wallet row was really written before the failure, so it is the rollback that undoes it");
        assertMoney("910000", walletInDatabase());
        assertMoney("90000", transactionInDatabase(transactionId).getAmount());
        assertEquals("WAITING_FOR_REVIEW", receiptRepository.findById(receiptId).orElseThrow().getStatus());
    }

    @Test
    void whenSavingTheApprovedTransactionFailsTheWalletThatWasAlreadyWrittenIsRolledBack() {
        Long receiptId = receiptOfAliceWaitingForReview();
        Long transactionId = transactionIdOf(receiptId);
        doThrow(new IllegalStateException("database went away")).when(transactionRepository).save(any(Transaction.class));
        statistics().clear();

        assertThrows(IllegalStateException.class, () -> service.approveReceipt(receiptId));

        assertTrue(walletRowsWritten() >= 1, "the wallet row was really written before the failure");
        assertMoney("910000", walletInDatabase());
        assertMoney("90000", transactionInDatabase(transactionId).getAmount());
        assertEquals("WAITING_FOR_REVIEW", receiptRepository.findById(receiptId).orElseThrow().getStatus());
    }

    @Test
    void aReceiptNobodyGaveATotalCannotBeApprovedAndNothingChanges() {
        Long receiptId = failedReceiptOfAlice(); // the AI read nothing and the owner has not answered
        Long transactionId = transactionIdOf(receiptId);

        assertThrows(IllegalStateException.class, () -> service.approveReceipt(receiptId));

        assertEquals("FAILED", receiptRepository.findById(receiptId).orElseThrow().getStatus());
        assertMoney("90000", transactionInDatabase(transactionId).getAmount());
        assertMoney("910000", walletInDatabase());
    }

    @Test
    void aReceiptWithoutATransactionIsNotVerifiedAndStaysAsItWas() {
        Receipt orphan = new Receipt();
        orphan.setStatus("WAITING_FOR_REVIEW");
        Long orphanId = receiptRepository.saveAndFlush(orphan).getId();

        assertThrows(IllegalStateException.class, () -> service.approveReceipt(orphanId));

        assertEquals("WAITING_FOR_REVIEW", receiptRepository.findById(orphanId).orElseThrow().getStatus());
    }

    // ----------------------------------------------------------------------------- checkout with a client id (idempotency)

    private static TransactionRequestDto checkoutWithClientId(String amount, String clientId) {
        TransactionRequestDto request = new TransactionRequestDto();
        request.setAmount(new BigDecimal(amount));
        request.setLocation("Quán A");
        request.setPaymentMethod("Ví chính");
        request.setTransactionDate(CLIENT_DATE);
        request.setCategoryName("Ăn uống");
        request.setExpense(true);
        request.setClientId(clientId);
        return request;
    }

    private Long checkedOut(TransactionRequestDto request, Long userId) {
        return (Long) service.createTransaction(request, userId).get("transactionId");
    }

    private void setWallet(String balance) {
        User alice = userRepository.findById(aliceId).orElseThrow();
        alice.setWalletBalance(new BigDecimal(balance));
        userRepository.saveAndFlush(alice);
    }

    @Test
    void sendingTheSameCheckoutTwiceCreatesOneTransactionAndMovesTheWalletOnce() {
        Long first = checkedOut(checkoutWithClientId("150000", "attempt-1"), aliceId);
        Map<String, Object> second = service.createTransaction(checkoutWithClientId("150000", "attempt-1"), aliceId);

        assertEquals(first, second.get("transactionId"));
        assertEquals(2, transactionRepository.count(), "the transaction of the fixture plus the one checkout");
        assertMoney("850000", walletInDatabase());
        assertMoney("850000", (BigDecimal) second.get("remainingBalance"));
        assertEquals("attempt-1", transactionInDatabase(first).getClientId());
        verify(budgetAlertService, times(1)).notifyAfterCreate(any());
    }

    @Test
    void theSameClientIdSentByTwoUsersIsTwoDifferentCheckouts() {
        Long bobId = persistBob().getId();

        Long alices = checkedOut(checkoutWithClientId("150000", "shared-id"), aliceId);
        Long bobs = checkedOut(checkoutWithClientId("150000", "shared-id"), bobId);

        assertNotEquals(alices, bobs);
        assertMoney("850000", walletInDatabase());
        assertMoney("850000", userRepository.findById(bobId).orElseThrow().getWalletBalance());
        assertEquals("shared-id", transactionInDatabase(bobs).getClientId());
    }

    @Test
    void checkoutsWithoutAClientIdAreNeverTakenForRepeats() {
        checkedOut(checkoutWithClientId("150000", null), aliceId);
        checkedOut(checkoutWithClientId("150000", null), aliceId);
        checkedOut(checkoutWithClientId("150000", "   "), aliceId);

        assertEquals(4, transactionRepository.count(), "the transaction of the fixture plus three checkouts");
        assertMoney("550000", walletInDatabase());
    }

    @Test
    void aCheckoutThatFailedDoesNotUseUpItsClientId() {
        setWallet("20000.00");

        assertThrows(RuntimeException.class, () -> checkedOut(checkoutWithClientId("150000", "attempt-2"), aliceId));
        assertEquals(1, transactionRepository.count(), "the failed checkout left nothing behind");

        setWallet("1000000.00");
        Long retried = checkedOut(checkoutWithClientId("150000", "attempt-2"), aliceId);

        assertEquals("attempt-2", transactionInDatabase(retried).getClientId());
        assertMoney("850000", walletInDatabase());
    }

    @Test
    void aRepeatedScannedCheckoutKeepsOneReceiptAndMovesTheWalletOnce() {
        TransactionRequestDto scanned = scan("200000", CLIENT_DATE, FULL_SCAN);
        scanned.setClientId("scan-1");
        TransactionRequestDto again = scan("200000", CLIENT_DATE, FULL_SCAN);
        again.setClientId("scan-1");

        Long first = checkedOut(scanned, aliceId);
        Long second = checkedOut(again, aliceId);

        assertEquals(first, second);
        assertEquals(1, receiptRepository.count());
        assertMoney("800000", walletInDatabase());
    }

    @Test
    void ifTheLookupMissedTheUniqueKeyStillStopsTheDuplicateAndTheSecondChargeIsRolledBack() {
        Long first = checkedOut(checkoutWithClientId("150000", "attempt-3"), aliceId);
        doReturn(Optional.empty()).when(transactionRepository).findByUserIdAndClientId(any(), any());

        assertThrows(DataIntegrityViolationException.class,
                () -> service.createTransaction(checkoutWithClientId("150000", "attempt-3"), aliceId));

        assertEquals(2, transactionRepository.count(), "the transaction of the fixture and the first checkout only");
        assertMoney("850000", walletInDatabase());
        assertEquals("attempt-3", transactionInDatabase(first).getClientId());
    }

    // ------------------------------------------------------------------------------------------------------ concurrency

    @Test
    void twoCheckoutsWithTheSameClientIdThatArriveTogetherCreateOneTransaction() throws Exception {
        CountDownLatch firstIsInside = new CountDownLatch(1);
        CountDownLatch firstMayCommit = new CountDownLatch(1);
        // The first checkout is held inside its transaction: wallet locked, transaction row written, nothing committed.
        doAnswer(invocation -> {
            firstIsInside.countDown();
            awaitQuietly(firstMayCommit);
            return null;
        }).when(budgetAlertService).notifyAfterCreate(any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Map<String, Object> first;
        Map<String, Object> second;
        try {
            Future<Map<String, Object>> firstCall = pool.submit(
                    () -> service.createTransaction(checkoutWithClientId("150000", "double-tap"), aliceId));
            assertTrue(firstIsInside.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            // The double tap: the same checkout again while the first one has not committed.
            Future<Map<String, Object>> secondCall = pool.submit(
                    () -> service.createTransaction(checkoutWithClientId("150000", "double-tap"), aliceId));
            assertThrows(TimeoutException.class, () -> secondCall.get(PROBE_MILLIS, TimeUnit.MILLISECONDS),
                    "the second checkout has to wait for the wallet lock of the first");

            firstMayCommit.countDown();
            first = firstCall.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            second = secondCall.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            firstMayCommit.countDown();
            pool.shutdownNow();
        }

        // Without the lock both would have passed the lookup, and the second would at best have failed on the unique key.
        assertEquals(first.get("transactionId"), second.get("transactionId"));
        assertEquals(2, transactionRepository.count(), "the transaction of the fixture plus one");
        assertMoney("850000", walletInDatabase());
        assertMoney("850000", (BigDecimal) second.get("remainingBalance"));
        verify(budgetAlertService, times(1)).notifyAfterCreate(any());
    }

    @Test
    void anEditWaitsForAnotherWriterOfTheWalletAndThenCountsWhatItCommitted() throws Exception {
        CountDownLatch otherHoldsTheLock = new CountDownLatch(1);
        CountDownLatch otherMayCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Another request locks the wallet row, adds 500,000 to it and has not committed yet.
            Future<?> otherWriter = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                User locked = entityManager.find(User.class, aliceId, LockModeType.PESSIMISTIC_WRITE);
                locked.setWalletBalance(locked.getWalletBalance().add(new BigDecimal("500000")));
                otherHoldsTheLock.countDown();
                awaitQuietly(otherMayCommit);
            }));
            assertTrue(otherHoldsTheLock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            // This edit spends 50,000 more. It has read the wallet as 1,000,000 by the time it asks for the lock.
            Future<?> edit = pool.submit(() -> service.updateTransaction(expense.getId(), amount("150000")));
            assertThrows(TimeoutException.class, () -> edit.get(PROBE_MILLIS, TimeUnit.MILLISECONDS),
                    "the edit has to wait for the wallet lock of the other writer");

            otherMayCommit.countDown();
            otherWriter.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            edit.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            otherMayCommit.countDown();
            pool.shutdownNow();
        }

        // 1,000,000 + 500,000 from the other writer - 50,000 from the edit. Without the lock the edit would have
        // written 950,000 and the 500,000 would have been lost.
        assertMoney("1450000", walletInDatabase());
    }

    @Test
    void anApprovalThatWaitedForTheWalletLockCountsTheApprovalThatCommittedInTheMeantime() throws Exception {
        Long receiptId = receiptOfAliceWaitingForReview(); // the transaction holds 90,000, the wallet 910,000
        Long transactionId = transactionIdOf(receiptId);
        CountDownLatch otherHoldsTheLock = new CountDownLatch(1);
        CountDownLatch otherMayCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Another approval of the same receipt has locked the wallet and changed everything, but has not committed yet.
            Future<?> otherApproval = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                User locked = entityManager.find(User.class, aliceId, LockModeType.PESSIMISTIC_WRITE);
                locked.setWalletBalance(locked.getWalletBalance().subtract(new BigDecimal("60000")));
                entityManager.find(Transaction.class, transactionId).setAmount(new BigDecimal("150000.00"));
                entityManager.find(Receipt.class, receiptId).setStatus("APPROVED");
                otherHoldsTheLock.countDown();
                awaitQuietly(otherMayCommit);
            }));
            assertTrue(otherHoldsTheLock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            // This approval has read the transaction as 90,000 and the receipt as waiting by the time it asks for the lock.
            Future<?> approval = pool.submit(() -> service.approveReceipt(receiptId));
            assertThrows(TimeoutException.class, () -> approval.get(PROBE_MILLIS, TimeUnit.MILLISECONDS),
                    "the approval has to wait for the wallet lock of the other one");

            otherMayCommit.countDown();
            otherApproval.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            approval.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            otherMayCommit.countDown();
            pool.shutdownNow();
        }

        // 910,000 - 60,000, once. Working from what it had read before the wait, the second approval would have taken
        // another 60,000 from the wallet without the transaction growing any further.
        assertMoney("850000", walletInDatabase());
        assertMoney("150000", transactionInDatabase(transactionId).getAmount());
        assertEquals("APPROVED", receiptRepository.findById(receiptId).orElseThrow().getStatus());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
