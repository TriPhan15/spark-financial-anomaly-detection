package vn.edu.huit.finance.core.Entity;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static vn.edu.huit.finance.core.Entity.AuditCategory.DATA;
import static vn.edu.huit.finance.core.Entity.AuditCategory.SECURITY;
import static vn.edu.huit.finance.core.Entity.AuditCategory.SYSTEM;
import static vn.edu.huit.finance.core.Entity.AuditResult.FAILURE;
import static vn.edu.huit.finance.core.Entity.AuditResult.SUCCESS;

/**
 * Every action the application records in action_logs; the name is the code stored in {@code action}. Only actions the
 * code base really performs are here. Each one fixes what its rows look like: the tab it is shown in, the kind of record
 * its {@code target_id} points at (none for an action about no single record) and its {@link AuditResult}.
 * <p>
 * Rows written before these codes existed keep the code they were written with: the history is never rewritten. Those
 * older codes are listed as legacy codes of the action that replaced them, so that a search for an action finds its old
 * rows too, and the admin page shows them under the same name.
 */
public enum AuditAction {

    // ---------------------------------------------------------------------------- sign-in, sessions and credentials
    AUTH_LOGIN_SUCCESS(SECURITY, AuditTargetType.USER, SUCCESS, "Đăng nhập thành công", "LOGIN_SUCCESS"),
    AUTH_LOGIN_FAILED(SECURITY, AuditTargetType.USER, FAILURE, "Đăng nhập thất bại", "LOGIN_FAILED"),
    /** A sign-in refused because the account or the address is locked out, or the attempt that triggered the lockout. */
    AUTH_LOGIN_LOCKED(SECURITY, AuditTargetType.USER, FAILURE, "Đăng nhập bị khóa tạm thời", "LOGIN_LOCKED"),
    /** An administrator lifted a lockout early; the lockout is about an email or an address, not a stored record. */
    AUTH_LOGIN_UNLOCKED(SECURITY, null, SUCCESS, "Gỡ khóa đăng nhập", "SECURITY_UNLOCK"),
    AUTH_LOGOUT(SECURITY, AuditTargetType.USER, SUCCESS, "Đăng xuất", "LOGOUT"),
    AUTH_PASSWORD_CHANGED(SECURITY, AuditTargetType.USER, SUCCESS, "Đổi mật khẩu", "PASSWORD_CHANGED", "CHANGE_PASSWORD"),
    /** The old password sent with a change was wrong. */
    AUTH_PASSWORD_CHANGE_REJECTED(SECURITY, AuditTargetType.USER, FAILURE, "Đổi mật khẩu bị từ chối"),
    /** An OTP to reset the password was mailed. */
    AUTH_PASSWORD_RESET_REQUESTED(SECURITY, AuditTargetType.USER, SUCCESS, "Yêu cầu mã OTP đặt lại mật khẩu"),
    /** An OTP was asked for an unknown email, or a reset came with a wrong, expired or used-up OTP. */
    AUTH_PASSWORD_RESET_REJECTED(SECURITY, AuditTargetType.USER, FAILURE, "Đặt lại mật khẩu bị từ chối"),
    AUTH_PASSWORD_RESET(SECURITY, AuditTargetType.USER, SUCCESS, "Đặt lại mật khẩu bằng OTP", "PASSWORD_RESET"),
    AUTH_TOKEN_REFRESH_REJECTED(SECURITY, AuditTargetType.USER, FAILURE, "Làm mới phiên đăng nhập bị từ chối"),
    /** A signed access token that may no longer be used: revoked, issued before the last password change, or of a locked account. */
    AUTH_TOKEN_REJECTED(SECURITY, AuditTargetType.USER, FAILURE, "Token truy cập bị từ chối"),
    /** A signed-in caller asked for something its role or ownership does not allow (403). */
    AUTH_ACCESS_DENIED(SECURITY, null, FAILURE, "Truy cập bị từ chối"),

    // ------------------------------------------------------------------------------------------------------- accounts
    USER_CREATED(DATA, AuditTargetType.USER, SUCCESS, "Tạo tài khoản", "CREATE_USER"),
    USER_UPDATED(DATA, AuditTargetType.USER, SUCCESS, "Cập nhật tài khoản", "UPDATE_USER", "UPDATE_PROFILE"),
    USER_ROLE_CHANGED(DATA, AuditTargetType.USER, SUCCESS, "Đổi vai trò tài khoản"),
    /** Locked (inactive) or unlocked (active again) by an administrator. */
    USER_STATUS_CHANGED(DATA, AuditTargetType.USER, SUCCESS, "Khóa / mở khóa tài khoản", "TOGGLE_USER"),
    USER_DELETED(DATA, AuditTargetType.USER, SUCCESS, "Xóa tài khoản", "DELETE_USER"),

    // --------------------------------------------------------------------------------------------------- transactions
    TRANSACTION_CREATED(DATA, AuditTargetType.TRANSACTION, SUCCESS, "Tạo giao dịch", "CREATE_TRANSACTION"),
    TRANSACTION_UPDATED(DATA, AuditTargetType.TRANSACTION, SUCCESS, "Sửa giao dịch"),
    TRANSACTION_DELETED(DATA, AuditTargetType.TRANSACTION, SUCCESS, "Xóa giao dịch"),

    // ------------------------------------------------------------------------------------------------------- receipts
    RECEIPT_CREATED(DATA, AuditTargetType.RECEIPT, SUCCESS, "Tạo hóa đơn", "CREATE_RECEIPT"),
    /** A reading of the AI (sent with a checkout or a retry) holds a usable total. */
    RECEIPT_PROCESSING_SUCCEEDED(DATA, AuditTargetType.RECEIPT, SUCCESS, "AI đọc hóa đơn thành công"),
    /** A reading of the AI holds no usable total: the receipt is FAILED with the reason. */
    RECEIPT_PROCESSING_FAILED(DATA, AuditTargetType.RECEIPT, FAILURE, "AI không đọc được hóa đơn"),
    RECEIPT_UPDATED(DATA, AuditTargetType.RECEIPT, SUCCESS, "Sửa hóa đơn", "UPDATE_RECEIPT"),
    RECEIPT_RETRIED(DATA, AuditTargetType.RECEIPT, SUCCESS, "Đọc lại hóa đơn", "RETRY_RECEIPT"),
    RECEIPT_FEEDBACK_SUBMITTED(DATA, AuditTargetType.RECEIPT, SUCCESS, "Gửi yêu cầu duyệt hóa đơn"),
    RECEIPT_APPROVED(DATA, AuditTargetType.RECEIPT, SUCCESS, "Duyệt hóa đơn"),
    RECEIPT_REJECTED(DATA, AuditTargetType.RECEIPT, SUCCESS, "Từ chối hóa đơn"),
    RECEIPT_CANCELED(DATA, AuditTargetType.RECEIPT, SUCCESS, "Hủy hóa đơn"),

    // -------------------------------------------------------------------------------------------------------- budgets
    BUDGET_CREATED(DATA, AuditTargetType.BUDGET, SUCCESS, "Tạo ngân sách"),
    BUDGET_UPDATED(DATA, AuditTargetType.BUDGET, SUCCESS, "Sửa ngân sách"),
    BUDGET_DELETED(DATA, AuditTargetType.BUDGET, SUCCESS, "Xóa ngân sách"),

    // ----------------------------------------------------------------------------------------------------- categories
    CATEGORY_CREATED(DATA, AuditTargetType.CATEGORY, SUCCESS, "Tạo danh mục", "CREATE_CATEGORY"),
    CATEGORY_UPDATED(DATA, AuditTargetType.CATEGORY, SUCCESS, "Sửa danh mục", "UPDATE_CATEGORY"),
    CATEGORY_DELETED(DATA, AuditTargetType.CATEGORY, SUCCESS, "Xóa danh mục", "DELETE_CATEGORY"),

    // ------------------------------------------------------------------------------------------------------- payments
    PAYMENT_CREATED(DATA, AuditTargetType.PAYMENT_ORDER, SUCCESS, "Tạo đơn thanh toán"),
    /** The gateway confirmed the payment and the wallet transaction was recorded. */
    PAYMENT_COMPLETED(DATA, AuditTargetType.PAYMENT_ORDER, SUCCESS, "Thanh toán thành công"),
    /** The gateway reported a failure, or confirmed another amount than the order's. */
    PAYMENT_FAILED(DATA, AuditTargetType.PAYMENT_ORDER, FAILURE, "Thanh toán thất bại"),
    PAYMENT_CANCELED(DATA, AuditTargetType.PAYMENT_ORDER, SUCCESS, "Hủy đơn thanh toán"),
    /** A webhook whose signature does not match: nothing was changed. */
    PAYMENT_WEBHOOK_REJECTED(SECURITY, AuditTargetType.PAYMENT_ORDER, FAILURE, "Webhook thanh toán bị từ chối"),

    // --------------------------------------------------------------------------------------------------- saving goals
    SAVING_CREATED(DATA, AuditTargetType.SAVING_GOAL, SUCCESS, "Tạo mục tiêu tiết kiệm"),
    SAVING_UPDATED(DATA, AuditTargetType.SAVING_GOAL, SUCCESS, "Cập nhật mục tiêu tiết kiệm"),
    SAVING_DELETED(DATA, AuditTargetType.SAVING_GOAL, SUCCESS, "Xóa mục tiêu tiết kiệm"),

    // --------------------------------------------------------------------------------------------- subscription plans
    PLAN_CREATED(DATA, AuditTargetType.SUBSCRIPTION_PLAN, SUCCESS, "Tạo gói hội viên", "CREATE_PLAN"),
    PLAN_UPDATED(DATA, AuditTargetType.SUBSCRIPTION_PLAN, SUCCESS, "Sửa gói hội viên", "UPDATE_PLAN"),
    PLAN_STATUS_CHANGED(DATA, AuditTargetType.SUBSCRIPTION_PLAN, SUCCESS, "Mở bán / tạm ẩn gói hội viên", "TOGGLE_PLAN"),
    PLAN_DELETED(DATA, AuditTargetType.SUBSCRIPTION_PLAN, SUCCESS, "Xóa gói hội viên", "DELETE_PLAN"),

    // -------------------------------------------------------------------------------------------------------- reviews
    REVIEW_APPROVED(DATA, AuditTargetType.APP_REVIEW, SUCCESS, "Duyệt đánh giá", "APPROVE_REVIEW"),
    REVIEW_DELETED(DATA, AuditTargetType.APP_REVIEW, SUCCESS, "Xóa đánh giá", "DELETE_REVIEW"),

    // -------------------------------------------------------------------------------------------- system operations
    SYSTEM_BACKUP_CREATED(SYSTEM, null, SUCCESS, "Sao lưu cơ sở dữ liệu", "CREATE_BACKUP"),
    SYSTEM_DATABASE_RESTORED(SYSTEM, null, SUCCESS, "Phục hồi cơ sở dữ liệu", "RESTORE_DB"),
    SYSTEM_SEGMENTATION_RUN(SYSTEM, null, SUCCESS, "Phân lớp khách hàng (AI)", "AI_SEGMENT"),
    SYSTEM_LOW_BALANCE_WARNING(SYSTEM, AuditTargetType.USER, SUCCESS, "Cảnh báo số dư thấp", "AUTO_WARNING");

    private static final Map<String, AuditAction> BY_STORED_CODE = new HashMap<>();

    static {
        for (AuditAction action : values()) {
            for (String code : action.storedCodes) {
                if (BY_STORED_CODE.put(code, action) != null) {
                    throw new IllegalStateException("Audit code used twice: " + code);
                }
            }
        }
    }

    private final AuditCategory category;
    private final AuditTargetType targetType;
    private final AuditResult result;
    private final String label;
    private final Set<String> storedCodes;

    AuditAction(AuditCategory category, AuditTargetType targetType, AuditResult result, String label, String... legacyCodes) {
        this.category = category;
        this.targetType = targetType;
        this.result = result;
        this.label = label;
        Set<String> codes = new LinkedHashSet<>();
        codes.add(name());
        codes.addAll(Arrays.asList(legacyCodes));
        this.storedCodes = Collections.unmodifiableSet(codes);
    }

    public AuditCategory getCategory() {
        return category;
    }

    /** The kind of record the target id of this action points at; null for an action about no single stored record. */
    public AuditTargetType getTargetType() {
        return targetType;
    }

    public AuditResult getResult() {
        return result;
    }

    public String getLabel() {
        return label;
    }

    /** Every code rows of this action may carry: its own name first, then the codes older builds wrote for it. */
    public Set<String> getStoredCodes() {
        return storedCodes;
    }

    /** The action a stored code stands for, a legacy code included; empty for null and for a code none of them uses. */
    public static Optional<AuditAction> fromStored(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_STORED_CODE.get(code.trim().toUpperCase(Locale.ROOT)));
    }

    /** Every code, legacy ones included, that rows of the actions of {@code category} may carry. */
    public static Set<String> storedCodesOf(AuditCategory category) {
        Set<String> codes = new LinkedHashSet<>();
        for (AuditAction action : values()) {
            if (action.category == category) {
                codes.addAll(action.storedCodes);
            }
        }
        return Collections.unmodifiableSet(codes);
    }
}
