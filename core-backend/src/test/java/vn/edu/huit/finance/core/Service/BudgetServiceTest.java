package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.BudgetRequestDto;
import vn.edu.huit.finance.core.Dto.BudgetResponseDto;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.Budget;
import vn.edu.huit.finance.core.Entity.BudgetPeriodType;
import vn.edu.huit.finance.core.Entity.Category;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.BudgetNotFoundException;
import vn.edu.huit.finance.core.Exception.DuplicateBudgetException;
import vn.edu.huit.finance.core.Exception.InvalidCategoryException;
import vn.edu.huit.finance.core.Exception.UserNotFoundException;
import vn.edu.huit.finance.core.Repository.BudgetRepository;
import vn.edu.huit.finance.core.Repository.CategoryRepository;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

    private static final Long ALICE_ID = 1L;
    private static final Long FOOD_ID = 10L;
    private static final Long BUDGET_ID = 7L;
    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    @Mock private BudgetRepository budgetRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private BudgetService service;

    private final User alice = user(ALICE_ID);
    private final Category food = category(FOOD_ID, "Ăn uống");

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private static Category category(Long id, String name) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        return category;
    }

    private static Budget budget(Long id, User owner, Category category) {
        Budget budget = new Budget();
        budget.setId(id);
        budget.setUser(owner);
        budget.setCategory(category);
        budget.setAmount(new BigDecimal("1000000"));
        budget.setPeriodType(BudgetPeriodType.WEEK);
        budget.setStartDate(START.minusDays(20));
        budget.setEndDate(START.minusDays(14));
        return budget;
    }

    private static BudgetRequestDto request(Long categoryId) {
        BudgetRequestDto request = new BudgetRequestDto();
        request.setCategoryId(categoryId);
        request.setAmount(new BigDecimal("2000000"));
        request.setPeriodType(BudgetPeriodType.MONTH);
        request.setStartDate(START);
        request.setEndDate(END);
        return request;
    }

    private void signedInAsAlice() {
        when(currentUserProvider.getCurrentUser()).thenReturn(alice);
    }

    /** The one row of a change: by the caller, about the budget, naming the period and the category, never the amount. */
    private AuditEntry recorded(AuditAction action) {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        assertEquals(action, entry.getValue().action());
        assertEquals(AuditActor.of(alice), entry.getValue().actor());
        assertEquals(BUDGET_ID, entry.getValue().targetId());
        assertFalse(entry.getValue().description().contains("2000000"));
        assertFalse(entry.getValue().description().contains("1000000"));
        return entry.getValue();
    }

    // ---- reads ----

    @Test
    void getMyBudgets_asksForTheCallersBudgetsOnly_andMapsThem() {
        signedInAsAlice();
        when(budgetRepository.findAllWithCategoryByUserId(ALICE_ID))
                .thenReturn(List.of(budget(1L, alice, food), budget(2L, alice, null)));

        List<BudgetResponseDto> result = service.getMyBudgets();

        assertEquals(2, result.size());
        assertEquals(FOOD_ID, result.get(0).getCategoryId());
        assertEquals("Ăn uống", result.get(0).getCategoryName());
        assertNull(result.get(1).getCategoryId());
        assertNull(result.get(1).getCategoryName());
        verify(budgetRepository).findAllWithCategoryByUserId(ALICE_ID);
        verifyNoMoreInteractions(budgetRepository);
    }

    @Test
    void getMyBudget_returnsTheBudgetOfTheCaller() {
        signedInAsAlice();
        when(budgetRepository.findWithCategoryByIdAndUserId(BUDGET_ID, ALICE_ID))
                .thenReturn(Optional.of(budget(BUDGET_ID, alice, food)));

        BudgetResponseDto result = service.getMyBudget(BUDGET_ID);

        assertEquals(BUDGET_ID, result.getId());
        assertEquals(new BigDecimal("1000000"), result.getAmount());
        assertEquals(BudgetPeriodType.WEEK, result.getPeriodType());
    }

    @Test
    void getMyBudget_throwsNotFound_whenTheBudgetIsMissingOrBelongsToAnotherUser() {
        // The repository is only ever asked about the caller's own budgets, so "someone else's" and "missing" look alike.
        signedInAsAlice();
        when(budgetRepository.findWithCategoryByIdAndUserId(99L, ALICE_ID)).thenReturn(Optional.empty());

        assertThrows(BudgetNotFoundException.class, () -> service.getMyBudget(99L));

        verify(budgetRepository).findWithCategoryByIdAndUserId(99L, ALICE_ID);
        verifyNoMoreInteractions(budgetRepository);
    }

    // ---- create ----

    @Test
    void createBudget_savesTheBudgetForTheCaller_withTheRequestedCategoryAndPeriod() {
        signedInAsAlice();
        when(categoryRepository.findById(FOOD_ID)).thenReturn(Optional.of(food));
        when(budgetRepository.existsDuplicate(ALICE_ID, FOOD_ID, BudgetPeriodType.MONTH, START)).thenReturn(false);
        when(budgetRepository.saveAndFlush(any(Budget.class))).thenAnswer(invocation -> {
            Budget saved = invocation.getArgument(0);
            saved.setId(BUDGET_ID);
            return saved;
        });

        BudgetResponseDto result = service.createBudget(request(FOOD_ID));

        ArgumentCaptor<Budget> saved = ArgumentCaptor.forClass(Budget.class);
        verify(budgetRepository).saveAndFlush(saved.capture());
        assertSame(alice, saved.getValue().getUser());
        assertSame(food, saved.getValue().getCategory());
        assertEquals(new BigDecimal("2000000"), saved.getValue().getAmount());
        assertEquals(BudgetPeriodType.MONTH, saved.getValue().getPeriodType());
        assertEquals(START, saved.getValue().getStartDate());
        assertEquals(END, saved.getValue().getEndDate());
        assertEquals(BUDGET_ID, result.getId());
        assertEquals("Ăn uống", result.getCategoryName());
        assertEquals("Tạo ngân sách kỳ MONTH từ 2026-09-01, danh mục #10",
                recorded(AuditAction.BUDGET_CREATED).description());
    }

    @Test
    void createBudget_withoutACategory_coversEveryCategory_andChecksDuplicatesForThatCase() {
        signedInAsAlice();
        when(budgetRepository.existsDuplicate(ALICE_ID, Budget.ALL_CATEGORIES_KEY, BudgetPeriodType.MONTH, START))
                .thenReturn(false);
        when(budgetRepository.saveAndFlush(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BudgetResponseDto result = service.createBudget(request(null));

        assertNull(result.getCategoryId());
        verifyNoInteractions(categoryRepository);
    }

    @Test
    void createBudget_throwsDuplicate_andSavesNothing_whenTheUserAlreadyHasThatCategoryAndPeriod() {
        signedInAsAlice();
        when(categoryRepository.findById(FOOD_ID)).thenReturn(Optional.of(food));
        when(budgetRepository.existsDuplicate(ALICE_ID, FOOD_ID, BudgetPeriodType.MONTH, START)).thenReturn(true);

        assertThrows(DuplicateBudgetException.class, () -> service.createBudget(request(FOOD_ID)));

        verify(budgetRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void createBudget_throwsInvalidCategory_andSavesNothing_whenTheCategoryDoesNotExist() {
        signedInAsAlice();
        when(categoryRepository.findById(FOOD_ID)).thenReturn(Optional.empty());

        assertThrows(InvalidCategoryException.class, () -> service.createBudget(request(FOOD_ID)));

        verifyNoInteractions(budgetRepository);
    }

    // ---- update ----

    @Test
    void updateBudget_changesTheBudgetOfTheCaller_andExcludesItFromTheDuplicateCheck() {
        signedInAsAlice();
        Budget existing = budget(BUDGET_ID, alice, null);
        when(budgetRepository.findWithCategoryByIdAndUserId(BUDGET_ID, ALICE_ID)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(FOOD_ID)).thenReturn(Optional.of(food));
        when(budgetRepository.existsDuplicateExcluding(ALICE_ID, FOOD_ID, BudgetPeriodType.MONTH, START, BUDGET_ID))
                .thenReturn(false);
        when(budgetRepository.saveAndFlush(existing)).thenReturn(existing);

        BudgetResponseDto result = service.updateBudget(BUDGET_ID, request(FOOD_ID));

        assertSame(alice, existing.getUser());
        assertSame(food, existing.getCategory());
        assertEquals(FOOD_ID, existing.getCategoryKey());
        assertEquals(new BigDecimal("2000000"), existing.getAmount());
        assertEquals(BudgetPeriodType.MONTH, existing.getPeriodType());
        assertEquals(START, existing.getStartDate());
        assertEquals(END, existing.getEndDate());
        assertEquals(BUDGET_ID, result.getId());
        assertEquals("Sửa ngân sách kỳ MONTH từ 2026-09-01, danh mục #10",
                recorded(AuditAction.BUDGET_UPDATED).description());
    }

    @Test
    void updateBudget_throwsNotFound_andChangesNothing_whenTheBudgetIsNotTheCallers() {
        signedInAsAlice();
        when(budgetRepository.findWithCategoryByIdAndUserId(BUDGET_ID, ALICE_ID)).thenReturn(Optional.empty());

        assertThrows(BudgetNotFoundException.class, () -> service.updateBudget(BUDGET_ID, request(FOOD_ID)));

        verify(budgetRepository, never()).saveAndFlush(any());
        verify(budgetRepository, never()).existsDuplicateExcluding(any(), anyLong(), any(), any(), any());
        verifyNoInteractions(categoryRepository, auditLogService);
    }

    @Test
    void updateBudget_throwsDuplicate_andKeepsTheBudget_whenAnotherBudgetHasThatCategoryAndPeriod() {
        signedInAsAlice();
        Budget existing = budget(BUDGET_ID, alice, null);
        when(budgetRepository.findWithCategoryByIdAndUserId(BUDGET_ID, ALICE_ID)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(FOOD_ID)).thenReturn(Optional.of(food));
        when(budgetRepository.existsDuplicateExcluding(ALICE_ID, FOOD_ID, BudgetPeriodType.MONTH, START, BUDGET_ID))
                .thenReturn(true);

        assertThrows(DuplicateBudgetException.class, () -> service.updateBudget(BUDGET_ID, request(FOOD_ID)));

        assertNull(existing.getCategory());
        assertEquals(BudgetPeriodType.WEEK, existing.getPeriodType());
        verify(budgetRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateBudget_throwsInvalidCategory_andKeepsTheBudget_whenTheCategoryDoesNotExist() {
        signedInAsAlice();
        Budget existing = budget(BUDGET_ID, alice, null);
        when(budgetRepository.findWithCategoryByIdAndUserId(BUDGET_ID, ALICE_ID)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(FOOD_ID)).thenReturn(Optional.empty());

        assertThrows(InvalidCategoryException.class, () -> service.updateBudget(BUDGET_ID, request(FOOD_ID)));

        assertEquals(BudgetPeriodType.WEEK, existing.getPeriodType());
        verify(budgetRepository, never()).saveAndFlush(any());
    }

    // ---- delete ----

    @Test
    void deleteBudget_deletesTheBudgetOfTheCaller() {
        signedInAsAlice();
        Budget existing = budget(BUDGET_ID, alice, food);
        when(budgetRepository.findWithCategoryByIdAndUserId(BUDGET_ID, ALICE_ID)).thenReturn(Optional.of(existing));

        service.deleteBudget(BUDGET_ID);

        verify(budgetRepository).delete(existing);
        assertEquals("Xóa ngân sách kỳ WEEK từ 2026-08-12, danh mục #10", recorded(AuditAction.BUDGET_DELETED).description());
    }

    @Test
    void deleteBudget_throwsNotFound_andDeletesNothing_whenTheBudgetIsNotTheCallers() {
        signedInAsAlice();
        when(budgetRepository.findWithCategoryByIdAndUserId(BUDGET_ID, ALICE_ID)).thenReturn(Optional.empty());

        assertThrows(BudgetNotFoundException.class, () -> service.deleteBudget(BUDGET_ID));

        verify(budgetRepository, never()).delete(any());
        verifyNoInteractions(auditLogService);
    }

    // ---- identity and transactions ----

    @Test
    void everyOperation_failsWithoutTouchingAnyData_whenTheCallerCannotBeResolved() {
        when(currentUserProvider.getCurrentUser()).thenThrow(new UserNotFoundException("no caller"));

        assertThrows(UserNotFoundException.class, () -> service.getMyBudgets());
        assertThrows(UserNotFoundException.class, () -> service.getMyBudget(BUDGET_ID));
        assertThrows(UserNotFoundException.class, () -> service.createBudget(request(FOOD_ID)));
        assertThrows(UserNotFoundException.class, () -> service.updateBudget(BUDGET_ID, request(FOOD_ID)));
        assertThrows(UserNotFoundException.class, () -> service.deleteBudget(BUDGET_ID));

        verifyNoInteractions(budgetRepository, categoryRepository, auditLogService);
    }

    @Test
    void writes_runInATransaction_andReadsAreReadOnly() {
        // Mockito bypasses Spring's proxy, so the transaction boundaries can only be checked declaratively.
        for (Method method : BudgetService.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
            assertNotNull(transactional, method.getName() + " must be transactional");
            boolean isRead = method.getName().startsWith("getMy");
            assertEquals(isRead, transactional.readOnly(), method.getName() + " readOnly flag");
        }
    }
}
