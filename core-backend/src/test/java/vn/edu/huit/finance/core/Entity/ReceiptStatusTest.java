package vn.edu.huit.finance.core.Entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.huit.finance.core.Entity.ReceiptStatus.*;

/** The lifecycle of a receipt as one table, and how the rows written before it existed are read. */
class ReceiptStatusTest {

    // --------------------------------------------------------------------------------------------- the moves that exist

    static Stream<Arguments> everyMoveOfTheLifecycle() {
        // from, every status it may move to
        return Stream.of(
                Arguments.of(PENDING, EnumSet.of(COMPLETED_RAW, FAILED, CANCELED, APPROVED)),
                Arguments.of(PROCESSING, EnumSet.noneOf(ReceiptStatus.class)),
                Arguments.of(COMPLETED_RAW, EnumSet.of(EDITED, APPROVED, FAILED)),
                Arguments.of(EDITED, EnumSet.of(EDITED, APPROVED, FAILED)),
                Arguments.of(FAILED, EnumSet.of(COMPLETED_RAW, FAILED, WAITING_FOR_REVIEW, APPROVED)),
                Arguments.of(WAITING_FOR_REVIEW, EnumSet.of(APPROVED, FAILED)),
                Arguments.of(APPROVED, EnumSet.of(APPROVED, FAILED)),
                Arguments.of(CANCELED, EnumSet.noneOf(ReceiptStatus.class)));
    }

    @ParameterizedTest
    @MethodSource("everyMoveOfTheLifecycle")
    void aStatusMovesToTheStatusesOfTheTableAndToNoOther(ReceiptStatus from, Set<ReceiptStatus> allowed) {
        for (ReceiptStatus target : ReceiptStatus.values()) {
            assertEquals(allowed.contains(target), from.canTransitionTo(target), from + " -> " + target);
        }
    }

    @Test
    void everyStatusIsInTheTable() {
        assertEquals(ReceiptStatus.values().length, everyMoveOfTheLifecycle().count());
    }

    @ParameterizedTest
    @EnumSource(ReceiptStatus.class)
    void aCanceledReceiptGoesNowhereAndNothingIsEverUncanceled(ReceiptStatus other) {
        assertFalse(CANCELED.canTransitionTo(other));
        // Only a receipt nothing was read for yet can be withdrawn.
        assertEquals(other == PENDING, other.canTransitionTo(CANCELED));
    }

    @ParameterizedTest
    @EnumSource(ReceiptStatus.class)
    void noMoveLeadsIntoProcessingSoNoFlowCanStoreIt(ReceiptStatus from) {
        // The AI runs on the phone: core-backend never sees a reading in progress, so nothing may pretend it does.
        assertFalse(from.canTransitionTo(PROCESSING));
    }

    @ParameterizedTest
    @EnumSource(ReceiptStatus.class)
    void aRowThatHoldsProcessingAnywayIsMovedNowhere(ReceiptStatus target) {
        assertFalse(PROCESSING.canTransitionTo(target));
    }

    @ParameterizedTest
    @EnumSource(ReceiptStatus.class)
    void aReadingIsRecordedForANewReceiptOrAFailedOneAndOnlyAReadingMakesAReceiptCompletedRaw(ReceiptStatus from) {
        assertEquals(from == PENDING || from == FAILED, from.canTransitionTo(COMPLETED_RAW));
    }

    @Test
    void aRetryWhoseReadingStillHoldsNoTotalLeavesTheReceiptFailed() {
        assertTrue(FAILED.canTransitionTo(FAILED));
        assertTrue(PENDING.canTransitionTo(FAILED), "a checkout whose reading holds no total creates a FAILED receipt");
    }

    @ParameterizedTest
    @EnumSource(ReceiptStatus.class)
    void onlyAReceiptThatWasReadAndIsNotSettledCanBeEdited(ReceiptStatus from) {
        assertEquals(from == COMPLETED_RAW || from == EDITED, from.canTransitionTo(EDITED));
    }

    @ParameterizedTest
    @EnumSource(ReceiptStatus.class)
    void onlyAFailedReceiptWaitsForReview(ReceiptStatus from) {
        assertEquals(from == FAILED, from.canTransitionTo(WAITING_FOR_REVIEW));
    }

    @ParameterizedTest
    @EnumSource(value = ReceiptStatus.class, names = {"PROCESSING", "CANCELED"})
    void aReceiptThatIsBeingReadOrWasWithdrawnIsNotApproved(ReceiptStatus from) {
        assertFalse(from.canTransitionTo(APPROVED));
    }

    // ------------------------------------------------------------------------------ rows that predate the lifecycle

    @ParameterizedTest
    @CsvSource({
            "PENDING, PENDING", "pending, PENDING", "' Pending ', PENDING",
            "FAILED, FAILED", "Failed, FAILED",
            "WAITING_FOR_REVIEW, WAITING_FOR_REVIEW", "waiting_for_review, WAITING_FOR_REVIEW",
            "CANCELED, CANCELED", "canceled, CANCELED",
            "SUCCESS, COMPLETED_RAW", "success, COMPLETED_RAW", "' Success', COMPLETED_RAW"})
    void aStatusWrittenBeforeTheLifecycleIsReadAsTheStatusItMeans(String stored, ReceiptStatus expected) {
        assertEquals(Optional.of(expected), ReceiptStatus.fromStored(stored));
    }

    @ParameterizedTest
    @EnumSource(ReceiptStatus.class)
    void everyStatusIsReadBackFromItsOwnName(ReceiptStatus status) {
        assertEquals(Optional.of(status), ReceiptStatus.fromStored(status.name()));
        assertTrue(status.matches(status.name()));
        assertTrue(status.matches(" " + status.name().toLowerCase() + " "));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "DONE", "CANCELLED", "SUCCESSFUL"})
    void aTextThatIsNoStatusIsNotGuessedAt(String stored) {
        assertEquals(Optional.empty(), ReceiptStatus.fromStored(stored));
        for (ReceiptStatus status : ReceiptStatus.values()) {
            assertFalse(status.matches(stored));
        }
    }

    @Test
    void successIsNoLongerAStatusOfItsOwnSoNothingCanWriteIt() {
        assertTrue(Stream.of(ReceiptStatus.values()).noneMatch(status -> status.name().equals(LEGACY_SUCCESS)));
        assertTrue(COMPLETED_RAW.matches(LEGACY_SUCCESS));
        assertFalse(APPROVED.matches(LEGACY_SUCCESS), "an old SUCCESS row was read by the AI, not approved by an administrator");
    }

    // --------------------------------------------------------------- the names the clients that predate it know

    @ParameterizedTest
    @CsvSource({
            "PENDING, PENDING", "PROCESSING, PROCESSING", "FAILED, FAILED", "WAITING_FOR_REVIEW, WAITING_FOR_REVIEW",
            "CANCELED, CANCELED", "COMPLETED_RAW, SUCCESS", "EDITED, SUCCESS", "APPROVED, SUCCESS"})
    void theOlderClientsKnowAReadReceiptAsSuccessAndEveryOtherStatusUnderItsOwnName(ReceiptStatus status, String legacyName) {
        assertEquals(legacyName, status.legacyName());
        assertEquals(legacyName, ReceiptStatus.legacyNameOf(status.name()));
    }

    @Test
    void theLegacyNameOfAStoredTextFollowsTheWayItIsRead() {
        assertEquals("SUCCESS", ReceiptStatus.legacyNameOf("success"));
        assertEquals("FAILED", ReceiptStatus.legacyNameOf(" failed "));
        assertEquals("DONE", ReceiptStatus.legacyNameOf("DONE"), "a text the lifecycle does not know is passed on as it is");
        assertNull(ReceiptStatus.legacyNameOf(null));
    }
}
