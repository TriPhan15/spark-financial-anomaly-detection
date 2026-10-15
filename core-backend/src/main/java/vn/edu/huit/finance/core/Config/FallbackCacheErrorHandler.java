package vn.edu.huit.finance.core.Config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.lang.Nullable;

/**
 * Redis only caches what PostgreSQL holds, so no cache failure (Redis down or slow, an entry that no longer reads back)
 * may fail a request: a read that fails counts as a miss and the statistics are read from the database, a write that
 * fails leaves the result uncached, and an eviction that fails leaves the entry to expire with its TTL. Each failure is
 * logged with the cache, the key and the type and message of the exception: never the cached value, and no stack trace
 * for every request of an outage.
 */
@Slf4j
class FallbackCacheErrorHandler implements CacheErrorHandler {

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Cache read failed, reading from the database instead, cache={}, key={}, error={}",
                cache.getName(), key, describe(exception));
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, @Nullable Object value) {
        log.warn("Cache write failed, the result is served without being cached, cache={}, key={}, error={}",
                cache.getName(), key, describe(exception));
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.error("Cache eviction failed, the entry stays until its TTL expires, cache={}, key={}, error={}",
                cache.getName(), key, describe(exception));
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.error("Cache clear failed, its entries stay until their TTL expires, cache={}, error={}",
                cache.getName(), describe(exception));
    }

    /** The exception and, when it wraps one, the cause that says what really happened ("Currently not connected..."). */
    static String describe(RuntimeException exception) {
        String described = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
        return cause == exception
                ? described
                : described + " (" + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")";
    }
}
