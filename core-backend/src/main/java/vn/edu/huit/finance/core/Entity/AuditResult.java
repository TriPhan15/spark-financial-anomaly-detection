package vn.edu.huit.finance.core.Entity;

/**
 * The outcome an action_logs row records, stored in {@code result}. It follows from the action (every {@link AuditAction}
 * has exactly one), so a reader of the table can find the failures without knowing every action code. Rows written
 * before the column existed have none.
 */
public enum AuditResult {
    SUCCESS("Thành công"),
    FAILURE("Thất bại");

    private final String label;

    AuditResult(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
