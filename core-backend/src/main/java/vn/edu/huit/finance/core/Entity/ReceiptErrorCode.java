package vn.edu.huit.finance.core.Entity;

import java.util.Optional;

/**
 * Why a receipt is FAILED; {@code Receipt.errorCode} stores the name of one of these. The message is the only text about
 * a failure that is ever shown to a client: nothing an exception said is stored or passed on.
 */
public enum ReceiptErrorCode {
    /** The AI's answer held none of the fields of a receipt (not JSON, not an object, or only noise). */
    AI_UNREADABLE("AI không đọc được nội dung hóa đơn"),
    /** The AI read something, but no total that can be trusted. */
    AI_NO_TOTAL("AI không đọc được tổng tiền trên hóa đơn"),
    /** An administrator rejected the receipt. */
    REJECTED_BY_ADMIN("Hóa đơn đã bị quản trị viên từ chối");

    private final String message;

    ReceiptErrorCode(String message) {
        this.message = message;
    }

    /** What the owner of the receipt is told. */
    public String getMessage() {
        return message;
    }

    /** The code a stored text stands for; empty for null and for a text that is none of the codes. */
    public static Optional<ReceiptErrorCode> fromStored(String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        for (ReceiptErrorCode code : values()) {
            if (code.name().equals(stored)) {
                return Optional.of(code);
            }
        }
        return Optional.empty();
    }
}
