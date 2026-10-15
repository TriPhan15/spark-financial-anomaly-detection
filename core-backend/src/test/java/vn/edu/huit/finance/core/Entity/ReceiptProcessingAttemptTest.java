package vn.edu.huit.finance.core.Entity;

import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Immutable;
import org.hibernate.envers.Audited;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** One reading of a receipt: what it records about each outcome, and that nothing can change it once it exists. */
class ReceiptProcessingAttemptTest {

    private static final String ANSWER = "  {\"seller\": {\"seller_name\": \"Highlands\", \"total_cost_number\": 95000}}\n";
    private static final LocalDateTime ENDED_AT = LocalDateTime.of(2026, 10, 1, 9, 30);
    private static final LocalDateTime RECEIPT_CREATED_AT = LocalDateTime.of(2026, 6, 1, 8, 0);

    private final Receipt receipt = new Receipt();

    @Test
    void aCompletedReadingKeepsTheAnswerExactlyAndEndedWhenItWasRecorded() {
        ReceiptProcessingAttempt attempt = ReceiptProcessingAttempt.completed(receipt, 2, ANSWER, "attempt-1", ENDED_AT);

        assertSame(receipt, attempt.getReceipt());
        assertEquals(2, attempt.getAttemptNumber());
        assertEquals("COMPLETED", attempt.getStatus());
        assertEquals(ANSWER, attempt.getAiRawData(), "not trimmed, not re-serialised");
        assertNull(attempt.getErrorCode());
        assertNull(attempt.getSafeErrorMessage());
        assertEquals("attempt-1", attempt.getClientId());
        assertEquals(ENDED_AT, attempt.getCreatedAt());
        assertEquals(ENDED_AT, attempt.getCompletedAt(), "the reading ended in the request that recorded it");
        assertNull(attempt.getId());
    }

    @ParameterizedTest
    @EnumSource(value = ReceiptErrorCode.class, names = {"AI_UNREADABLE", "AI_NO_TOTAL"})
    void aFailedReadingSaysWhyWithTheFixedTextOfTheReason(ReceiptErrorCode reason) {
        ReceiptProcessingAttempt attempt = ReceiptProcessingAttempt.failed(receipt, 1, ANSWER, null, reason, ENDED_AT);

        assertEquals("FAILED", attempt.getStatus());
        assertEquals(reason.name(), attempt.getErrorCode());
        assertEquals(reason.getMessage(), attempt.getSafeErrorMessage());
        assertNull(attempt.getClientId());
        assertEquals(ENDED_AT, attempt.getCompletedAt());
    }

    @Test
    void anImportedReadingClaimsNothingAboutAnOutcomeThatWasNeverRecorded() {
        ReceiptProcessingAttempt attempt = ReceiptProcessingAttempt.legacy(receipt, 1, ANSWER, RECEIPT_CREATED_AT);

        assertEquals("LEGACY", attempt.getStatus());
        assertEquals(ANSWER, attempt.getAiRawData());
        assertNull(attempt.getErrorCode());
        assertNull(attempt.getSafeErrorMessage());
        assertNull(attempt.getClientId());
        assertEquals(RECEIPT_CREATED_AT, attempt.getCreatedAt());
        assertNull(attempt.getCompletedAt(), "when that reading ended was never recorded");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void theNumberingStartsAtTheFirstAttempt(int attemptNumber) {
        assertThrows(IllegalArgumentException.class,
                () -> ReceiptProcessingAttempt.completed(receipt, attemptNumber, ANSWER, null, ENDED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> ReceiptProcessingAttempt.legacy(receipt, attemptNumber, ANSWER, RECEIPT_CREATED_AT));
    }

    @Test
    void aReadingWithoutAnAnswerIsNoReading() {
        assertThrows(IllegalArgumentException.class,
                () -> ReceiptProcessingAttempt.failed(receipt, 1, null, null, ReceiptErrorCode.AI_UNREADABLE, ENDED_AT));
    }

    // ------------------------------------------------------------------------------------------- written once, kept as it is

    @Test
    void theEntityIsImmutableAndHasNoSetterAndNoPublicConstructor() {
        assertNotNull(ReceiptProcessingAttempt.class.getAnnotation(Immutable.class), "no update of the entity may reach the row");
        List<String> setters = Arrays.stream(ReceiptProcessingAttempt.class.getMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("set"))
                .toList();
        assertEquals(List.of(), setters);
        assertTrue(Arrays.stream(ReceiptProcessingAttempt.class.getDeclaredConstructors())
                .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers())));
    }

    @Test
    void theAttemptsAreTheirOwnHistoryAndAreNotAudited() {
        assertNull(ReceiptProcessingAttempt.class.getAnnotation(Audited.class));
    }

    @Test
    void aReceiptHasOneAttemptPerNumberAndOnePerIdempotencyKey() {
        Table table = ReceiptProcessingAttempt.class.getAnnotation(Table.class);
        Map<String, List<String>> uniqueKeys = Arrays.stream(table.uniqueConstraints())
                .collect(Collectors.toMap(UniqueConstraint::name, constraint -> List.of(constraint.columnNames())));

        assertEquals("receipt_processing_attempts", table.name());
        assertEquals(Map.of(
                "uk_receipt_attempts_receipt_number", List.of("receipt_id", "attempt_number"),
                "uk_receipt_attempts_receipt_client_id", List.of("receipt_id", "client_id")), uniqueKeys);
    }

    @Test
    void everyStatusAndEveryReasonFitsItsColumn() {
        for (ReceiptAttemptStatus status : ReceiptAttemptStatus.values()) {
            assertTrue(status.name().length() <= ReceiptProcessingAttempt.STATUS_MAX_LENGTH, status.name());
        }
        for (ReceiptErrorCode code : ReceiptErrorCode.values()) {
            assertTrue(code.name().length() <= Receipt.ERROR_CODE_MAX_LENGTH, code.name());
            assertTrue(code.getMessage().length() <= ReceiptProcessingAttempt.SAFE_ERROR_MESSAGE_MAX_LENGTH, code.name());
        }
    }
}
