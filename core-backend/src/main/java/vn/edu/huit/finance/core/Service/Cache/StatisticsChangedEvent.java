package vn.edu.huit.finance.core.Service.Cache;

import java.util.Objects;

/**
 * Published by a write whose data some cached statistics are computed from, inside the database transaction of that
 * write. StatisticsCacheEvictor evicts the stale entries once that transaction has committed, and not at all if it rolls
 * back.
 *
 * @param change what the write changed
 * @param userId whose own entries (dashboard, popular vendors) are stale; null when the change is not about one user
 */
public record StatisticsChangedEvent(StatisticsChange change, Long userId) {

    public StatisticsChangedEvent {
        Objects.requireNonNull(change, "change");
    }

    /** A change of the data of one user. */
    public static StatisticsChangedEvent of(StatisticsChange change, Long userId) {
        return new StatisticsChangedEvent(change, userId);
    }

    /** A change that is not about the own entries of one user. */
    public static StatisticsChangedEvent of(StatisticsChange change) {
        return new StatisticsChangedEvent(change, null);
    }
}
