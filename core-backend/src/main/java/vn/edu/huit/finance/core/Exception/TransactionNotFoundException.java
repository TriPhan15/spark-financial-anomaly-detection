package vn.edu.huit.finance.core.Exception;

/** The transaction does not exist. */
public class TransactionNotFoundException extends RuntimeException {

    public TransactionNotFoundException(String message) {
        super(message);
    }
}
