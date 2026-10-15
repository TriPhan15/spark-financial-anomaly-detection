package vn.edu.huit.finance.core.Exception;

/** The request carries no usable token where one is required. Mapped to 401 by {@link GlobalExceptionHandler}. */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }
}
