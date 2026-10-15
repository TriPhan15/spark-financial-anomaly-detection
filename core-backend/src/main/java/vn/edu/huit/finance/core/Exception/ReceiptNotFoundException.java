package vn.edu.huit.finance.core.Exception;

/** The receipt does not exist. */
public class ReceiptNotFoundException extends RuntimeException {

    public ReceiptNotFoundException(String message) {
        super(message);
    }
}
