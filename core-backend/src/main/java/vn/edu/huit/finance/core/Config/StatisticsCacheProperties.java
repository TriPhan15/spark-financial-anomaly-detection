package vn.edu.huit.finance.core.Config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Settings of the Redis statistics caches (prefix {@code app.cache}), defaulted in redis-cache.properties and overridden
 * by the CACHE_* environment variables. Checked at startup: every TTL must be at least a second (an entry that never
 * expires could serve a stale balance forever), and the jitter can shorten a TTL by half at most.
 *
 * @param keyPrefix        application and environment part of every key; blank means ledgermind:&lt;active profiles&gt;
 * @param ttl              the longest an entry of each cache lives
 * @param ttlJitterPercent how much shorter than its TTL an entry may randomly live, so entries written together do not
 *                         all expire together
 */
@ConfigurationProperties(prefix = "app.cache")
public record StatisticsCacheProperties(String keyPrefix, Ttl ttl, int ttlJitterPercent) {

    static final int MAX_TTL_JITTER_PERCENT = 50;

    public StatisticsCacheProperties {
        if (ttl == null) {
            throw new IllegalArgumentException("app.cache.ttl must be set");
        }
        if (ttlJitterPercent < 0 || ttlJitterPercent > MAX_TTL_JITTER_PERCENT) {
            throw new IllegalArgumentException("app.cache.ttl-jitter-percent must be between 0 and "
                    + MAX_TTL_JITTER_PERCENT + ", was " + ttlJitterPercent);
        }
    }

    /** One TTL per cache; a bare number means seconds. */
    public record Ttl(
            @DurationUnit(ChronoUnit.SECONDS) Duration userDashboard,
            @DurationUnit(ChronoUnit.SECONDS) Duration userPopularVendors,
            @DurationUnit(ChronoUnit.SECONDS) Duration adminDashboardSummary,
            @DurationUnit(ChronoUnit.SECONDS) Duration adminTopSpenders,
            @DurationUnit(ChronoUnit.SECONDS) Duration adminStatistics) {

        private static final Duration MIN_TTL = Duration.ofSeconds(1);

        public Ttl {
            requireFinite("user-dashboard", userDashboard);
            requireFinite("user-popular-vendors", userPopularVendors);
            requireFinite("admin-dashboard-summary", adminDashboardSummary);
            requireFinite("admin-top-spenders", adminTopSpenders);
            requireFinite("admin-statistics", adminStatistics);
        }

        private static void requireFinite(String cache, Duration ttl) {
            if (ttl == null || ttl.compareTo(MIN_TTL) < 0) {
                throw new IllegalArgumentException("app.cache.ttl." + cache + " must be at least " + MIN_TTL.toSeconds()
                        + " second, was " + ttl);
            }
        }
    }
}
