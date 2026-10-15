package vn.edu.huit.finance.core.Exception;

/** The change would take the wallet balance below zero. */
public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(String message) {
        super(message);
    }
}
