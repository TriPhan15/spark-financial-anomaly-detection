package vn.edu.huit.finance.core.Repository;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import vn.edu.huit.finance.core.Entity.Budget;
import vn.edu.huit.finance.core.Entity.BudgetPeriodType;
import vn.edu.huit.finance.core.Entity.Category;
import vn.edu.huit.finance.core.Entity.User;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the real JPA mapping and queries on an in-memory database (H2; the project has no test PostgreSQL), so N+1
 * behaviour, the unique constraint and the ownership scoping are checked for real instead of being assumed.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
class BudgetRepositoryTest {

    private static final LocalDate SEPTEMBER = LocalDate.of(2026, 9, 1);

    @Autowired private BudgetRepository budgetRepository;
    @Autowired private TestEntityManager entityManager;

    private int userSeed;
    private User alice;
    private User bob;
    private Category food;
    private Category transport;

    @BeforeEach
    void setUp() {
        alice = persistUser("alice");
        bob = persistUser("bob");
        food = persistCategory("Ăn uống");
        transport = persistCategory("Di chuyển");
    }

    private User persistUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("hash");
        user.setEmail(username + "@example.com");
        user.setPhone(String.format("09%08d", ++userSeed));
        return entityManager.persistAndFlush(user);
    }

    private Category persistCategory(String name) {
        Category category = new Category();
        category.setName(name);
        category.setType("EXPENSE");
        return entityManager.persistAndFlush(category);
    }

    private Budget newBudget(User owner, Category category, BudgetPeriodType periodType, LocalDate startDate) {
        Budget budget = new Budget();
        budget.setUser(owner);
        budget.setCategory(category);
        budget.setAmount(new BigDecimal("1500000"));
        budget.setPeriodType(periodType);
        budget.setStartDate(startDate);
        budget.setEndDate(startDate.plusDays(6));
        return budget;
    }

    private Budget persistBudget(User owner, Category category, BudgetPeriodType periodType, LocalDate startDate) {
        return entityManager.persistAndFlush(newBudget(owner, category, periodType, startDate));
    }

    private Statistics statistics() {
        return entityManager.getEntityManager().getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    /** Empties the first-level cache, so what follows really goes to the database. */
    private void forgetLoadedEntities() {
        entityManager.flush();
        entityManager.clear();
        statistics().clear();
    }

    private void seedBudgetsForListing() {
        persistBudget(alice, food, BudgetPeriodType.MONTH, SEPTEMBER.minusMonths(1));
        persistBudget(alice, transport, BudgetPeriodType.WEEK, SEPTEMBER.plusDays(13));
        persistBudget(alice, null, BudgetPeriodType.MONTH, SEPTEMBER);
        persistBudget(bob, food, BudgetPeriodType.MONTH, SEPTEMBER);
    }

    @Test
    void findAllWithCategoryByUserId_listsOnlyThatUsersBudgets_newestFirst_withCategoriesInASingleQuery() {
        seedBudgetsForListing();
        forgetLoadedEntities();

        List<Budget> budgets = budgetRepository.findAllWithCategoryByUserId(alice.getId());
        // What the DTO mapping does: read the category of every budget.
        List<String> categoryNames = budgets.stream()
                .map(b -> b.getCategory() == null ? "(all)" : b.getCategory().getName())
                .toList();

        assertEquals(List.of("Di chuyển", "(all)", "Ăn uống"), categoryNames);
        assertEquals(1, statistics().getPrepareStatementCount(), "listing must not issue a query per budget");
    }

    @Test
    void withoutFetchingTheCategory_readingCategoriesCostsAQueryPerCategory() {
        // Control for the test above: proves the statement counter really notices an N+1.
        seedBudgetsForListing();
        forgetLoadedEntities();

        budgetRepository.findAll().forEach(b -> {
            if (b.getCategory() != null) {
                b.getCategory().getName();
            }
        });

        assertTrue(statistics().getPrepareStatementCount() > 1, "expected extra category queries");
    }

    @Test
    void findWithCategoryByIdAndUserId_returnsTheBudgetToItsOwner_andNothingToAnotherUser() {
        Budget budget = persistBudget(alice, food, BudgetPeriodType.MONTH, SEPTEMBER);
        forgetLoadedEntities();

        assertTrue(budgetRepository.findWithCategoryByIdAndUserId(budget.getId(), alice.getId()).isPresent());
        assertTrue(budgetRepository.findWithCategoryByIdAndUserId(budget.getId(), bob.getId()).isEmpty());
        assertTrue(budgetRepository.findWithCategoryByIdAndUserId(budget.getId() + 1000, alice.getId()).isEmpty());
    }

    @Test
    void findWithCategoryByIdAndUserId_loadsTheCategoryWithoutAnExtraQuery() {
        Budget budget = persistBudget(alice, food, BudgetPeriodType.MONTH, SEPTEMBER);
        forgetLoadedEntities();

        Budget found = budgetRepository.findWithCategoryByIdAndUserId(budget.getId(), alice.getId()).orElseThrow();
        found.getCategory().getName();

        assertEquals(1, statistics().getPrepareStatementCount());
    }

    @Test
    void aSecondBudgetForTheSameUserCategoryPeriodAndStartDate_isRejected() {
        persistBudget(alice, food, BudgetPeriodType.MONTH, SEPTEMBER);

        assertThrows(DataIntegrityViolationException.class,
                () -> budgetRepository.saveAndFlush(newBudget(alice, food, BudgetPeriodType.MONTH, SEPTEMBER)));
    }

    @Test
    void aSecondAllCategoriesBudgetForTheSamePeriod_isRejectedToo() {
        // SQL alone would let two NULL categories through; the category_key column is what closes that gap.
        persistBudget(alice, null, BudgetPeriodType.MONTH, SEPTEMBER);

        assertThrows(DataIntegrityViolationException.class,
                () -> budgetRepository.saveAndFlush(newBudget(alice, null, BudgetPeriodType.MONTH, SEPTEMBER)));
    }

    @Test
    void budgetsThatDifferInUserCategoryPeriodTypeOrStartDate_areAllowed() {
        persistBudget(alice, food, BudgetPeriodType.MONTH, SEPTEMBER);

        persistBudget(bob, food, BudgetPeriodType.MONTH, SEPTEMBER);                    // other user
        persistBudget(alice, transport, BudgetPeriodType.MONTH, SEPTEMBER);             // other category
        persistBudget(alice, null, BudgetPeriodType.MONTH, SEPTEMBER);                  // all categories
        persistBudget(alice, food, BudgetPeriodType.WEEK, SEPTEMBER);                   // other period type
        persistBudget(alice, food, BudgetPeriodType.MONTH, SEPTEMBER.plusMonths(1));    // other start date

        assertEquals(6, budgetRepository.count());
    }

    @Test
    void existsQueries_matchOnUserCategoryPeriodTypeAndStartDate_includingTheAllCategoriesKey() {
        Budget forFood = persistBudget(alice, food, BudgetPeriodType.MONTH, SEPTEMBER);
        persistBudget(alice, null, BudgetPeriodType.WEEK, SEPTEMBER);

        assertTrue(budgetRepository.existsDuplicate(
                alice.getId(), food.getId(), BudgetPeriodType.MONTH, SEPTEMBER));
        assertTrue(budgetRepository.existsDuplicate(
                alice.getId(), Budget.categoryKeyOf(null), BudgetPeriodType.WEEK, SEPTEMBER));
        assertFalse(budgetRepository.existsDuplicate(
                alice.getId(), transport.getId(), BudgetPeriodType.MONTH, SEPTEMBER));
        assertFalse(budgetRepository.existsDuplicate(
                bob.getId(), food.getId(), BudgetPeriodType.MONTH, SEPTEMBER));
        // Same user and category, but a different start date or period type: not a duplicate.
        assertFalse(budgetRepository.existsDuplicate(
                alice.getId(), food.getId(), BudgetPeriodType.MONTH, SEPTEMBER.plusMonths(1)));
        assertFalse(budgetRepository.existsDuplicate(
                alice.getId(), food.getId(), BudgetPeriodType.WEEK, SEPTEMBER));

        // "Excluding id" is what lets a budget be updated without clashing with itself.
        assertFalse(budgetRepository.existsDuplicateExcluding(
                alice.getId(), food.getId(), BudgetPeriodType.MONTH, SEPTEMBER, forFood.getId()));
        assertTrue(budgetRepository.existsDuplicateExcluding(
                alice.getId(), food.getId(), BudgetPeriodType.MONTH, SEPTEMBER, forFood.getId() + 1000));
    }

    @Test
    void periodTypeIsStoredAsItsName_notItsOrdinal() {
        persistBudget(alice, food, BudgetPeriodType.MONTH, SEPTEMBER);
        entityManager.flush();

        Object stored = entityManager.getEntityManager()
                .createNativeQuery("select period_type from budgets").getSingleResult();

        assertEquals("MONTH", stored);
    }

    @Test
    void changingTheCategory_keepsCategoryKeyInStep_soTheUniqueConstraintStillHolds() {
        Budget budget = persistBudget(alice, food, BudgetPeriodType.MONTH, SEPTEMBER);
        assertEquals(food.getId(), budget.getCategoryKey());

        budget.setCategory(null);
        assertEquals(Budget.ALL_CATEGORIES_KEY, budget.getCategoryKey());

        budget.setCategory(transport);
        assertEquals(transport.getId(), budget.getCategoryKey());
    }

    // ------------------------------------------------------------------------------------ budgets in force on a day

    private static List<Long> categoryKeysOf(Category category) {
        return List.of(Budget.ALL_CATEGORIES_KEY, category.getId());
    }

    private List<Long> idsFound(User owner, LocalDate day, Category category) {
        return budgetRepository.findCoveringDate(owner.getId(), day, categoryKeysOf(category)).stream()
                .map(Budget::getId)
                .sorted()
                .toList();
    }

    @Test
    void findCoveringDate_returnsTheBudgetsOfTheCategoryAndOfEveryCategory_notThoseOfOtherCategories() {
        Budget forFood = persistBudget(alice, food, BudgetPeriodType.WEEK, SEPTEMBER);
        Budget forEverything = persistBudget(alice, null, BudgetPeriodType.WEEK, SEPTEMBER);
        persistBudget(alice, transport, BudgetPeriodType.WEEK, SEPTEMBER);
        forgetLoadedEntities();

        assertEquals(List.of(forFood.getId(), forEverything.getId()), idsFound(alice, SEPTEMBER.plusDays(2), food));
    }

    @Test
    void findCoveringDate_includesBothEndDaysAndNothingOutsideThePeriod() {
        Budget week = persistBudget(alice, food, BudgetPeriodType.WEEK, SEPTEMBER); // September 1 to September 7
        forgetLoadedEntities();

        assertEquals(List.of(week.getId()), idsFound(alice, SEPTEMBER, food));
        assertEquals(List.of(week.getId()), idsFound(alice, SEPTEMBER.plusDays(6), food));
        assertTrue(idsFound(alice, SEPTEMBER.minusDays(1), food).isEmpty());
        assertTrue(idsFound(alice, SEPTEMBER.plusDays(7), food).isEmpty());
    }

    @Test
    void findCoveringDate_neverReturnsTheBudgetOfAnotherUser() {
        persistBudget(bob, food, BudgetPeriodType.WEEK, SEPTEMBER);
        persistBudget(bob, null, BudgetPeriodType.WEEK, SEPTEMBER);
        forgetLoadedEntities();

        assertTrue(idsFound(alice, SEPTEMBER, food).isEmpty());
    }

    @Test
    void findCoveringDate_loadsTheCategoriesInTheSameQuery() {
        persistBudget(alice, food, BudgetPeriodType.WEEK, SEPTEMBER);
        persistBudget(alice, null, BudgetPeriodType.WEEK, SEPTEMBER);
        forgetLoadedEntities();

        List<Budget> found = budgetRepository.findCoveringDate(alice.getId(), SEPTEMBER, categoryKeysOf(food));
        // What the alert message does: read the category name of every budget.
        found.forEach(b -> {
            if (b.getCategory() != null) {
                b.getCategory().getName();
            }
        });

        assertEquals(2, found.size());
        assertEquals(1, statistics().getPrepareStatementCount(), "reading the categories must not cost a query per budget");
    }
}
