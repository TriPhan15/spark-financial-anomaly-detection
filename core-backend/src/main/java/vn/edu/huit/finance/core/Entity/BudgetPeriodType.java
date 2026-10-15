package vn.edu.huit.finance.core.Entity;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** How long a budget lasts: its date range may not be longer than {@code maxDays}. */
public enum BudgetPeriodType {
    WEEK(7),
    MONTH(31);

    private final int maxDays;

    BudgetPeriodType(int maxDays) {
        this.maxDays = maxDays;
    }

    /** True when startDate..endDate, both days included, is no longer than this period type. */
    public boolean allowsRange(LocalDate startDate, LocalDate endDate) {
        return ChronoUnit.DAYS.between(startDate, endDate) + 1 <= maxDays;
    }
}
