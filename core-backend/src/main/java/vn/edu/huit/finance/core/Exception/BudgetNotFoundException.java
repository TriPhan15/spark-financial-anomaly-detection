package vn.edu.huit.finance.core.Exception;

/** The budget does not exist or belongs to another user; callers cannot tell the two apart. */
public class BudgetNotFoundException extends RuntimeException {

    public BudgetNotFoundException(String message) {
        super(message);
    }
}
