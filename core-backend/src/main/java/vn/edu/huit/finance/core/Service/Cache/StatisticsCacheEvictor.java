package vn.edu.huit.finance.core.Service.Cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Evicts the cached statistics a write made stale, once the database transaction of that write has committed: a request
 * that misses the cache after the eviction reads what was committed, never the state before it, and a rollback evicts
 * nothing because nothing changed. An event published outside any transaction (a repository save that committed on its
 * own, a database restore) is handled at once. Every instance of the API shares the same Redis, so an eviction here is
 * seen by all of them. A failure to reach Redis is logged and never reaches the write, which has already committed; the
 * stale entry then expires with its TTL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatisticsCacheEvictor {

    private final CacheManager cacheManager;
    private final CacheErrorHandler cacheErrorHandler;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onStatisticsChanged(StatisticsChangedEvent event) {
        StatisticsChange change = event.change();
        if (event.userId() != null) {
            change.ownerCaches().forEach(cacheName -> evict(cacheName, event.userId()));
        }
        change.clearedCaches().forEach(this::clear);
        log.debug("Statistics caches evicted, change={}, userId={}", change, event.userId());
    }

    private void evict(String cacheName, Long userId) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            log.error("Statistics cache is not configured, nothing evicted, cache={}", cacheName);
            return;
        }
        try {
            cache.evict(userId);
        } catch (RuntimeException e) {
            cacheErrorHandler.handleCacheEvictError(e, cache, userId);
        }
    }

    private void clear(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            log.error("Statistics cache is not configured, nothing cleared, cache={}", cacheName);
            return;
        }
        try {
            cache.clear();
        } catch (RuntimeException e) {
            cacheErrorHandler.handleCacheClearError(e, cache);
        }
    }
}
