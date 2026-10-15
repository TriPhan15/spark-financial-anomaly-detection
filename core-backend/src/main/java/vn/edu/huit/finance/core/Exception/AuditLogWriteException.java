package vn.edu.huit.finance.core.Exception;

/**
 * An action_logs row could not be written. The message is a fixed text that may be shown to a client; the cause (which
 * may quote SQL or the database) stays on the server, in the log line AuditLogService writes. Thrown inside the
 * transaction of a change, it rolls that change back: nothing is changed without its audit row.
 */
public class AuditLogWriteException extends RuntimeException {

    public AuditLogWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
