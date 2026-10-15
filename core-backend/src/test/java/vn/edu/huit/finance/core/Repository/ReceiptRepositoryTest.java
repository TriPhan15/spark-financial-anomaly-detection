package vn.edu.huit.finance.core.Repository;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.util.ReflectionTestUtils;
import vn.edu.huit.finance.core.Entity.Category;
import vn.edu.huit.finance.core.Entity.Receipt;
import vn.edu.huit.finance.core.Entity.ReceiptErrorCode;
import vn.edu.huit.finance.core.Entity.ReceiptProcessingAttempt;
import vn.edu.huit.finance.core.Entity.Transaction;
import vn.edu.huit.finance.core.Entity.User;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the real mapping and queries on H2, so the "one statement" promise of the finders, the row count of the retry claim
 * and the fact that no save of the entity writes the legacy column of the answer are measured, not assumed.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
class ReceiptRepositoryTest {

    private static final String FIRST_ANSWER = "{\"seller\": {\"seller_name\": \"Highlands\"}}";

    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private TestEntityManager entityManager;

    private Receipt carried;
    private Receipt orphan;
    private Transaction carrier;
    private Transaction typedIn;

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

        carried = new Receipt();
        carried.setVendorName("Highlands");
        carried.setStatus("FAILED");
        carried.setErrorCode("AI_NO_TOTAL");
        carried.addFirstAttempt(ReceiptProcessingAttempt.failed(carried, ReceiptProcessingAttempt.FIRST_ATTEMPT_NUMBER,
                FIRST_ANSWER, null, ReceiptErrorCode.AI_NO_TOTAL, LocalDateTime.of(2026, 9, 1, 12, 0)));

        carrier = transactionOf(alice, food);
        carrier.setReceipt(carried);
        entityManager.persist(carrier);

        typedIn = transactionOf(alice, food);
        entityManager.persist(typedIn);

        orphan = new Receipt();
        orphan.setStatus("PENDING");
        entityManager.persist(orphan);
        entityManager.flush();
    }

    private static Transaction transactionOf(User owner, Category category) {
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

    /** The row as the database holds it now, not a copy the persistence context still has. */
    private Receipt inDatabase(Receipt receipt) {
        entityManager.flush();
        entityManager.clear();
        return receiptRepository.findById(receipt.getId()).orElseThrow();
    }

    /** What the AI answered for the receipt, reading after reading, as the attempts table holds it. */
    private List<String> answersOf(Receipt receipt) {
        entityManager.flush();
        return entityManager.getEntityManager()
                .createQuery("SELECT a.aiRawData FROM ReceiptProcessingAttempt a WHERE a.receipt.id = :id ORDER BY a.attemptNumber",
                        String.class)
                .setParameter("id", receipt.getId())
                .getResultList();
    }

    /** The legacy column as the row holds it, read with SQL past the entity. */
    private Object legacyAnswerInDatabase(Receipt receipt) {
        entityManager.flush();
        return entityManager.getEntityManager()
                .createNativeQuery("select ai_raw_data from receipts where id = :id")
                .setParameter("id", receipt.getId())
                .getSingleResult();
    }

    /** Puts an answer into the legacy column the way the build before the attempts did, past the entity. */
    private void storeLegacyAnswer(Receipt receipt, String answer) {
        entityManager.getEntityManager()
                .createNativeQuery("update receipts set ai_raw_data = :answer where id = :id")
                .setParameter("answer", answer)
                .setParameter("id", receipt.getId())
                .executeUpdate();
        entityManager.clear();
    }

    /** Writes a status the way a hand-written row or an earlier version of the code left it, without going through the lifecycle. */
    private void storeStatus(Receipt receipt, String status) {
        entityManager.getEntityManager()
                .createNativeQuery("update receipts set status = :status where id = :id")
                .setParameter("status", status)
                .setParameter("id", receipt.getId())
                .executeUpdate();
        entityManager.clear();
    }

    // ------------------------------------------------------------------------------------------------------- finders

    @Test
    void findDetailedByIdLoadsTheCarryingTransactionAndItsOwnerInOneStatement() {
        forgetLoadedEntities();

        Receipt found = receiptRepository.findDetailedById(carried.getId()).orElseThrow();
        String owner = found.getTransaction().getUser().getUsername();

        assertEquals("alice", owner);
        assertEquals(1, statistics().getPrepareStatementCount(), "checking the owner must not add queries");
    }

    @Test
    void aReceiptNoTransactionCarriesIsStillFoundWithoutOne() {
        forgetLoadedEntities();

        Receipt found = receiptRepository.findDetailedById(orphan.getId()).orElseThrow();

        assertNull(found.getTransaction());
        assertEquals(1, statistics().getPrepareStatementCount());
    }

    @Test
    void aMissingIdIsEmpty() {
        assertTrue(receiptRepository.findDetailedById(-1L).isEmpty());
    }

    @Test
    void controlPlainFindByIdReallyNeedsMoreStatementsSoTheCounterIsTrustworthy() {
        forgetLoadedEntities();

        Receipt found = receiptRepository.findById(carried.getId()).orElseThrow();
        found.getTransaction().getUser().getUsername();

        assertTrue(statistics().getPrepareStatementCount() > 1, "the lazy owner costs a statement of its own");
    }

    @Test
    void theReceiptOfATransactionIsFoundWithItsTransactionAndTheOwnerInOneStatement() {
        forgetLoadedEntities();

        Receipt found = receiptRepository.findDetailedByTransactionId(carrier.getId()).orElseThrow();
        String owner = found.getTransaction().getUser().getUsername();

        assertEquals(carried.getId(), found.getId());
        assertEquals(carrier.getId(), found.getTransaction().getId());
        assertEquals("alice", owner);
        assertEquals(1, statistics().getPrepareStatementCount(), "checking the owner must not add queries");
    }

    @Test
    void aTransactionThatCarriesNoReceiptOrDoesNotExistHasNone() {
        assertTrue(receiptRepository.findDetailedByTransactionId(typedIn.getId()).isEmpty());
        assertTrue(receiptRepository.findDetailedByTransactionId(-1L).isEmpty());
    }

    // --------------------------------------------------------------------- version, and the legacy column of the answer

    @Test
    void aNewReceiptStartsAtVersionZeroAndEveryUpdateOfTheEntityRaisesIt() {
        assertEquals(0L, inDatabase(carried).getVersion());

        Receipt managed = receiptRepository.findById(carried.getId()).orElseThrow();
        managed.setVendorName("Highlands Coffee");

        assertEquals(1L, inDatabase(carried).getVersion());
    }

    @Test
    void theReasonOfAFailureIsStoredWithTheReceiptAndTheAnswerWithItsFirstAttempt() {
        Receipt stored = inDatabase(carried);

        assertEquals("AI_NO_TOTAL", stored.getErrorCode());
        assertNull(stored.getAiRawData(), "the legacy column is not where an answer is kept any more");
        assertEquals(List.of(FIRST_ANSWER), answersOf(carried));
    }

    @Test
    void neitherAnInsertNorAnUpdateOfTheEntityWritesTheLegacyColumn() {
        Receipt fresh = new Receipt();
        ReflectionTestUtils.setField(fresh, "aiRawData", "{\"written\": \"at insert\"}");
        entityManager.persist(fresh);
        assertNull(legacyAnswerInDatabase(fresh), "the insert does not name the column");

        storeLegacyAnswer(carried, FIRST_ANSWER);
        Receipt managed = receiptRepository.findById(carried.getId()).orElseThrow();
        ReflectionTestUtils.setField(managed, "aiRawData", "{\"tampered\": true}");
        managed.setVendorName("Highlands Coffee");

        Receipt stored = inDatabase(carried);
        assertEquals("Highlands Coffee", stored.getVendorName(), "the update itself was written");
        assertEquals(FIRST_ANSWER, stored.getAiRawData(), "and the legacy answer is still the one the row held");
    }

    // ------------------------------------------------------------------------------ the claim of a retry: FAILED, locked

    @Test
    void aFailedReceiptIsClaimedInOneStatementThatRaisesItsVersionAndChangesNothingElse() {
        forgetLoadedEntities();

        int claimed = receiptRepository.claimFailedForRetry(carried.getId(), 0L);

        assertEquals(1, claimed);
        assertEquals(1, statistics().getPrepareStatementCount(), "the check and the change are one statement");
        Receipt stored = inDatabase(carried);
        assertEquals("FAILED", stored.getStatus(), "no PROCESSING: the status changes when the reading is recorded");
        assertEquals("AI_NO_TOTAL", stored.getErrorCode());
        assertEquals(1L, stored.getVersion(), "the claim raises the version like any other update of the row");
        assertEquals("Highlands", stored.getVendorName(), "the final fields wait for the reading");
        assertEquals(List.of(FIRST_ANSWER), answersOf(carried), "and no attempt is touched");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, 1L, 7L})
    void aClaimMadeFromAnotherVersionThanTheCurrentOneChangesNothing(long staleVersion) {
        int claimed = receiptRepository.claimFailedForRetry(carried.getId(), staleVersion);

        assertEquals(0, claimed);
        Receipt stored = inDatabase(carried);
        assertEquals("FAILED", stored.getStatus());
        assertEquals(0L, stored.getVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSING", "COMPLETED_RAW", "SUCCESS", "EDITED", "WAITING_FOR_REVIEW", "APPROVED", "CANCELED"})
    void aReceiptThatIsNotFailedIsNeverClaimed(String status) {
        storeStatus(carried, status);

        int claimed = receiptRepository.claimFailedForRetry(carried.getId(), 0L);

        assertEquals(0, claimed);
        Receipt stored = inDatabase(carried);
        assertEquals(status, stored.getStatus());
        assertEquals(0L, stored.getVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "Failed", " FAILED "})
    void aFailedStatusThatWasWrittenByHandIsClaimedToo(String status) {
        storeStatus(carried, status);

        assertEquals(1, receiptRepository.claimFailedForRetry(carried.getId(), 0L));

        Receipt stored = inDatabase(carried);
        assertEquals(status, stored.getStatus(), "the claim leaves the status as it found it");
        assertEquals(1L, stored.getVersion());
    }

    @Test
    void ofTwoClaimsMadeFromTheSameVersionOnlyTheFirstChangesTheRow() {
        int first = receiptRepository.claimFailedForRetry(carried.getId(), 0L);
        int second = receiptRepository.claimFailedForRetry(carried.getId(), 0L);

        assertEquals(1, first);
        assertEquals(0, second);
        assertEquals(1L, inDatabase(carried).getVersion());
    }

    @Test
    void aClaimOfAReceiptThatDoesNotExistChangesNothing() {
        assertEquals(0, receiptRepository.claimFailedForRetry(-1L, 0L));
    }

    @Test
    void theClaimLeavesNoStaleCopyOfTheReceiptBehind() {
        Receipt before = receiptRepository.findById(carried.getId()).orElseThrow();
        assertEquals(0L, before.getVersion());

        receiptRepository.claimFailedForRetry(carried.getId(), 0L);

        // Without the clear the persistence context would hand the old copy out again, at its old version.
        Receipt after = receiptRepository.findById(carried.getId()).orElseThrow();
        assertNotSame(before, after);
        assertEquals(1L, after.getVersion());
    }
}
