package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Entity.Budget;
import vn.edu.huit.finance.core.Entity.BudgetPeriodType;
import vn.edu.huit.finance.core.Entity.Category;
import vn.edu.huit.finance.core.Entity.NotificationType;
import vn.edu.huit.finance.core.Entity.Transaction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Repository.BudgetRepository;
import vn.edu.huit.finance.core.Repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * When does a saved or edited expense raise a budget notification? Everything the service reads is faked, so each case
 * states the totals of the budget and checks the notification that comes out (or that none does).
 */
@ExtendWith(MockitoExtension.class)
class BudgetAlertServiceTest {

    private static final Long ALICE_ID = 1L;
    private static final Long FOOD_ID = 10L;
    private static final Long TRANSPORT_ID = 20L;
    private static final Long FOOD_BUDGET_ID = 100L;
    private static final Long ALL_BUDGET_ID = 200L;
    private static final Long TRANSACTION_ID = 500L;
    private static final String LIMIT = "2000000";
    private static final LocalDate SEPTEMBER_FIRST = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEPTEMBER_LAST = LocalDate.of(2026, 9, 30);
    private static final LocalDate SPENT_ON = LocalDate.of(2026, 9, 15);
    // The period of the budgets as the sum queries see it: the last day is included, so the end is the next midnight.
    private static final LocalDateTime PERIOD_START = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime PERIOD_END_EXCLUSIVE = LocalDateTime.of(2026, 10, 1, 0, 0);

    @Mock private BudgetRepository budgetRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private NotificationService notificationService;
    @InjectMocks private BudgetAlertService service;

    private final User alice = user(ALICE_ID);
    private final Category food = category(FOOD_ID, "Ăn uống");
    private final Category transport = category(TRANSPORT_ID, "Di chuyển");

    // ---------------------------------------------------------------------------------------------------------- fixtures

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        return user;
    }

    private static Category category(Long id, String name) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        category.setType("EXPENSE");
        return category;
    }

    /** A monthly budget for September; a null category means every category. */
    private Budget budget(Long id, Category category, String limit) {
        Budget budget = new Budget();
        budget.setId(id);
        budget.setUser(alice);
        budget.setCategory(category);
        budget.setAmount(new BigDecimal(limit));
        budget.setPeriodType(BudgetPeriodType.MONTH);
        budget.setStartDate(SEPTEMBER_FIRST);
        budget.setEndDate(SEPTEMBER_LAST);
        return budget;
    }

    private Transaction expense(Category category, String amount) {
        Transaction transaction = new Transaction();
        transaction.setId(TRANSACTION_ID);
        transaction.setUser(alice);
        transaction.setCategory(category);
        transaction.setCategoryName(category.getName());
        transaction.setAmount(new BigDecimal(amount));
        transaction.setExpense(true);
        transaction.setTransactionDate(SPENT_ON.atTime(12, 0));
        return transaction;
    }

    private static BudgetAlertService.SpendingSnapshot counted(Category category, String amount, LocalDate date) {
        return new BudgetAlertService.SpendingSnapshot(new BigDecimal(amount), true, date, category.getId());
    }

    private void budgetsInForce(Budget... budgets) {
        when(budgetRepository.findCoveringDate(eq(ALICE_ID), eq(SPENT_ON), any())).thenReturn(List.of(budgets));
    }

    /** What the user has spent inside the budget once the change is saved; null is what an empty period gives. */
    private void totalInBudgetAfterTheChange(Budget budget, String total) {
        BigDecimal sum = total == null ? null : new BigDecimal(total);
        if (budget.getCategory() == null) {
            when(transactionRepository.sumExpensesBetween(ALICE_ID, PERIOD_START, PERIOD_END_EXCLUSIVE)).thenReturn(sum);
        } else {
            when(transactionRepository.sumExpensesInCategoryBetween(
                    ALICE_ID, budget.getCategory().getId(), PERIOD_START, PERIOD_END_EXCLUSIVE)).thenReturn(sum);
        }
    }

    private void assertNoNotification() {
        verify(notificationService, never()).create(any(), any(), anyString(), any(), any(), any());
    }

    // ----------------------------------------------------------------------------------- a new expense: the thresholds

    static Stream<Arguments> newExpenses() {
        // total afterwards, amount of the new expense, notification raised (null = none); the limit is 2,000,000
        return Stream.of(
                Arguments.of("100000", "100000", null),                                  // 5%
                Arguments.of("1599999", "100000", null),                                 // just under 80%
                Arguments.of("1600000", "100000", NotificationType.BUDGET_WARNING),      // exactly 80%: reached
                Arguments.of("1600000.00", "0.01", NotificationType.BUDGET_WARNING),     // one cent takes it to 80%
                Arguments.of("1700000", "100000", null),                                 // was at 80% already: no repeat
                Arguments.of("1700000", "300000", NotificationType.BUDGET_WARNING),      // 70% to 85%
                Arguments.of("1999999", "500000", NotificationType.BUDGET_WARNING),      // 75% to just under 100%
                Arguments.of("2000000", "100000", NotificationType.BUDGET_EXCEEDED),     // 95% to exactly 100%: reached
                Arguments.of("2000000.00", "0.01", NotificationType.BUDGET_EXCEEDED),    // one cent takes it to 100%
                Arguments.of("2100000", "100000", null),                                 // was at 100% already: no repeat
                Arguments.of("2500000", "1000000", NotificationType.BUDGET_EXCEEDED),    // 75% to 125%: only the higher level
                Arguments.of("3000000", "3000000", NotificationType.BUDGET_EXCEEDED));   // one expense over the whole limit
    }

    @ParameterizedTest
    @MethodSource("newExpenses")
    void aNewExpenseRaisesTheNotificationOfTheLevelItReaches_andOnlyOnce(
            String totalAfter, String amount, NotificationType expected) {
        Budget foodBudget = budget(FOOD_BUDGET_ID, food, LIMIT);
        budgetsInForce(foodBudget);
        totalInBudgetAfterTheChange(foodBudget, totalAfter);

        service.notifyAfterCreate(expense(food, amount));

        if (expected == null) {
            assertNoNotification();
        } else {
            verify(notificationService, times(1)).create(eq(alice), eq(expected), anyString(), eq(TRANSACTION_ID), eq(FOOD_BUDGET_ID), isNull());
            verifyNoMoreInteractions(notificationService);
        }
    }

    @Test
    void aJumpOverBothLevelsRaisesOnlyTheHigherOne() {
        Budget foodBudget = budget(FOOD_BUDGET_ID, food, LIMIT);
        budgetsInForce(foodBudget);
        totalInBudgetAfterTheChange(foodBudget, "2500000");

        service.notifyAfterCreate(expense(food, "1000000"));

        verify(notificationService).create(any(), eq(NotificationType.BUDGET_EXCEEDED), anyString(), any(), any(), any());
        verify(notificationService, never()).create(any(), eq(NotificationType.BUDGET_WARNING), anyString(), any(), any(), any());
    }

    @Test
    void aSumQueryThatFindsNothingCountsAsZero() {
        Budget foodBudget = budget(FOOD_BUDGET_ID, food, LIMIT);
        budgetsInForce(foodBudget);
        totalInBudgetAfterTheChange(foodBudget, null);

        assertDoesNotThrow(() -> service.notifyAfterCreate(expense(food, "100000")));

        assertNoNotification();
    }

    // ----------------------------------------------------------------------------------------------- what it looks at

    @Test
    void anIncomeNeverUsesUpABudget_soNothingIsReadOrRaised() {
        Transaction income = expense(food, "5000000");
        income.setExpense(false);

        service.notifyAfterCreate(income);

        verifyNoInteractions(budgetRepository, transactionRepository, notificationService);
    }

    @Test
    void withoutABudgetInForce_noTotalIsComputed() {
        budgetsInForce();

        service.notifyAfterCreate(expense(food, "100000"));

        verifyNoInteractions(transactionRepository, notificationService);
    }

    @Test
    void theBudgetsAreLookedUpForTheOwnerTheDayAndTheCategoryOrEveryCategory() {
        budgetsInForce();

        service.notifyAfterCreate(expense(food, "100000"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> categoryKeys = ArgumentCaptor.forClass(Collection.class);
        verify(budgetRepository).findCoveringDate(eq(ALICE_ID), eq(SPENT_ON), categoryKeys.capture());
        assertEquals(Set.of(Budget.ALL_CATEGORIES_KEY, FOOD_ID), new HashSet<>(categoryKeys.getValue()));
    }

    @Test
    void aBudgetOfOneCategoryCountsOnlyThatCategory_andABudgetOfEveryCategoryCountsEverything() {
        Budget foodBudget = budget(FOOD_BUDGET_ID, food, LIMIT);
        Budget allBudget = budget(ALL_BUDGET_ID, null, LIMIT);
        budgetsInForce(foodBudget, allBudget);
        totalInBudgetAfterTheChange(foodBudget, "100000");
        totalInBudgetAfterTheChange(allBudget, "100000");

        service.notifyAfterCreate(expense(food, "100000"));

        verify(transactionRepository).sumExpensesInCategoryBetween(ALICE_ID, FOOD_ID, PERIOD_START, PERIOD_END_EXCLUSIVE);
        verify(transactionRepository).sumExpensesBetween(ALICE_ID, PERIOD_START, PERIOD_END_EXCLUSIVE);
        verifyNoMoreInteractions(transactionRepository);
    }

    @Test
    void everyBudgetThatIsCrossedRaisesItsOwnNotification() {
        Budget foodBudget = budget(FOOD_BUDGET_ID, food, LIMIT);
        Budget allBudget = budget(ALL_BUDGET_ID, null, "3000000");
        budgetsInForce(foodBudget, allBudget);
        totalInBudgetAfterTheChange(foodBudget, "1700000");   // 85% of 2,000,000
        totalInBudgetAfterTheChange(allBudget, "3100000");    // over 3,000,000

        service.notifyAfterCreate(expense(food, "300000"));

        verify(notificationService).create(eq(alice), eq(NotificationType.BUDGET_WARNING), anyString(), eq(TRANSACTION_ID), eq(FOOD_BUDGET_ID), isNull());
        verify(notificationService).create(eq(alice), eq(NotificationType.BUDGET_EXCEEDED), anyString(), eq(TRANSACTION_ID), eq(ALL_BUDGET_ID), isNull());
    }

    @Test
    void aBudgetThatIsNotCrossedRaisesNothing_evenWhenAnotherOneIs() {
        Budget foodBudget = budget(FOOD_BUDGET_ID, food, LIMIT);
        Budget allBudget = budget(ALL_BUDGET_ID, null, "10000000");
        budgetsInForce(foodBudget, allBudget);
        totalInBudgetAfterTheChange(foodBudget, "1700000");
        totalInBudgetAfterTheChange(allBudget, "1700000");    // 17% of 10,000,000

        service.notifyAfterCreate(expense(food, "300000"));

        verify(notificationService).create(any(), any(), anyString(), any(), eq(FOOD_BUDGET_ID), any());
        verify(notificationService, never()).create(any(), any(), anyString(), any(), eq(ALL_BUDGET_ID), any());
    }

    // ---------------------------------------------------------------------------------------------- an edited expense

    @Test
    void raisingTheAmountOfAnExpenseRaisesTheNotificationOfTheLevelItReaches() {
        Budget foodBudget = budget(FOOD_BUDGET_ID, food, LIMIT);
        budgetsInForce(foodBudget);
        totalInBudgetAfterTheChange(foodBudget, "1700000");   // after the edit, which made the expense 300,000

        // Before the edit the expense was 100,000, so the total was 1,500,000 (75%).
        service.notifyAfterUpdate(expense(food, "300000"), counted(food, "100000", SPENT_ON));

        verify(notificationService).create(eq(alice), eq(NotificationType.BUDGET_WARNING), anyString(), eq(TRANSACTION_ID), eq(FOOD_BUDGET_ID), isNull());
    }

    @Test
    void loweringTheAmountOfAnExpenseRaisesNothing() {
        Budget foodBudget = budget(FOOD_BUDGET_ID, food, LIMIT);
        budgetsInForce(foodBudget);
        totalInBudgetAfterTheChange(foodBudget, "1500000");   // after the edit, which made the expense 100,000

        // Before the edit the expense was 300,000 and the total 1,700,000 (85%).
        service.notifyAfterUpdate(expense(food, "100000"), counted(food, "300000", SPENT_ON));

        assertNoNotification();
    }

    @Test
    void anEditThatDoesNotChangeTheSpending_suchAsANewNote_neverRepeatsAnAlert() {
        Budget foodBudget = budget(FOOD_BUDGET_ID, food, LIMIT);
        budgetsInForce(foodBudget);
        totalInBudgetAfterTheChange(foodBudget, "2100000");   // already over the limit, and stays there

        service.notifyAfterUpdate(expense(food, "300000"), counted(food, "300000", SPENT_ON));

        assertNoNotification();
    }

    @Test
    void movingAnExpenseIntoTheCategoryOfABudgetCanCrossIt() {
        Budget foodBudget = budget(FOOD_BUDGET_ID, food, LIMIT);
        budgetsInForce(foodBudget);
        totalInBudgetAfterTheChange(foodBudget, "1700000");   // includes the 500,000 that now counts as food

        // It was a transport expense: it counted for nothing in the food budget, whose total was 1,200,000 (60%).
        service.notifyAfterUpdate(expense(food, "500000"), counted(transport, "500000", SPENT_ON));

        verify(notificationService).create(eq(alice), eq(NotificationType.BUDGET_WARNING), anyString(), eq(TRANSACTION_ID), eq(FOOD_BUDGET_ID), isNull());
    }

    @Test
    void movingAnExpenseBetweenCategoriesDoesNotCrossABudgetOfEveryCategory_becauseItCountedThereAlready() {
        Budget allBudget = budget(ALL_BUDGET_ID, null, LIMIT);
        budgetsInForce(allBudget);
        totalInBudgetAfterTheChange(allBudget, "1700000");

        service.notifyAfterUpdate(expense(food, "500000"), counted(transport, "500000", SPENT_ON));

        // The 500,000 was inside the budget of every category before and after, so the total did not move: no repeat.
        assertNoNotification();
    }

    @Test
    void movingAnExpenseIntoThePeriodOfABudgetCanCrossIt() {
        Budget foodBudget = budget(FOOD_BUDGET_ID, food, LIMIT);
        budgetsInForce(foodBudget);
        totalInBudgetAfterTheChange(foodBudget, "1700000");

        // It used to be dated in August: it was outside this budget, whose total was 1,200,000.
        service.notifyAfterUpdate(expense(food, "500000"), counted(food, "500000", LocalDate.of(2026, 8, 20)));

        verify(notificationService).create(eq(alice), eq(NotificationType.BUDGET_WARNING), anyString(), eq(TRANSACTION_ID), eq(FOOD_BUDGET_ID), isNull());
    }

    @Test
    void anIncomeThatBecameAnExpenseCountsInFull() {
        Budget foodBudget = budget(FOOD_BUDGET_ID, food, LIMIT);
        budgetsInForce(foodBudget);
        totalInBudgetAfterTheChange(foodBudget, "1700000");
        BudgetAlertService.SpendingSnapshot wasAnIncome =
                new BudgetAlertService.SpendingSnapshot(new BigDecimal("500000"), false, SPENT_ON, FOOD_ID);

        service.notifyAfterUpdate(expense(food, "500000"), wasAnIncome);

        verify(notificationService).create(eq(alice), eq(NotificationType.BUDGET_WARNING), anyString(), eq(TRANSACTION_ID), eq(FOOD_BUDGET_ID), isNull());
    }

    @Test
    void anExpenseThatBecameAnIncomeRaisesNothing_andNothingIsRead() {
        Transaction nowAnIncome = expense(food, "500000");
        nowAnIncome.setExpense(false);

        service.notifyAfterUpdate(nowAnIncome, counted(food, "500000", SPENT_ON));

        verifyNoInteractions(budgetRepository, transactionRepository, notificationService);
    }

    // ----------------------------------------------------------------------------------------------------- the message

    @Test
    void theWarningNamesTheCategoryTheFiguresAndTheLevel() {
        Budget foodBudget = budget(FOOD_BUDGET_ID, food, LIMIT);
        budgetsInForce(foodBudget);
        totalInBudgetAfterTheChange(foodBudget, "1600000");

        service.notifyAfterCreate(expense(food, "100000"));

        verify(notificationService).create(alice, NotificationType.BUDGET_WARNING,
                "Bạn đã chi 1.600.000 / 2.000.000 VNĐ, đạt 80% ngân sách Ăn uống.", TRANSACTION_ID, FOOD_BUDGET_ID, null);
    }

    @Test
    void theExceededMessageOfABudgetOfEveryCategoryNamesNoCategory() {
        Budget allBudget = budget(ALL_BUDGET_ID, null, LIMIT);
        budgetsInForce(allBudget);
        totalInBudgetAfterTheChange(allBudget, "2100000");

        service.notifyAfterCreate(expense(food, "600000"));

        verify(notificationService).create(alice, NotificationType.BUDGET_EXCEEDED,
                "Bạn đã chi 2.100.000 / 2.000.000 VNĐ, vượt ngân sách tất cả danh mục.", TRANSACTION_ID, ALL_BUDGET_ID, null);
    }

    // ---------------------------------------------------------------------------------------------- structural guards

    @Test
    void bothEntryPointsRunInAWritableTransaction_soTheNotificationIsCommittedOrRolledBackWithTheCaller() throws NoSuchMethodException {
        List<java.lang.reflect.Method> entryPoints = List.of(
                BudgetAlertService.class.getMethod("notifyAfterCreate", Transaction.class),
                BudgetAlertService.class.getMethod("notifyAfterUpdate", Transaction.class, BudgetAlertService.SpendingSnapshot.class));

        for (java.lang.reflect.Method method : entryPoints) {
            Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
            assertNotNull(transactional, method.getName() + " must be @Transactional");
            assertFalse(transactional.readOnly(), method.getName() + " writes, so it cannot be read-only");
        }
    }

    @Test
    void onlyTheTwoBudgetLevelsHaveAThreshold_andTheyAreWalkedFromTheLowest() {
        assertEquals(List.of(NotificationType.BUDGET_WARNING, NotificationType.BUDGET_EXCEEDED), NotificationType.budgetLevels());
        assertEquals(80, NotificationType.BUDGET_WARNING.getThresholdPercent());
        assertEquals(100, NotificationType.BUDGET_EXCEEDED.getThresholdPercent());
        assertNull(NotificationType.TRANSACTION_ALERT.getThresholdPercent());
        assertNull(NotificationType.RECEIPT_PROCESSED.getThresholdPercent());
        assertFalse(NotificationType.TRANSACTION_ALERT.isBudgetLevel());
        assertFalse(NotificationType.RECEIPT_PROCESSED.isBudgetLevel());
    }
}
