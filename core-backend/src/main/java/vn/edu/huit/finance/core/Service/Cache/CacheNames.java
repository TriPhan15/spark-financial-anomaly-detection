package vn.edu.huit.finance.core.Service.Cache;

import java.util.Set;

/**
 * The Redis caches of the statistics, one per kind of data, so that evicting one never touches another. The per-user
 * caches are keyed by the user id alone; the admin caches by everything their result depends on (period, filter, page,
 * limit). Config/CacheConfig configures exactly these names and refuses any other.
 */
public final class CacheNames {

    /** Part of every key: raise it whenever a cached DTO changes shape, so that no instance reads what older code wrote. */
    public static final String SCHEMA_VERSION = "v1";

    public static final String USER_DASHBOARD = "user-dashboard";
    public static final String USER_POPULAR_VENDORS = "user-popular-vendors";
    public static final String ADMIN_DASHBOARD_SUMMARY = "admin-dashboard-summary";
    public static final String ADMIN_TOP_SPENDERS = "admin-top-spenders";
    public static final String ADMIN_STATISTICS = "admin-statistics";

    /** Keyed by user id: a change of one user's data evicts that user's entry and nobody else's. */
    public static final Set<String> USER_CACHES = Set.of(USER_DASHBOARD, USER_POPULAR_VENDORS);
    /** Aggregates across every user: a change clears the whole cache (with SCAN, never KEYS). */
    public static final Set<String> ADMIN_CACHES = Set.of(ADMIN_DASHBOARD_SUMMARY, ADMIN_TOP_SPENDERS, ADMIN_STATISTICS);
    public static final Set<String> ALL_CACHES = Set.of(
            USER_DASHBOARD, USER_POPULAR_VENDORS, ADMIN_DASHBOARD_SUMMARY, ADMIN_TOP_SPENDERS, ADMIN_STATISTICS);

    private CacheNames() {
    }
}
