package vn.edu.huit.finance.core.Repository;

import jakarta.persistence.PersistenceException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import vn.edu.huit.finance.core.Entity.Category;
import vn.edu.huit.finance.core.Entity.Receipt;
import vn.edu.huit.finance.core.Entity.Transaction;
import vn.edu.huit.finance.core.Entity.User;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real mapping and queries on H2, so the "one statement" promise of findDetailedById is measured, not assumed. */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
class TransactionRepositoryTest {

    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TestEntityManager entityManager;

    private Transaction withReceipt;
    private Transaction withoutReceipt;

    @BeforeEach
    void setUp() {
        User alice = new User();
        alice.setUsername("alice");
        alice.setPassword("hash");
        alice.setEmail("alice@example.com");
        alice.setPhone("0900000001");
        entityManager.persist(alice);

        Category food = new Category();
        food.setName("Ăn uống");
        food.setType("EXPENSE");
        entityManager.persist(food);

        Receipt receipt = new Receipt();
        receipt.setVendorName("Highlands");
        receipt.setImageUrl("https://img.example.com/1.jpg");

        withReceipt = newTransaction(alice, food);
        withReceipt.setReceipt(receipt);
        withoutReceipt = newTransaction(alice, food);
        entityManager.persist(withReceipt);
        entityManager.persist(withoutReceipt);
        entityManager.flush();
    }

    private static Transaction newTransaction(User owner, Category category) {
        Transaction transaction = new Transaction();
        transaction.setUser(owner);
        transaction.setCategory(category);
        transaction.setCategoryName(category.getName());
        transaction.setAmount(new BigDecimal("100000"));
        transaction.setExpense(true);
        transaction.setTransactionDate(LocalDateTime.of(2026, 9, 1, 12, 0));
        return transaction;
    }

    private Statistics statistics() {
        return entityManager.getEntityManager().getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    private void forgetLoadedEntities() {
        entityManager.flush();
        entityManager.clear();
        statistics().clear();
    }

    @Test
    void findDetailedByIdLoadsTheOwnerTheCategoryAndTheReceiptInOneStatement() {
        forgetLoadedEntities();

        Transaction found = transactionRepository.findDetailedById(withReceipt.getId()).orElseThrow();
        String owner = found.getUser().getUsername();
        String category = found.getCategory().getName();
        String image = found.getReceipt().getImageUrl();

        assertEquals("alice", owner);
        assertEquals("Ăn uống", category);
        assertEquals("https://img.example.com/1.jpg", image);
        assertEquals(1, statistics().getPrepareStatementCount(), "reading the associations must not add queries");
    }

    @Test
    void aTransactionWithoutAReceiptIsStillFound() {
        forgetLoadedEntities();

        Transaction found = transactionRepository.findDetailedById(withoutReceipt.getId()).orElseThrow();

        assertNull(found.getReceipt());
        assertEquals(1, statistics().getPrepareStatementCount());
    }

    @Test
    void aMissingIdIsEmpty() {
        assertTrue(transactionRepository.findDetailedById(-1L).isEmpty());
    }

    @Test
    void controlPlainFindByIdReallyNeedsMoreStatementsSoTheCounterIsTrustworthy() {
        forgetLoadedEntities();

        Transaction found = transactionRepository.findById(withReceipt.getId()).orElseThrow();
        found.getUser().getUsername();
        found.getCategory().getName();
        found.getReceipt().getImageUrl();

        assertTrue(statistics().getPrepareStatementCount() > 1, "lazy associations cost one statement each");
    }

    // ------------------------------------------------------------------------------------ what a user spent in a period

    private static final LocalDateTime SEPTEMBER_START = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime OCTOBER_START = LocalDateTime.of(2026, 10, 1, 0, 0);

    private Transaction persistTransaction(User owner, Category category, String amount, boolean expense, LocalDateTime date) {
        Transaction transaction = newTransaction(owner, category);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setExpense(expense);
        transaction.setTransactionDate(date);
        return entityManager.persistAndFlush(transaction);
    }

    private Category persistCategory(String name) {
        Category category = new Category();
        category.setName(name);
        category.setType("EXPENSE");
        return entityManager.persistAndFlush(category);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual, "expected " + expected + " but there was no sum");
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    /** The two expenses of the fixture (100,000 each, September 1) plus what one test adds around the edges of September. */
    private void persistNoiseAroundSeptember(User alice, Category food, Category transport) {
        User bob = new User();
        bob.setUsername("bob");
        bob.setPassword("hash");
        bob.setEmail("bob@example.com");
        bob.setPhone("0900000002");
        entityManager.persistAndFlush(bob);

        persistTransaction(alice, transport, "50000", true, OCTOBER_START.minusSeconds(1));   // last second: counted
        persistTransaction(alice, food, "70000", false, SEPTEMBER_START.plusDays(1));         // income: never counted
        persistTransaction(alice, food, "30000", true, OCTOBER_START);                        // the end is exclusive
        persistTransaction(alice, food, "20000", true, SEPTEMBER_START.minusSeconds(1));      // before the start
        persistTransaction(bob, food, "999999", true, SEPTEMBER_START.plusDays(1));           // somebody else's
    }

    @Test
    void sumExpensesBetween_addsUpOnlyTheExpensesOfThatUserInsideTheRange() {
        User alice = withReceipt.getUser();
        persistNoiseAroundSeptember(alice, withReceipt.getCategory(), persistCategory("Di chuyển"));

        BigDecimal spent = transactionRepository.sumExpensesBetween(alice.getId(), SEPTEMBER_START, OCTOBER_START);

        assertMoney("250000", spent); // 2 x 100,000 of the fixture + 50,000 on the last second of September
    }

    @Test
    void sumExpensesInCategoryBetween_isRestrictedToOneCategory() {
        User alice = withReceipt.getUser();
        Category food = withReceipt.getCategory();
        Category transport = persistCategory("Di chuyển");
        persistNoiseAroundSeptember(alice, food, transport);

        assertMoney("200000", transactionRepository.sumExpensesInCategoryBetween(
                alice.getId(), food.getId(), SEPTEMBER_START, OCTOBER_START));
        assertMoney("50000", transactionRepository.sumExpensesInCategoryBetween(
                alice.getId(), transport.getId(), SEPTEMBER_START, OCTOBER_START));
    }

    @Test
    void theSums_areNullWhenNothingMatches_soTheCallerHasToTreatNullAsZero() {
        User alice = withReceipt.getUser();
        Category food = withReceipt.getCategory();

        assertNull(transactionRepository.sumExpensesBetween(alice.getId(), OCTOBER_START, OCTOBER_START.plusDays(31)));
        assertNull(transactionRepository.sumExpensesInCategoryBetween(
                alice.getId(), food.getId(), OCTOBER_START, OCTOBER_START.plusDays(31)));
    }

    // ------------------------------------------------------------------- the client id of a checkout (idempotency key)

    private Transaction persistWithClientId(User owner, String clientId) {
        Transaction transaction = newTransaction(owner, withReceipt.getCategory());
        transaction.setClientId(clientId);
        return entityManager.persistAndFlush(transaction);
    }

    private User persistBob() {
        User bob = new User();
        bob.setUsername("bob");
        bob.setPassword("hash");
        bob.setEmail("bob@example.com");
        bob.setPhone("0900000002");
        return entityManager.persistAndFlush(bob);
    }

    @Test
    void findByUserIdAndClientId_findsTheTransactionTheUserCreatedWithThatId() {
        User alice = withReceipt.getUser();
        Transaction saved = persistWithClientId(alice, "attempt-1");
        forgetLoadedEntities();

        Transaction found = transactionRepository.findByUserIdAndClientId(alice.getId(), "attempt-1").orElseThrow();

        assertEquals(saved.getId(), found.getId());
        assertEquals("attempt-1", found.getClientId());
        assertEquals(1, statistics().getPrepareStatementCount(), "answering a repeated checkout must not fetch anything else");
    }

    @Test
    void findByUserIdAndClientId_neverReturnsTheTransactionOfAnotherUser() {
        persistWithClientId(withReceipt.getUser(), "attempt-1");
        User bob = persistBob();

        assertTrue(transactionRepository.findByUserIdAndClientId(bob.getId(), "attempt-1").isEmpty());
    }

    @Test
    void findByUserIdAndClientId_isEmptyForAnUnknownIdAndTheTransactionsThatHaveNone() {
        User alice = withReceipt.getUser(); // the two transactions of the fixture carry no client id

        assertTrue(transactionRepository.findByUserIdAndClientId(alice.getId(), "never-sent").isEmpty());
    }

    @Test
    void theClientIdIsMatchedExactly() {
        User alice = withReceipt.getUser();
        persistWithClientId(alice, "Attempt-1");

        assertTrue(transactionRepository.findByUserIdAndClientId(alice.getId(), "attempt-1").isEmpty());
        assertTrue(transactionRepository.findByUserIdAndClientId(alice.getId(), "Attempt-1").isPresent());
    }

    @Test
    void theDatabaseRefusesASecondTransactionOfTheSameUserWithTheSameClientId() {
        User alice = withReceipt.getUser();
        persistWithClientId(alice, "attempt-1");

        assertThrows(PersistenceException.class, () -> persistWithClientId(alice, "attempt-1"));
    }

    @Test
    void theSameClientIdOfTwoDifferentUsersIsAllowed() {
        Transaction alices = persistWithClientId(withReceipt.getUser(), "shared-id");
        Transaction bobs = persistWithClientId(persistBob(), "shared-id");

        assertNotEquals(alices.getId(), bobs.getId());
        Long foundForBob = transactionRepository.findByUserIdAndClientId(bobs.getUser().getId(), "shared-id").orElseThrow().getId();
        assertEquals(bobs.getId(), foundForBob);
    }

    @Test
    void transactionsWithoutAClientIdNeverCollideWithEachOther() {
        User alice = withReceipt.getUser(); // already has two of them from the fixture

        persistWithClientId(alice, null);
        persistWithClientId(alice, null);

        assertEquals(4, transactionRepository.findByUserIdOrderByTransactionDateDesc(alice.getId()).size());
    }
}
