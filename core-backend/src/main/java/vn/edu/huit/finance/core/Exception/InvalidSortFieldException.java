package vn.edu.huit.finance.core.Exception;

/** A sort field that is not one of the fields a client may sort transactions by. */
public class InvalidSortFieldException extends RuntimeException {

    public InvalidSortFieldException(String message) {
        super(message);
    }
}
