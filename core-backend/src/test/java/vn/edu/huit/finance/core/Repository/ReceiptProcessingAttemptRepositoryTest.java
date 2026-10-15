package vn.edu.huit.finance.core.Repository;

import jakarta.persistence.PersistenceException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.util.ReflectionTestUtils;
import vn.edu.huit.finance.core.Dto.ReceiptAttemptResponseDto;
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
 * The attempts table on H2 through the real mapping: how the first reading is saved with its receipt, what the two unique
 * keys refuse, that a saved attempt cannot be changed, that attempts go with their receipt, and what each query costs.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
class ReceiptProcessingAttemptRepositoryTest {

    private static final String FIRST_ANSWER = "{\"seller\": {\"seller_name\": \"Highlands\"}}";
    private static final String SECOND_ANSWER = "{\"seller\": {\"seller_name\": \"Highlands\", \"total_cost_number\": 95000}}";
    private static final LocalDateTime SCANNED_AT = LocalDateTime.of(2026, 9, 1, 12, 0);
    private static final LocalDateTime RETRIED_AT = LocalDateTime.of(2026, 9, 2, 8, 30);

    @Autowired private ReceiptProcessingAttemptRepository attemptRepository;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private TestEntityManager entityManager;

    private User alice;
    private Category food;
    private Transaction carrier;
    private Receipt receipt;
    private Receipt other;

    @BeforeEach
    void setUp() {
        alice = new User();
        alice.setUsername("alice");
        alice.setPassword("hash");
        alice.setEmail("alice@example.com");
        alice.setPhone("0900000001");
        entityManager.persist(alice);

        food = new Category();
        food.setName("Ăn uống");
        food.setType("EXPENSE");
        entityManager.persist(food);

        // The way a checkout saves them: the transaction cascades to its new receipt, the receipt to its first attempt.
        receipt = new Receipt();
        receipt.setStatus("FAILED");
        receipt.addFirstAttempt(ReceiptProcessingAttempt.failed(receipt, ReceiptProcessingAttempt.FIRST_ATTEMPT_NUMBER,
                FIRST_ANSWER, null, ReceiptErrorCode.AI_NO_TOTAL, SCANNED_AT));
        carrier = transactionCarrying(receipt);
        entityManager.persist(carrier);

        other = new Receipt();
        other.setStatus("FAILED");
        entityManager.persist(transactionCarrying(other));
        entityManager.flush();
    }

    private Transaction transactionCarrying(Receipt carried) {
        Transaction transaction = new Transaction();
        transaction.setUser(alice);
        transaction.setCategory(food);
        transaction.setCategoryName(food.getName());
        transaction.setAmount(new BigDecimal("90000"));
        transaction.setExpense(true);
        transaction.setTransactionDate(SCANNED_AT);
        transaction.setReceipt(carried);
        carried.setTransaction(transaction);
        return transaction;
    }

    private ReceiptProcessingAttempt retryOf(Receipt retried, int attemptNumber, String clientId) {
        return ReceiptProcessingAttempt.completed(retried, attemptNumber, SECOND_ANSWER, clientId, RETRIED_AT);
    }

    private Statistics statistics() {
        return entityManager.getEntityManager().getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    private void forgetLoadedEntities() {
        entityManager.flush();
        entityManager.clear();
        statistics().clear();
    }

    private long attemptRows() {
        entityManager.flush();
        return ((Number) entityManager.getEntityManager()
                .createNativeQuery("select count(*) from receipt_processing_attempts")
                .getSingleResult()).longValue();
    }

    // ------------------------------------------------------------------------------------------------------- saving

    @Test
    void theFirstReadingIsInsertedTogetherWithItsNewReceipt() {
        forgetLoadedEntities();

        List<ReceiptProcessingAttempt> attempts = attemptRepository.findByReceiptIdOrderByAttemptNumberAsc(receipt.getId());

        assertEquals(1, attempts.size());
        ReceiptProcessingAttempt first = attempts.get(0);
        assertNotNull(first.getId());
        assertEquals(1, first.getAttemptNumber());
        assertEquals("FAILED", first.getStatus());
        assertEquals(FIRST_ANSWER, first.getAiRawData());
        assertEquals("AI_NO_TOTAL", first.getErrorCode());
        assertEquals(ReceiptErrorCode.AI_NO_TOTAL.getMessage(), first.getSafeErrorMessage());
        assertEquals(SCANNED_AT, first.getCreatedAt());
        assertEquals(SCANNED_AT, first.getCompletedAt());
    }

    @Test
    void aLaterReadingIsSavedOnItsOwnAndTheEarlierOneStaysAsItWas() {
        attemptRepository.save(retryOf(receipt, 2, "attempt-2"));
        forgetLoadedEntities();

        List<ReceiptProcessingAttempt> attempts = attemptRepository.findByReceiptIdOrderByAttemptNumberAsc(receipt.getId());

        assertEquals(List.of(1, 2), attempts.stream().map(ReceiptProcessingAttempt::getAttemptNumber).toList());
        assertEquals(List.of(FIRST_ANSWER, SECOND_ANSWER), attempts.stream().map(ReceiptProcessingAttempt::getAiRawData).toList());
        assertEquals(List.of("FAILED", "COMPLETED"), attempts.stream().map(ReceiptProcessingAttempt::getStatus).toList());
    }

    @Test
    void aSavedAttemptIsNeverUpdatedWhateverHappensToTheLoadedCopy() {
        forgetLoadedEntities();
        ReceiptProcessingAttempt loaded = attemptRepository.findByReceiptIdOrderByAttemptNumberAsc(receipt.getId()).get(0);
        ReflectionTestUtils.setField(loaded, "aiRawData", "{\"tampered\": true}");
        ReflectionTestUtils.setField(loaded, "status", "COMPLETED");
        statistics().clear();

        entityManager.flush();

        assertEquals(0, statistics().getEntityUpdateCount(), "an immutable entity is never written back");
        entityManager.clear();
        ReceiptProcessingAttempt stored = attemptRepository.findById(loaded.getId()).orElseThrow();
        assertEquals(FIRST_ANSWER, stored.getAiRawData());
        assertEquals("FAILED", stored.getStatus());
    }

    // ------------------------------------------------------------------------------------------------- unique keys

    @Test
    void aReceiptCannotRecordTwoReadingsUnderOneNumber() {
        assertThrows(PersistenceException.class, () -> entityManager.persistAndFlush(retryOf(receipt, 1, null)));
    }

    @Test
    void aReceiptCannotRecordTwoReadingsUnderOneIdempotencyKey() {
        entityManager.persistAndFlush(retryOf(receipt, 2, "attempt-a"));

        assertThrows(PersistenceException.class, () -> entityManager.persistAndFlush(retryOf(receipt, 3, "attempt-a")));
    }

    @Test
    void readingsWithoutAKeyNeverCollideAndOneKeyMayServeTwoReceipts() {
        entityManager.persist(retryOf(receipt, 2, null));
        entityManager.persist(retryOf(receipt, 3, null));
        entityManager.persist(retryOf(receipt, 4, "attempt-a"));
        entityManager.persist(retryOf(other, 1, "attempt-a"));

        assertEquals(5, attemptRows());
    }

    // ---------------------------------------------------------------------------------------------------- queries

    @Test
    void aKeyIsFoundOnlyOnTheReceiptThatRecordedIt() {
        entityManager.persist(retryOf(receipt, 2, "attempt-a"));
        forgetLoadedEntities();

        assertTrue(attemptRepository.existsByReceiptIdAndClientId(receipt.getId(), "attempt-a"));
        assertFalse(attemptRepository.existsByReceiptIdAndClientId(receipt.getId(), "attempt-b"));
        assertFalse(attemptRepository.existsByReceiptIdAndClientId(other.getId(), "attempt-a"));
        assertEquals(3, statistics().getPrepareStatementCount(), "one statement per lookup");
    }

    @Test
    void theLastNumberIsZeroForAReceiptWithoutReadingsAndTheHighestOtherwise() {
        entityManager.persist(retryOf(receipt, 2, null));
        entityManager.persist(retryOf(receipt, 3, null));
        forgetLoadedEntities();

        assertEquals(3, attemptRepository.findLastAttemptNumber(receipt.getId()));
        assertEquals(0, attemptRepository.findLastAttemptNumber(other.getId()));
        assertEquals(0, attemptRepository.findLastAttemptNumber(-1L));
    }

    @Test
    void theReadingsOfAReceiptAreListedAndMappedInOneStatement() {
        entityManager.persist(retryOf(receipt, 2, "attempt-2"));
        forgetLoadedEntities();

        List<ReceiptAttemptResponseDto> listed = attemptRepository.findByReceiptIdOrderByAttemptNumberAsc(receipt.getId()).stream()
                .map(ReceiptAttemptResponseDto::from)
                .toList();

        assertEquals(1, statistics().getPrepareStatementCount(), "the receipt is read for its id only, nothing more is loaded");
        assertEquals(List.of(receipt.getId(), receipt.getId()), listed.stream().map(ReceiptAttemptResponseDto::getReceiptId).toList());
        assertEquals(SECOND_ANSWER, listed.get(1).getAiRawData());
        assertEquals("attempt-2", listed.get(1).getClientId());
    }

    // ------------------------------------------------------------------------------------- going with the receipt

    @Test
    void theReadingsGoWithTheirReceiptWhenItsTransactionIsDeleted() {
        entityManager.persist(retryOf(receipt, 2, null));
        entityManager.persist(retryOf(other, 1, null));
        entityManager.flush();
        entityManager.clear();

        // As TransactionService.deleteTransaction does: the transaction cascades to its receipt, the database to the attempts.
        entityManager.remove(entityManager.find(Transaction.class, carrier.getId()));

        assertEquals(1, attemptRows(), "only the attempt of the receipt that is still there is left");
        assertTrue(receiptRepository.findById(receipt.getId()).isEmpty());
        assertEquals(1, attemptRepository.findLastAttemptNumber(other.getId()));
    }

    @Test
    void theReadingsGoWithAReceiptNoTransactionCarries() {
        Receipt orphan = new Receipt();
        orphan.addFirstAttempt(ReceiptProcessingAttempt.legacy(orphan, ReceiptProcessingAttempt.FIRST_ATTEMPT_NUMBER,
                FIRST_ANSWER, SCANNED_AT));
        entityManager.persist(orphan);
        entityManager.flush();
        entityManager.clear();

        receiptRepository.delete(receiptRepository.findById(orphan.getId()).orElseThrow());

        assertEquals(1, attemptRows(), "the receipt of the transaction still has its own");
    }
}
