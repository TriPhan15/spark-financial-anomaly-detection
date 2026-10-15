package vn.edu.huit.finance.core.Entity;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * What a notification is about. Only the budget levels have a {@link #thresholdPercent}; BudgetAlertService goes through
 * {@link #budgetLevels()}, never through {@link #values()}.
 * The name is what notifications.type stores, and that column has a CHECK constraint listing every name: a new constant
 * needs that constraint widened by hand, because ddl-auto=update never changes an existing constraint.
 */
public enum NotificationType {
    /** Spending has reached 80% of a budget. */
    BUDGET_WARNING(80),
    /** Spending has reached, or passed, 100% of a budget. */
    BUDGET_EXCEEDED(100),
    /** About one transaction of the user; the notification carries its transactionId. */
    TRANSACTION_ALERT(null),
    /** A receipt of the user has been processed; the notification carries its receiptId. */
    RECEIPT_PROCESSED(null);

    private static final List<NotificationType> BUDGET_LEVELS = Arrays.stream(values())
            .filter(NotificationType::isBudgetLevel)
            .sorted(Comparator.comparing(NotificationType::getThresholdPercent))
            .toList();

    /** The share of a budget that has to be spent for this notification to be raised; null when it is not a budget level. */
    private final Integer thresholdPercent;

    NotificationType(Integer thresholdPercent) {
        this.thresholdPercent = thresholdPercent;
    }

    public Integer getThresholdPercent() {
        return thresholdPercent;
    }

    public boolean isBudgetLevel() {
        return thresholdPercent != null;
    }

    /** The budget levels, the lowest threshold first. */
    public static List<NotificationType> budgetLevels() {
        return BUDGET_LEVELS;
    }
}
