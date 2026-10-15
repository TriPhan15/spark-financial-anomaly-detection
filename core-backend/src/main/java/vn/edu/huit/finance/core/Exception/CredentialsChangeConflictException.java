package vn.edu.huit.finance.core.Exception;

/**
 * A password change or reset found the credentials already changed by another request, so it wrote nothing. Mapped to
 * 409 by {@link GlobalExceptionHandler}; the client can simply try again.
 */
public class CredentialsChangeConflictException extends RuntimeException {

    public CredentialsChangeConflictException(String message) {
        super(message);
    }
}
