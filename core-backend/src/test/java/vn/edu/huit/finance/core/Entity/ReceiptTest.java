package vn.edu.huit.finance.core.Entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Version;
import org.hibernate.envers.NotAudited;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The rules a receipt keeps by itself, whichever service holds it: its status, its error code and its readings by the AI. */
class ReceiptTest {

    private static Receipt receipt(String status) {
        Receipt receipt = new Receipt();
        receipt.setStatus(status);
        return receipt;
    }

    // ---------------------------------------------------------------------------------------------------------- status

    @Test
    void aNewReceiptIsPending() {
        assertEquals("PENDING", new Receipt().getStatus());
        assertEquals(ReceiptStatus.PENDING, new Receipt().lifecycleStatus().orElseThrow());
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING, COMPLETED_RAW", "PENDING, FAILED", "COMPLETED_RAW, EDITED", "EDITED, EDITED", "EDITED, APPROVED",
            "FAILED, COMPLETED_RAW", "FAILED, FAILED", "FAILED, WAITING_FOR_REVIEW", "WAITING_FOR_REVIEW, APPROVED",
            "PENDING, CANCELED", "SUCCESS, EDITED", "success, APPROVED", "failed, COMPLETED_RAW"})
    void aMoveTheLifecycleKnowsWritesTheNameOfTheNewStatus(String stored, ReceiptStatus target) {
        Receipt receipt = receipt(stored);

        assertTrue(receipt.canMoveTo(target));
        receipt.moveTo(target);

        assertEquals(target.name(), receipt.getStatus());
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING, PROCESSING", "FAILED, PROCESSING", "failed, PROCESSING", "PROCESSING, COMPLETED_RAW", "PROCESSING, FAILED",
            "PROCESSING, EDITED", "APPROVED, EDITED", "FAILED, EDITED", "CANCELED, PENDING", "CANCELED, APPROVED",
            "COMPLETED_RAW, PROCESSING", "EDITED, PROCESSING", "APPROVED, PROCESSING", "EDITED, COMPLETED_RAW",
            "APPROVED, COMPLETED_RAW", "WAITING_FOR_REVIEW, COMPLETED_RAW", "WAITING_FOR_REVIEW, EDITED",
            "SUCCESS, PROCESSING", "NOT_A_STATUS, APPROVED"})
    void aMoveTheLifecycleDoesNotKnowIsRefusedAndTheReceiptStaysAsItWas(String stored, ReceiptStatus target) {
        Receipt receipt = receipt(stored);
        receipt.setErrorCode("AI_NO_TOTAL");

        assertFalse(receipt.canMoveTo(target));
        assertThrows(IllegalStateException.class, () -> receipt.moveTo(target));

        assertEquals(stored, receipt.getStatus());
        assertEquals("AI_NO_TOTAL", receipt.getErrorCode());
    }

    @Test
    void aStatusThatIsNoneOfTheLifecyclesCanMoveNowhere() {
        Receipt receipt = receipt("NOT_A_STATUS");

        assertTrue(receipt.lifecycleStatus().isEmpty());
        for (ReceiptStatus target : ReceiptStatus.values()) {
            assertFalse(receipt.canMoveTo(target));
        }
    }

    // ------------------------------------------------------------------------------------------------------ error code

    @ParameterizedTest
    @EnumSource(ReceiptErrorCode.class)
    void failingAReceiptRecordsWhy(ReceiptErrorCode reason) {
        Receipt receipt = receipt("PENDING");

        receipt.failWith(reason);

        assertEquals("FAILED", receipt.getStatus());
        assertEquals(reason.name(), receipt.getErrorCode());
        assertTrue(reason.name().length() <= Receipt.ERROR_CODE_MAX_LENGTH, "the code must fit its column");
    }

    @Test
    void aFailedReceiptWhoseNewReadingFailsTooKeepsOnlyTheReasonOfTheNewReading() {
        Receipt receipt = receipt("FAILED");
        receipt.setErrorCode("REJECTED_BY_ADMIN");

        receipt.failWith(ReceiptErrorCode.AI_UNREADABLE);

        assertEquals("FAILED", receipt.getStatus());
        assertEquals("AI_UNREADABLE", receipt.getErrorCode());
    }

    @ParameterizedTest
    @CsvSource({"FAILED, COMPLETED_RAW", "FAILED, WAITING_FOR_REVIEW", "FAILED, APPROVED"})
    void theReasonOfAFailureGoesWhenTheReceiptLeavesFailed(String stored, ReceiptStatus target) {
        Receipt receipt = receipt(stored);
        receipt.setErrorCode("AI_NO_TOTAL");

        receipt.moveTo(target);

        assertNull(receipt.getErrorCode());
    }

    @Test
    void aReceiptCannotBeFailedFromAStatusThatHasNoMoveToFailed() {
        Receipt canceled = receipt("CANCELED");

        assertThrows(IllegalStateException.class, () -> canceled.failWith(ReceiptErrorCode.REJECTED_BY_ADMIN));

        assertEquals("CANCELED", canceled.getStatus());
        assertNull(canceled.getErrorCode());
    }

    @Test
    void everyReasonHasATextForTheOwnerAndIsReadBackFromItsName() {
        for (ReceiptErrorCode code : ReceiptErrorCode.values()) {
            assertFalse(code.getMessage().isBlank());
            assertEquals(code, ReceiptErrorCode.fromStored(code.name()).orElseThrow());
        }
        assertTrue(ReceiptErrorCode.fromStored(null).isEmpty());
        assertTrue(ReceiptErrorCode.fromStored("java.net.SocketTimeoutException").isEmpty());
    }

    // ------------------------------------------------------------------------- the answers of the AI: the attempts

    private static ReceiptProcessingAttempt firstReadingOf(Receipt receipt) {
        return ReceiptProcessingAttempt.completed(receipt, ReceiptProcessingAttempt.FIRST_ATTEMPT_NUMBER,
                "{\"seller\": {}}", null, LocalDateTime.of(2026, 10, 1, 9, 0));
    }

    @Test
    void aNewReceiptTakesTheAttemptOfItsFirstReadingAndOnlyOne() {
        Receipt receipt = new Receipt();
        ReceiptProcessingAttempt first = firstReadingOf(receipt);

        receipt.addFirstAttempt(first);

        assertEquals(List.of(first), receipt.getAttempts());
        assertThrows(IllegalStateException.class, () -> receipt.addFirstAttempt(firstReadingOf(receipt)));
        assertEquals(1, receipt.getAttempts().size());
    }

    @Test
    void aSavedReceiptTakesNoFirstAttemptItsLaterReadingsAreSavedOnTheirOwn() {
        Receipt saved = new Receipt();
        saved.setId(7L);

        assertThrows(IllegalStateException.class, () -> saved.addFirstAttempt(firstReadingOf(saved)));
        assertTrue(saved.getAttempts().isEmpty());
    }

    @Test
    void theAttemptsCannotBeChangedThroughTheReceipt() {
        Receipt receipt = new Receipt();
        receipt.addFirstAttempt(firstReadingOf(receipt));

        assertThrows(UnsupportedOperationException.class, () -> receipt.getAttempts().clear());
        assertThrows(NoSuchMethodException.class, () -> Receipt.class.getMethod("setAttempts", List.class));
    }

    @Test
    void theAttemptsAreSavedWithANewReceiptButNotAuditedWithIt() throws NoSuchFieldException {
        Field attempts = Receipt.class.getDeclaredField("attempts");

        assertNotNull(attempts.getAnnotation(NotAudited.class), "the attempts are a history of their own");
        assertArrayEquals(new CascadeType[]{CascadeType.PERSIST}, attempts.getAnnotation(OneToMany.class).cascade(),
                "persisted with a new receipt, never merged or removed through it");
    }

    @Test
    void theLegacyColumnOfTheAnswerIsReadOnlyNotAuditedAndHasNoSetter() throws NoSuchFieldException {
        Field aiRawData = Receipt.class.getDeclaredField("aiRawData");
        Column column = aiRawData.getAnnotation(Column.class);

        assertEquals("ai_raw_data", column.name());
        assertFalse(column.insertable(), "nothing writes the legacy column any more");
        assertFalse(column.updatable(), "nothing writes the legacy column any more");
        assertNotNull(aiRawData.getAnnotation(NotAudited.class), "no revision copies it again");
        assertThrows(NoSuchMethodException.class, () -> Receipt.class.getMethod("setAiRawData", String.class));
    }

    @Test
    void theLegacyColumnOnTheTransactionIsReadOnlySoNoSecondCopyOfTheAnswerIsEverWritten() throws NoSuchFieldException {
        Column column = Transaction.class.getDeclaredField("rawReceiptData").getAnnotation(Column.class);

        assertFalse(column.insertable());
        assertFalse(column.updatable());
    }

    // --------------------------------------------------------------------------------------------------------- version

    @Test
    void theRowIsVersionedForOptimisticLocking() throws NoSuchFieldException {
        Field version = Receipt.class.getDeclaredField("version");

        assertNotNull(version.getAnnotation(Version.class));
        assertFalse(version.getAnnotation(Column.class).nullable());
        assertNull(new Receipt().getVersion(), "a receipt that was never saved has no version yet");
    }

    // ------------------------------------------------------------------------------- the name the admin pages show

    @ParameterizedTest
    @CsvSource({"COMPLETED_RAW, SUCCESS", "EDITED, SUCCESS", "APPROVED, SUCCESS", "SUCCESS, SUCCESS", "FAILED, FAILED",
            "PENDING, PENDING", "WAITING_FOR_REVIEW, WAITING_FOR_REVIEW"})
    void theLegacyStatusIsTheNameTheAdminPagesAndTheAppKnow(String stored, String legacyStatus) {
        assertEquals(legacyStatus, receipt(stored).getLegacyStatus());
    }
}
