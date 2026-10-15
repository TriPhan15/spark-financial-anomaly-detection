package vn.edu.huit.finance.core.Exception;

/** The transaction or receipt exists but belongs to another user. */
public class TransactionAccessDeniedException extends RuntimeException {

    public TransactionAccessDeniedException(String message) {
        super(message);
    }
}
