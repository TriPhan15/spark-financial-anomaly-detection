package vn.edu.huit.finance.core.Service.Audit;

import vn.edu.huit.finance.core.Entity.AuditAction;

/**
 * Asks for an action_logs row that is written on its own, whatever becomes of the transaction of the code that publishes
 * it ({@link SecurityAuditListener}). For what is not a database change of the request: a sign-in and its outcome, a
 * refused token, OTP or request (written although the request's transaction rolls back), and the administrative
 * operations whose effect lies outside the database (backup, restore, lifting a lockout, the segmentation batch).
 * A change the request makes in the database is recorded by AuditLogService.record, inside its transaction, instead.
 * <p>
 * Published as an application event so that security filters, handlers and controllers report what happened without
 * depending on how it is stored.
 */
public record SecurityAuditEvent(AuditEntry entry) {

    /** Why a signed-in caller got a 403, and the method and path (never the query string) of the request. */
    private static final String ACCESS_DENIED_DESCRIPTION = "%s: %s %s";

    public static SecurityAuditEvent of(AuditAction action, AuditActor actor, Long targetId, String description) {
        return new SecurityAuditEvent(AuditEntry.of(action, actor, targetId, description));
    }

    /** About the signed-in caller of the current request, looked up when the row is written. */
    public static SecurityAuditEvent ofCurrentCaller(AuditAction action, String description) {
        return of(action, AuditActor.currentCaller(), null, description);
    }

    /** A 403 answered to the signed-in caller of the current request. */
    public static SecurityAuditEvent accessDenied(String reason, String method, String path) {
        return ofCurrentCaller(AuditAction.AUTH_ACCESS_DENIED, String.format(ACCESS_DENIED_DESCRIPTION, reason, method, path));
    }
}
