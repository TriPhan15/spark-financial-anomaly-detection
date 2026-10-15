package vn.edu.huit.finance.core.Exception;

/** A role that is missing or blank, so it cannot be turned into "ROLE_&lt;NAME&gt;". */
public class InvalidRoleException extends RuntimeException {

    public InvalidRoleException(String message) {
        super(message);
    }
}
