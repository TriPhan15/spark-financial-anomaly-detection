package vn.edu.huit.finance.core.Entity;

/**
 * The kind of record an action_logs row is about. Its name is stored in {@code target_type}, next to the id of that record
 * in {@code target_id}; the label is how the admin audit page names it.
 */
public enum AuditTargetType {
    USER("Tài khoản"),
    TRANSACTION("Giao dịch"),
    RECEIPT("Hóa đơn"),
    BUDGET("Ngân sách"),
    CATEGORY("Danh mục"),
    PAYMENT_ORDER("Đơn thanh toán"),
    SAVING_GOAL("Mục tiêu tiết kiệm"),
    SUBSCRIPTION_PLAN("Gói hội viên"),
    APP_REVIEW("Đánh giá ứng dụng");

    private final String label;

    AuditTargetType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
