package vn.edu.huit.finance.core.Exception;

/** The notification does not exist or belongs to another user; callers cannot tell the two apart. */
public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException(String message) {
        super(message);
    }
}
