package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.TransactionRequestDto;
import vn.edu.huit.finance.core.Dto.TransactionUpdateRequestDto;
import vn.edu.huit.finance.core.Entity.Budget;
import vn.edu.huit.finance.core.Entity.BudgetPeriodType;
import vn.edu.huit.finance.core.Entity.Category;
import vn.edu.huit.finance.core.Entity.Notification;
import vn.edu.huit.finance.core.Entity.NotificationType;
import vn.edu.huit.finance.core.Entity.Transaction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Repository.BudgetRepository;
import vn.edu.huit.finance.core.Repository.CategoryRepository;
import vn.edu.huit.finance.core.Repository.NotificationRepository;
import vn.edu.huit.finance.core.Repository.ReceiptRepository;
import vn.edu.huit.finance.core.Repository.TransactionRepository;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * The budget alert end to end on H2: the real TransactionService, BudgetAlertService, NotificationService and queries.
 * Nothing here is wrapped in a test transaction (that would hide the very commits and rollbacks under test), and every
 * check reads the database again afterwards.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TransactionService.class, ReceiptProcessingService.class, OcrService.class,
        BudgetAlertService.class, NotificationService.class, UserStatisticsService.class})
class BudgetAlertRollbackTest {

    private static final BigDecimal WALLET = new BigDecimal("10000000.00");
    private static final LocalDateTime SEPTEMBER_15 = LocalDateTime.of(2026, 9, 15, 12, 0);
    private static final int WHOLE_LIST = 100;

    @Autowired private TransactionService service;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private ReceiptRepository receiptRepository;
    @SpyBean private NotificationRepository notificationRepository;
    @MockBean private CurrentUserProvider currentUserProvider;
    @MockBean private AuditLogService auditLogService;

    private Long aliceId;
    private Category food;
    private Budget foodBudget;
    private Transaction editable;

    @BeforeEach
    void setUp() {
        aliceId = persistUser("alice", "0900000001").getId();
        food = persistCategory("Ăn uống");
        foodBudget = persistBudget(aliceId, food, "2000000");

        // September so far: 1,500,000 on food, which is 75% of the food budget of 2,000,000.
        persistExpense("1400000", LocalDateTime.of(2026, 9, 3, 9, 0));
        editable = persistExpense("100000", LocalDateTime.of(2026, 9, 5, 9, 0));
        // Every call answers with a fresh read, inside whatever transaction the service has opened by then.
        when(currentUserProvider.getCurrentUser()).thenAnswer(invocation -> userRepository.findById(aliceId).orElseThrow());
    }

    @AfterEach
    void cleanUp() {
        notificationRepository.deleteAll();
        transactionRepository.deleteAll();
        receiptRepository.deleteAll();
        budgetRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---------------------------------------------------------------------------------------------------------- fixtures

    private User persistUser(String username, String phone) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("hash");
        user.setEmail(username + "@example.com");
        user.setPhone(phone);
        user.setWalletBalance(WALLET);
        return userRepository.saveAndFlush(user);
    }

    private Category persistCategory(String name) {
        Category category = new Category();
        category.setName(name);
        category.setType("EXPENSE");
        return categoryRepository.saveAndFlush(category);
    }

    /** A monthly budget for September; a null category means every category. */
    private Budget persistBudget(Long ownerId, Category category, String limit) {
        Budget budget = new Budget();
        budget.setUser(userRepository.findById(ownerId).orElseThrow());
        budget.setCategory(category);
        budget.setAmount(new BigDecimal(limit));
        budget.setPeriodType(BudgetPeriodType.MONTH);
        budget.setStartDate(LocalDate.of(2026, 9, 1));
        budget.setEndDate(LocalDate.of(2026, 9, 30));
        return budgetRepository.saveAndFlush(budget);
    }

    private Transaction persistExpense(String amount, LocalDateTime date) {
        Transaction transaction = new Transaction();
        transaction.setUser(userRepository.findById(aliceId).orElseThrow());
        transaction.setCategory(food);
        transaction.setCategoryName(food.getName());
        transaction.setAmount(new BigDecimal(amount));
        transaction.setExpense(true);
        transaction.setNote("Bún bò");
        transaction.setPaymentMethod("Ví chính");
        transaction.setTransactionDate(date);
        return transactionRepository.saveAndFlush(transaction);
    }

    private static TransactionRequestDto checkout(String amount) {
        TransactionRequestDto request = new TransactionRequestDto();
        request.setAmount(new BigDecimal(amount));
        request.setExpense(true);
        request.setLocation("Quán A");
        request.setPaymentMethod("Ví chính");
        request.setTransactionDate(SEPTEMBER_15);
        request.setCategoryName("Ăn uống");
        return request;
    }

    private static TransactionUpdateRequestDto amount(String value) {
        TransactionUpdateRequestDto request = new TransactionUpdateRequestDto();
        request.setAmount(new BigDecimal(value));
        return request;
    }

    private List<Notification> notificationsOf(Long userId) {
        return notificationRepository.findNewestByUserId(userId, PageRequest.of(0, WHOLE_LIST)).getContent();
    }

    private BigDecimal walletInDatabase() {
        return userRepository.findById(aliceId).orElseThrow().getWalletBalance();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    // ----------------------------------------------------------------------------------------------- a new expense

    @Test
    void anExpenseThatTakesTheBudgetToEightyPercentCommitsItsNotificationWithTheTransaction() {
        Object createdId = service.createTransaction(checkout("200000"), aliceId).get("transactionId");

        List<Notification> raised = notificationsOf(aliceId);
        assertEquals(1, raised.size());
        assertEquals(NotificationType.BUDGET_WARNING, raised.get(0).getType());
        assertEquals(foodBudget.getId(), raised.get(0).getBudgetId());
        assertNotNull(createdId);
        assertEquals(createdId, raised.get(0).getTransactionId(), "the alert points at the expense that crossed the level");
        assertNull(raised.get(0).getReceiptId());
        assertFalse(raised.get(0).isRead(), "a new notification is unread");
        assertEquals("Bạn đã chi 1.700.000 / 2.000.000 VNĐ, đạt 80% ngân sách Ăn uống.", raised.get(0).getMessage());
        assertNotNull(raised.get(0).getCreatedAt());
        assertEquals(3, transactionRepository.count());
        assertMoney("9800000", walletInDatabase());
    }

    @Test
    void anExpenseThatStaysBelowEightyPercentRaisesNothing() {
        service.createTransaction(checkout("50000"), aliceId); // 1,550,000 of 2,000,000

        assertTrue(notificationsOf(aliceId).isEmpty());
        assertEquals(3, transactionRepository.count());
    }

    @Test
    void anExpenseInAnotherCategoryDoesNotCountForTheBudgetOfFood() {
        TransactionRequestDto request = checkout("900000");
        request.setCategoryName("Di chuyển"); // created by the checkout; the food budget does not count it

        service.createTransaction(request, aliceId);

        assertTrue(notificationsOf(aliceId).isEmpty());
    }

    @Test
    void everyBudgetThatIsCrossedRaisesItsOwnNotification_theCategoryOneAndTheEveryCategoryOne() {
        // Everything spent in September is 1,500,000; the 200,000 below takes it to 1,700,000, which is over this limit.
        Budget everything = persistBudget(aliceId, null, "1600000");

        service.createTransaction(checkout("200000"), aliceId);

        List<Notification> raised = notificationsOf(aliceId);
        assertEquals(Set.of(NotificationType.BUDGET_WARNING, NotificationType.BUDGET_EXCEEDED),
                raised.stream().map(Notification::getType).collect(Collectors.toSet()));
        assertEquals(Set.of(foodBudget.getId(), everything.getId()),
                raised.stream().map(Notification::getBudgetId).collect(Collectors.toSet()));
    }

    @Test
    void theBudgetOfAnotherUserIsNeverConsidered() {
        Long bobId = persistUser("bob", "0900000002").getId();
        persistBudget(bobId, food, "100000"); // far below what Alice is about to spend

        service.createTransaction(checkout("200000"), aliceId);

        assertTrue(notificationsOf(bobId).isEmpty(), "Alice's spending must not raise anything for Bob");
        assertEquals(1, notificationsOf(aliceId).size());
    }

    // ------------------------------------------------------------------------------------------------ an edited expense

    @Test
    void raisingTheAmountOfAnExpenseThroughAnEditCrossesTheBudgetOnce() {
        service.updateTransaction(editable.getId(), amount("300000")); // 1,500,000 becomes 1,700,000

        List<Notification> raised = notificationsOf(aliceId);
        assertEquals(1, raised.size());
        assertEquals(NotificationType.BUDGET_WARNING, raised.get(0).getType());
    }

    @Test
    void editingWhatIsAlreadyOverTheLevelRaisesNothingMore() {
        service.updateTransaction(editable.getId(), amount("300000"));
        TransactionUpdateRequestDto onlyANote = new TransactionUpdateRequestDto();
        onlyANote.setNote("Bún chả");

        service.updateTransaction(editable.getId(), onlyANote);
        service.updateTransaction(editable.getId(), amount("310000")); // still between 80% and 100%

        assertEquals(1, notificationsOf(aliceId).size(), "the alert must not repeat while the spending stays above the level");
    }

    @Test
    void anEditThatLowersTheSpendingRaisesNothing() {
        service.updateTransaction(editable.getId(), amount("50000"));

        assertTrue(notificationsOf(aliceId).isEmpty());
    }

    // ----------------------------------------------------------------------------------------------------- atomicity

    @Test
    void whenTheNotificationCannotBeSavedTheWholeCheckoutIsRolledBack() {
        doThrow(new IllegalStateException("database went away")).when(notificationRepository).save(any(Notification.class));

        assertThrows(IllegalStateException.class, () -> service.createTransaction(checkout("200000"), aliceId));

        assertEquals(2, transactionRepository.count(), "only the transactions of the fixture are left");
        assertMoney("10000000", walletInDatabase());
        assertEquals(0, notificationRepository.count());
    }

    @Test
    void whenTheNotificationCannotBeSavedTheEditIsRolledBackToo() {
        doThrow(new IllegalStateException("database went away")).when(notificationRepository).save(any(Notification.class));

        assertThrows(IllegalStateException.class, () -> service.updateTransaction(editable.getId(), amount("300000")));

        assertMoney("10000000", walletInDatabase());
        assertMoney("100000", transactionRepository.findDetailedById(editable.getId()).orElseThrow().getAmount());
        assertEquals(0, notificationRepository.count());
    }
}
