package vn.edu.huit.finance.core.Entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The vocabulary of action_logs. Every code an earlier build wrote (all of them, from the git history of the project)
 * still means one action, so old rows are found and shown under today's names without being rewritten.
 */
class AuditActionTest {

    /** The action column (varchar 255), target_type (varchar 32) and result (varchar 16) of action_logs. */
    private static final int ACTION_COLUMN_LENGTH = 255;

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "LOGIN_SUCCESS, AUTH_LOGIN_SUCCESS",
            "LOGIN_FAILED, AUTH_LOGIN_FAILED",
            "LOGIN_LOCKED, AUTH_LOGIN_LOCKED",
            "SECURITY_UNLOCK, AUTH_LOGIN_UNLOCKED",
            "LOGOUT, AUTH_LOGOUT",
            "PASSWORD_CHANGED, AUTH_PASSWORD_CHANGED",
            "CHANGE_PASSWORD, AUTH_PASSWORD_CHANGED",
            "PASSWORD_RESET, AUTH_PASSWORD_RESET",
            "CREATE_USER, USER_CREATED",
            "UPDATE_USER, USER_UPDATED",
            "UPDATE_PROFILE, USER_UPDATED",
            "TOGGLE_USER, USER_STATUS_CHANGED",
            "DELETE_USER, USER_DELETED",
            "CREATE_TRANSACTION, TRANSACTION_CREATED",
            "CREATE_RECEIPT, RECEIPT_CREATED",
            "UPDATE_RECEIPT, RECEIPT_UPDATED",
            "RETRY_RECEIPT, RECEIPT_RETRIED",
            "CREATE_CATEGORY, CATEGORY_CREATED",
            "UPDATE_CATEGORY, CATEGORY_UPDATED",
            "DELETE_CATEGORY, CATEGORY_DELETED",
            "CREATE_PLAN, PLAN_CREATED",
            "UPDATE_PLAN, PLAN_UPDATED",
            "TOGGLE_PLAN, PLAN_STATUS_CHANGED",
            "DELETE_PLAN, PLAN_DELETED",
            "APPROVE_REVIEW, REVIEW_APPROVED",
            "DELETE_REVIEW, REVIEW_DELETED",
            "CREATE_BACKUP, SYSTEM_BACKUP_CREATED",
            "RESTORE_DB, SYSTEM_DATABASE_RESTORED",
            "AI_SEGMENT, SYSTEM_SEGMENTATION_RUN",
            "AUTO_WARNING, SYSTEM_LOW_BALANCE_WARNING"})
    void everyCodeAnEarlierBuildWroteStillMeansOneAction(String legacyCode, AuditAction action) {
        assertEquals(Optional.of(action), AuditAction.fromStored(legacyCode));
        assertTrue(action.getStoredCodes().contains(legacyCode));
    }

    @Test
    void everyActionIsFoundByItsOwnNameAndItsNameComesFirstAmongItsCodes() {
        for (AuditAction action : AuditAction.values()) {
            assertEquals(Optional.of(action), AuditAction.fromStored(action.name()));
            assertEquals(action.name(), action.getStoredCodes().iterator().next());
        }
    }

    @Test
    void noCodeIsSharedByTwoActions() {
        Set<String> seen = new HashSet<>();
        for (AuditAction action : AuditAction.values()) {
            for (String code : action.getStoredCodes()) {
                assertTrue(seen.add(code), code + " is used twice");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"auth_login_failed", "  LOGIN_FAILED  ", "Login_Failed"})
    void aStoredCodeIsMatchedIgnoringCaseAndBlanksAround(String stored) {
        assertEquals(Optional.of(AuditAction.AUTH_LOGIN_FAILED), AuditAction.fromStored(stored));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "DROP_TABLE", "LOGIN"})
    void aCodeNoActionUsesIsNoAction(String stored) {
        assertEquals(Optional.empty(), AuditAction.fromStored(stored));
    }

    @Test
    void theSecurityTabHoldsSignInsSessionsCredentialsAndRefusals_withTheirLegacyCodes_andNothingElse() {
        Set<String> security = AuditAction.storedCodesOf(AuditCategory.SECURITY);

        assertTrue(security.containsAll(Set.of("LOGIN_SUCCESS", "LOGIN_FAILED", "LOGIN_LOCKED", "SECURITY_UNLOCK", "LOGOUT",
                "PASSWORD_CHANGED", "CHANGE_PASSWORD", "PASSWORD_RESET", "AUTH_ACCESS_DENIED", "AUTH_TOKEN_REJECTED",
                "AUTH_TOKEN_REFRESH_REJECTED", "PAYMENT_WEBHOOK_REJECTED")));
        for (AuditAction action : AuditAction.values()) {
            boolean shown = security.contains(action.name());
            assertEquals(action.getCategory() == AuditCategory.SECURITY, shown, action.name());
        }
        assertFalse(security.contains("CREATE_USER"));
        assertFalse(security.contains("AUTO_WARNING"));
    }

    @Test
    void onlyRefusalsAndFailedOutcomesAreRecordedAsFailures() {
        Set<AuditAction> failures = Arrays.stream(AuditAction.values())
                .filter(action -> action.getResult() == AuditResult.FAILURE)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(AuditAction.class)));

        assertEquals(EnumSet.of(AuditAction.AUTH_LOGIN_FAILED, AuditAction.AUTH_LOGIN_LOCKED,
                AuditAction.AUTH_PASSWORD_CHANGE_REJECTED, AuditAction.AUTH_PASSWORD_RESET_REJECTED,
                AuditAction.AUTH_TOKEN_REFRESH_REJECTED, AuditAction.AUTH_TOKEN_REJECTED, AuditAction.AUTH_ACCESS_DENIED,
                AuditAction.RECEIPT_PROCESSING_FAILED, AuditAction.PAYMENT_FAILED, AuditAction.PAYMENT_WEBHOOK_REJECTED),
                failures);
    }

    @Test
    void everyChangeOfARecordNamesTheKindOfThatRecord() {
        for (AuditAction action : AuditAction.values()) {
            String name = action.name();
            if (name.endsWith("_CREATED") && action.getCategory() == AuditCategory.DATA
                    || name.endsWith("_UPDATED") || name.endsWith("_DELETED")) {
                assertNotNull(action.getTargetType(), name + " changes a record, so it names its kind");
            }
        }
        assertNull(AuditAction.AUTH_ACCESS_DENIED.getTargetType(), "a refused URL is no stored record");
        assertNull(AuditAction.SYSTEM_BACKUP_CREATED.getTargetType());
    }

    @Test
    void everyStoredValueFitsItsColumn() {
        for (AuditAction action : AuditAction.values()) {
            action.getStoredCodes().forEach(code -> assertTrue(code.length() <= ACTION_COLUMN_LENGTH, code));
            assertTrue(action.getResult().name().length() <= ActionLog.RESULT_MAX_LENGTH, action.name());
            assertFalse(action.getLabel().isBlank(), action.name());
        }
        for (AuditTargetType type : AuditTargetType.values()) {
            assertTrue(type.name().length() <= ActionLog.TARGET_TYPE_MAX_LENGTH, type.name());
        }
    }
}
