package vn.edu.huit.finance.core.Exception;

/**
 * The receipt is not in a state that allows the request, or another request changed it first, so nothing was written.
 * Mapped to 409 by {@link GlobalExceptionHandler}; the client reads the receipt again before it tries anything else.
 */
public class ReceiptConflictException extends RuntimeException {

    public ReceiptConflictException(String message) {
        super(message);
    }
}
