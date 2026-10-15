package vn.edu.huit.finance.core.Service.Cache;

import java.util.Set;

/**
 * What a write changed, as far as the cached statistics are concerned, and therefore which entries it made stale. The one
 * place that knows which statistics are computed from which data: a service that writes names the change, and
 * StatisticsCacheEvictor evicts accordingly. Budgets, categories, notifications, payment orders, passwords and tokens feed
 * none of the cached statistics and have no entry here (a transaction keeps the name of its category in its own column,
 * so even renaming a category leaves every cached dashboard right).
 */
public enum StatisticsChange {

    /** A transaction was created or deleted, or its amount, direction or date changed: the wallet and every total moved. */
    TRANSACTION_TOTALS(CacheNames.USER_CACHES, CacheNames.ADMIN_CACHES),
    /** Only the note, category or payment method of a transaction changed, which its owner's dashboard alone shows. */
    TRANSACTION_DETAILS(Set.of(CacheNames.USER_DASHBOARD), Set.of()),
    /** A receipt changed status: its owner's dashboard shows the status, the admin summary counts receipts by status. */
    RECEIPT_STATUS(Set.of(CacheNames.USER_DASHBOARD), Set.of(CacheNames.ADMIN_DASHBOARD_SUMMARY)),
    /**
     * What a receipt says changed, and its status with it (its owner corrected it, or a retry brought a new reading): the
     * vendor name feeds its owner's popular vendors on top of everything a status change touches.
     */
    RECEIPT_CONTENT(CacheNames.USER_CACHES, Set.of(CacheNames.ADMIN_DASHBOARD_SUMMARY)),
    /** An account was created: the user count and the low-balance list (a new wallet is empty) of the admin summary. */
    ACCOUNT_CREATED(Set.of(), Set.of(CacheNames.ADMIN_DASHBOARD_SUMMARY)),
    /** The name or phone of an account changed: the low-balance list and the top spenders show them. */
    ACCOUNT_PROFILE(Set.of(), Set.of(CacheNames.ADMIN_DASHBOARD_SUMMARY, CacheNames.ADMIN_TOP_SPENDERS)),
    /** An account was deleted, and its transactions and receipts with it (User.transactions cascades). */
    ACCOUNT_DELETED(CacheNames.USER_CACHES, CacheNames.ADMIN_CACHES),
    /** The whole database was replaced by a backup: every entry of every cache, whoever it belongs to. */
    DATABASE_RESTORED(Set.of(), CacheNames.ALL_CACHES);

    private final Set<String> ownerCaches;
    private final Set<String> clearedCaches;

    StatisticsChange(Set<String> ownerCaches, Set<String> clearedCaches) {
        this.ownerCaches = ownerCaches;
        this.clearedCaches = clearedCaches;
    }

    /** Per-user caches in which only the entry of the user the change is about goes. */
    public Set<String> ownerCaches() {
        return ownerCaches;
    }

    /** Caches that go as a whole. */
    public Set<String> clearedCaches() {
        return clearedCaches;
    }
}
