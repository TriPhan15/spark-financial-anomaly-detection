package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.BudgetRequestDto;
import vn.edu.huit.finance.core.Dto.ReceiptRetryRequestDto;
import vn.edu.huit.finance.core.Dto.ReceiptUpdateRequestDto;
import vn.edu.huit.finance.core.Dto.TransactionRequestDto;
import vn.edu.huit.finance.core.Dto.TransactionUpdateRequestDto;
import vn.edu.huit.finance.core.Entity.ActionLog;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.BudgetPeriodType;
import vn.edu.huit.finance.core.Entity.Category;
import vn.edu.huit.finance.core.Entity.Receipt;
import vn.edu.huit.finance.core.Entity.ReceiptStatus;
import vn.edu.huit.finance.core.Entity.Transaction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.AuditLogWriteException;
import vn.edu.huit.finance.core.Exception.BudgetNotFoundException;
import vn.edu.huit.finance.core.Exception.TransactionAccessDeniedException;
import vn.edu.huit.finance.core.Repository.ActionLogRepository;
import vn.edu.huit.finance.core.Repository.BudgetRepository;
import vn.edu.huit.finance.core.Repository.CategoryRepository;
import vn.edu.huit.finance.core.Repository.ReceiptRepository;
import vn.edu.huit.finance.core.Repository.TransactionRepository;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;
import vn.edu.huit.finance.core.Security.Roles;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * The activity log of the money flows, end to end on H2: the real services through their real transaction proxies, the
 * real AuditLogService, and every check made on the rows the database holds afterwards. Each change leaves its rows with
 * the right actor (the caller; an administrator for an approval or a rejection) and target; a refused change leaves none;
 * a change and its rows commit or roll back together; and no row holds anything of what the AI answered.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TransactionService.class, ReceiptProcessingService.class, OcrService.class, UserStatisticsService.class,
        BudgetService.class, AuditLogService.class, SecurityAuditListener.class})
class AuditTrailIntegrationTest {

    private static final BigDecimal WALLET = new BigDecimal("1000000.00");
    private static final String SELLER = "Bách Hóa Xanh";
    private static final String ADDRESS = "12 Nguyễn Trãi, Quận 1";
    private static final String FULL_SCAN = "{\"seller\": {\"seller_name\": \"" + SELLER + "\", \"address\": \"" + ADDRESS + "\","
            + " \"timestamp\": \"27/05/2026 10:30\", \"total_cost\": \"245.000\", \"total_cost_number\": 245000}}";
    private static final String UNREADABLE_TOTAL = "{\"seller\": {\"seller_name\": \"Highlands\", \"total_cost\": \"0\"}}";
    private static final String ANOTHER_SCAN = "{\"seller\": {\"seller_name\": \"Highlands Coffee\", \"total_cost_number\": 95000}}";

    @Autowired private TransactionService transactionService;
    @Autowired private ReceiptProcessingService receiptService;
    @Autowired private BudgetService budgetService;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private ActionLogRepository actionLogRepository;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private CurrentUserProvider currentUserProvider;
    // The alerts have their own end-to-end test (BudgetAlertRollbackTest).
    @MockBean private BudgetAlertService budgetAlertService;

    private Long aliceId;
    private Long bobId;
    private Long adminId;
    private Category food;
    private String refusingConstraint;

    @BeforeEach
    void setUp() {
        aliceId = account("alice", "0900000001", Roles.USER);
        bobId = account("bob", "0900000002", Roles.USER);
        adminId = account("root", "0900000003", Roles.ADMIN);
        food = new Category();
        food.setName("Ăn uống");
        food.setType("EXPENSE");
        food = categoryRepository.saveAndFlush(food);
        signedIn(aliceId);
    }

    @AfterEach
    void cleanUp() {
        if (refusingConstraint != null) {
            jdbc.execute("alter table action_logs drop constraint " + refusingConstraint);
        }
        actionLogRepository.deleteAll();
        budgetRepository.deleteAll();
        transactionRepository.deleteAll();
        receiptRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---------------------------------------------------------------------------------------------------------- fixtures

    private Long account(String name, String phone, String role) {
        User user = new User();
        user.setUsername(name);
        user.setPassword("$2a$04$notARealHashButNeverRead");
        user.setEmail(name + "@example.com");
        user.setPhone(phone);
        user.setRole(role);
        user.setWalletBalance(WALLET);
        return userRepository.saveAndFlush(user).getId();
    }

    /** Every call answers with a fresh read, inside whatever transaction the service has opened by then. */
    private void signedIn(Long userId) {
        when(currentUserProvider.getCurrentUser()).thenAnswer(invocation -> userRepository.findById(userId).orElseThrow());
    }

    private Long checkout(String amount, String rawReceiptData) {
        TransactionRequestDto request = new TransactionRequestDto();
        request.setAmount(new BigDecimal(amount));
        request.setExpense(true);
        request.setCategoryId(food.getId());
        request.setNote("Ghi chú riêng tư của Alice");
        request.setTransactionDate(LocalDateTime.of(2026, 9, 20, 9, 0));
        request.setRawReceiptData(rawReceiptData);
        return (Long) transactionService.createMyTransaction(request).get("transactionId");
    }

    private Long receiptIdOf(Long transactionId) {
        return transactionRepository.findDetailedById(transactionId).orElseThrow().getReceipt().getId();
    }

    private List<ActionLog> rows() {
        return actionLogRepository.findAll().stream().sorted(Comparator.comparing(ActionLog::getId)).toList();
    }

    private List<String> actions() {
        return rows().stream().map(ActionLog::getAction).toList();
    }

    private ActionLog onlyRowOf(AuditAction action) {
        List<ActionLog> rows = rows().stream().filter(row -> action.name().equals(row.getAction())).toList();
        assertEquals(1, rows.size(), action + " in " + actions());
        return rows.get(0);
    }

    private static void assertRow(ActionLog row, Long actorId, String actorEmail, String targetType, Long targetId) {
        assertEquals(actorId, row.getUserId(), row.getAction());
        assertEquals(actorEmail, row.getUserEmail(), row.getAction());
        assertEquals(targetType, row.getTargetType(), row.getAction());
        assertEquals(targetId, row.getTargetId(), row.getAction());
        assertNotNull(row.getTimestamp());
    }

    /** Nothing the AI answered, and nothing the client wrote in free text, is in any column of any row. */
    private void assertNoRowHolds(String... texts) {
        for (ActionLog row : rows()) {
            String everything = String.join("|", String.valueOf(row.getAction()), String.valueOf(row.getUserEmail()),
                    String.valueOf(row.getDescription()), String.valueOf(row.getIpAddress()));
            for (String text : texts) {
                assertFalse(everything.contains(text), row.getAction() + " holds " + text);
            }
        }
    }

    /** From now on the database refuses every audit row of {@code action}, with an error of its own. */
    private void theDatabaseRefusesRowsOf(AuditAction action) {
        refusingConstraint = "audit_refuses_" + action.name().toLowerCase();
        jdbc.execute("alter table action_logs add constraint " + refusingConstraint + " check (action <> '" + action.name() + "')");
    }

    private BigDecimal walletOf(Long userId) {
        return userRepository.findById(userId).orElseThrow().getWalletBalance();
    }

    // ------------------------------------------------------------------------------------------------------ transactions

    @Test
    void aCheckoutIsRecordedWithItsCallerAndTheNewTransaction() {
        Long transactionId = checkout("150000", null);

        assertEquals(List.of("TRANSACTION_CREATED"), actions());
        ActionLog row = onlyRowOf(AuditAction.TRANSACTION_CREATED);
        assertRow(row, aliceId, "alice@example.com", "TRANSACTION", transactionId);
        assertEquals("SUCCESS", row.getResult());
        assertEquals("Tạo giao dịch chi 150000 VNĐ, danh mục #" + food.getId() + ", của người dùng #" + aliceId,
                row.getDescription());
        assertNoRowHolds("Ghi chú riêng tư");
    }

    @Test
    void aScannedCheckoutRecordsTheTransactionTheReceiptAndItsReading_withoutAnythingTheAiAnswered() {
        Long transactionId = checkout("245000", FULL_SCAN);
        Long receiptId = receiptIdOf(transactionId);

        assertEquals(List.of("TRANSACTION_CREATED", "RECEIPT_CREATED", "RECEIPT_PROCESSING_SUCCEEDED"), actions());
        assertRow(onlyRowOf(AuditAction.RECEIPT_CREATED), aliceId, "alice@example.com", "RECEIPT", receiptId);
        assertRow(onlyRowOf(AuditAction.RECEIPT_PROCESSING_SUCCEEDED), aliceId, "alice@example.com", "RECEIPT", receiptId);
        assertNoRowHolds(FULL_SCAN, SELLER, ADDRESS, "seller_name", "total_cost");
    }

    @Test
    void anEditAndADeletionOfTheCallersTransactionAreRecorded_withTheMoneyThatMoved() {
        Long transactionId = checkout("100000", null);
        TransactionUpdateRequestDto edit = new TransactionUpdateRequestDto();
        edit.setAmount(new BigDecimal("120000"));
        edit.setNote("Một ghi chú khác");

        transactionService.updateTransaction(transactionId, edit);
        transactionService.deleteTransaction(transactionId);

        assertEquals(List.of("TRANSACTION_CREATED", "TRANSACTION_UPDATED", "TRANSACTION_DELETED"), actions());
        ActionLog updated = onlyRowOf(AuditAction.TRANSACTION_UPDATED);
        assertRow(updated, aliceId, "alice@example.com", "TRANSACTION", transactionId);
        assertTrue(updated.getDescription().contains("số tiền 100000.00 → 120000 VNĐ"), updated.getDescription());
        assertTrue(updated.getDescription().contains("ghi chú"), updated.getDescription());
        assertTrue(updated.getDescription().endsWith("; ví -20000.00 VNĐ"), updated.getDescription());
        ActionLog deleted = onlyRowOf(AuditAction.TRANSACTION_DELETED);
        assertRow(deleted, aliceId, "alice@example.com", "TRANSACTION", transactionId);
        assertTrue(deleted.getDescription().endsWith("; ví +120000.00 VNĐ"), deleted.getDescription());
        assertNoRowHolds("Một ghi chú khác", "Ghi chú riêng tư");
        assertEquals(0, WALLET.compareTo(walletOf(aliceId)));
    }

    @Test
    void bobCannotChangeAlicesTransaction_andNoRowSaysAnybodyDid() {
        Long transactionId = checkout("100000", null);
        signedIn(bobId);
        TransactionUpdateRequestDto edit = new TransactionUpdateRequestDto();
        edit.setAmount(BigDecimal.ONE);

        assertThrows(TransactionAccessDeniedException.class, () -> transactionService.updateTransaction(transactionId, edit));
        assertThrows(TransactionAccessDeniedException.class, () -> transactionService.deleteTransaction(transactionId));

        assertEquals(List.of("TRANSACTION_CREATED"), actions(), "only Alice's own checkout");
        assertEquals(aliceId, onlyRowOf(AuditAction.TRANSACTION_CREATED).getUserId());
        assertTrue(rows().stream().noneMatch(row -> bobId.equals(row.getUserId())));
    }

    @Test
    void aCheckoutWhoseRowTheDatabaseRefusesIsRolledBack_walletAndAll() {
        theDatabaseRefusesRowsOf(AuditAction.TRANSACTION_CREATED);

        assertThrows(AuditLogWriteException.class, () -> checkout("150000", null));

        assertEquals(0, transactionRepository.count());
        assertEquals(0, WALLET.compareTo(walletOf(aliceId)), "no change without its row");
        assertEquals(List.of(), actions());
    }

    @Test
    void whenALaterRowOfTheSameChangeIsRefused_theRowsWrittenBeforeItRollBackWithTheChange() {
        // TRANSACTION_CREATED is written, then RECEIPT_CREATED is refused: no row may claim the checkout happened.
        theDatabaseRefusesRowsOf(AuditAction.RECEIPT_CREATED);

        assertThrows(AuditLogWriteException.class, () -> checkout("245000", FULL_SCAN));

        assertEquals(List.of(), actions(), "no success row for a change that rolled back");
        assertEquals(0, transactionRepository.count());
        assertEquals(0, receiptRepository.count());
        assertEquals(0, WALLET.compareTo(walletOf(aliceId)));
    }

    // ---------------------------------------------------------------------------------------------------------- receipts

    @Test
    void aRetryRecordsItselfAndTheOutcomeOfItsReading_onTheReceipt() {
        Long receiptId = receiptIdOf(checkout("90000", UNREADABLE_TOTAL));
        ReceiptRetryRequestDto retry = new ReceiptRetryRequestDto();
        retry.setRawReceiptData(ANOTHER_SCAN);
        retry.setClientId("retry-1");

        receiptService.retryMyReceipt(receiptId, retry);

        assertEquals(List.of("TRANSACTION_CREATED", "RECEIPT_CREATED", "RECEIPT_PROCESSING_FAILED", "RECEIPT_RETRIED",
                "RECEIPT_PROCESSING_SUCCEEDED"), actions());
        assertRow(onlyRowOf(AuditAction.RECEIPT_RETRIED), aliceId, "alice@example.com", "RECEIPT", receiptId);
        ActionLog failed = onlyRowOf(AuditAction.RECEIPT_PROCESSING_FAILED);
        assertEquals("FAILURE", failed.getResult());
        assertEquals("Lần đọc #1: AI không đọc được tổng tiền (AI_NO_TOTAL), trạng thái FAILED", failed.getDescription());
        assertEquals("Lần đọc #2: AI đọc được tổng tiền, trạng thái COMPLETED_RAW",
                onlyRowOf(AuditAction.RECEIPT_PROCESSING_SUCCEEDED).getDescription());
        assertNoRowHolds(ANOTHER_SCAN, UNREADABLE_TOTAL, "Highlands", "retry-1");
    }

    @Test
    void anEditAndAFeedbackOfTheOwnerAreRecorded() {
        Long readReceipt = receiptIdOf(checkout("245000", FULL_SCAN));
        Long failedReceipt = receiptIdOf(checkout("90000", UNREADABLE_TOTAL));
        ReceiptUpdateRequestDto edit = new ReceiptUpdateRequestDto();
        edit.setVersion(receiptRepository.findById(readReceipt).orElseThrow().getVersion());
        edit.setVendorName("Tên cửa hàng do Alice sửa");
        edit.setExtractedDate(LocalDate.of(2026, 5, 28));

        receiptService.editMyReceipt(readReceipt, edit);
        receiptService.submitFeedback(failedReceipt, new BigDecimal("120000"));

        ActionLog edited = onlyRowOf(AuditAction.RECEIPT_UPDATED);
        assertRow(edited, aliceId, "alice@example.com", "RECEIPT", readReceipt);
        assertTrue(edited.getDescription().startsWith("Sửa hóa đơn (tên cửa hàng, ngày trên hóa đơn): COMPLETED_RAW → EDITED"),
                edited.getDescription());
        ActionLog feedback = onlyRowOf(AuditAction.RECEIPT_FEEDBACK_SUBMITTED);
        assertRow(feedback, aliceId, "alice@example.com", "RECEIPT", failedReceipt);
        assertTrue(feedback.getDescription().contains("120000.00 VNĐ"), feedback.getDescription());
        assertNoRowHolds("Tên cửa hàng do Alice sửa");
    }

    @Test
    void anApprovalIsRecordedWithTheAdministratorAsActor_andTheTransactionItReconciled() {
        Long transactionId = checkout("200000", FULL_SCAN);
        Long receiptId = receiptIdOf(transactionId);
        signedIn(adminId);

        transactionService.approveReceipt(receiptId);

        ActionLog approved = onlyRowOf(AuditAction.RECEIPT_APPROVED);
        assertRow(approved, adminId, "root@example.com", "RECEIPT", receiptId);
        assertTrue(approved.getDescription().contains("người dùng #" + aliceId), approved.getDescription());
        ActionLog reconciled = onlyRowOf(AuditAction.TRANSACTION_UPDATED);
        assertRow(reconciled, adminId, "root@example.com", "TRANSACTION", transactionId);
        assertTrue(reconciled.getDescription().contains("200000.00 → 245000.00 VNĐ; ví -45000.00 VNĐ"),
                reconciled.getDescription());
        assertNoRowHolds(FULL_SCAN, SELLER);
    }

    @Test
    void aRejectionIsRecordedWithTheAdministratorAsActor_neverTheOwner() {
        Long receiptId = receiptIdOf(checkout("245000", FULL_SCAN));
        signedIn(adminId);

        receiptService.rejectReceipt(receiptId);

        ActionLog rejected = onlyRowOf(AuditAction.RECEIPT_REJECTED);
        assertRow(rejected, adminId, "root@example.com", "RECEIPT", receiptId);
        assertEquals("Từ chối hóa đơn: COMPLETED_RAW → FAILED (REJECTED_BY_ADMIN), chủ sở hữu #" + aliceId,
                rejected.getDescription());
    }

    @Test
    void aCancellationByTheOwnerIsRecorded() {
        Long transactionId = checkout("50000", null);
        Transaction transaction = transactionRepository.findDetailedById(transactionId).orElseThrow();
        Receipt pending = new Receipt();
        pending.setTransaction(transaction);
        Long receiptId = receiptRepository.saveAndFlush(pending).getId();
        jdbc.update("update transactions set receipt_id = ? where id = ?", receiptId, transactionId);

        transactionService.cancelMyReceipt(receiptId);

        ActionLog canceled = onlyRowOf(AuditAction.RECEIPT_CANCELED);
        assertRow(canceled, aliceId, "alice@example.com", "RECEIPT", receiptId);
        assertEquals("Hủy hóa đơn: " + ReceiptStatus.PENDING.name() + " → " + ReceiptStatus.CANCELED.name(),
                canceled.getDescription());
    }

    // ----------------------------------------------------------------------------------------------------------- budgets

    private static BudgetRequestDto budget(LocalDate start) {
        BudgetRequestDto request = new BudgetRequestDto();
        request.setAmount(new BigDecimal("3000000"));
        request.setPeriodType(BudgetPeriodType.MONTH);
        request.setStartDate(start);
        request.setEndDate(start.plusMonths(1).minusDays(1));
        return request;
    }

    @Test
    void theCreationEditAndDeletionOfABudgetAreRecordedWithItsOwner_andBobsAttemptIsNot() {
        Long budgetId = budgetService.createBudget(budget(LocalDate.of(2026, 9, 1))).getId();
        budgetService.updateBudget(budgetId, budget(LocalDate.of(2026, 10, 1)));
        signedIn(bobId);
        assertThrows(BudgetNotFoundException.class, () -> budgetService.deleteBudget(budgetId));
        signedIn(aliceId);
        budgetService.deleteBudget(budgetId);

        assertEquals(List.of("BUDGET_CREATED", "BUDGET_UPDATED", "BUDGET_DELETED"), actions());
        for (ActionLog row : rows()) {
            assertRow(row, aliceId, "alice@example.com", "BUDGET", budgetId);
        }
        assertEquals("Sửa ngân sách kỳ MONTH từ 2026-10-01, mọi danh mục", onlyRowOf(AuditAction.BUDGET_UPDATED).getDescription());
        assertNoRowHolds("3000000");
    }
}
