package vn.edu.huit.finance.core.Service.Cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static vn.edu.huit.finance.core.Service.Cache.CacheNames.*;

/**
 * Which entries go for each event, on in-memory caches. That it happens only after a commit, on a real Redis shared by two
 * instances, is proven by StatisticsCacheTransactionTest and RedisStatisticsCacheIntegrationTest.
 */
class StatisticsCacheEvictorTest {

    private static final Long ALICE_ID = 1L;
    private static final Long BOB_ID = 2L;
    private static final String SUMMARY_KEY = "2026-09:50000";
    private static final String TOP_SPENDERS_KEY = "0:5";
    private static final String CHART_KEY = "monthly-transactions:2026-09:6";

    private final CacheErrorHandler errorHandler = mock(CacheErrorHandler.class);
    private SimpleCacheManager cacheManager;
    private StatisticsCacheEvictor evictor;

    @BeforeEach
    void everyCacheHoldsEntries() {
        cacheManager = cacheManagerOf(ALL_CACHES.stream().map(name -> (Cache) new ConcurrentMapCache(name)).toList());
        evictor = new StatisticsCacheEvictor(cacheManager, errorHandler);
        for (String name : USER_CACHES) {
            cache(name).put(ALICE_ID, "alice");
            cache(name).put(BOB_ID, "bob");
        }
        cache(ADMIN_DASHBOARD_SUMMARY).put(SUMMARY_KEY, "summary");
        cache(ADMIN_TOP_SPENDERS).put(TOP_SPENDERS_KEY, "top");
        cache(ADMIN_STATISTICS).put(CHART_KEY, "chart");
    }

    private static SimpleCacheManager cacheManagerOf(List<Cache> caches) {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(caches);
        manager.afterPropertiesSet();
        return manager;
    }

    private Cache cache(String name) {
        return cacheManager.getCache(name);
    }

    private boolean holds(String name, Object key) {
        return cache(name).get(key) != null;
    }

    private boolean adminFiguresAreAllThere() {
        return holds(ADMIN_DASHBOARD_SUMMARY, SUMMARY_KEY) && holds(ADMIN_TOP_SPENDERS, TOP_SPENDERS_KEY)
                && holds(ADMIN_STATISTICS, CHART_KEY);
    }

    @Test
    void aTransactionOfAliceEvictsHerOwnEntriesAndEveryAdminFigureButNothingOfBob() {
        evictor.onStatisticsChanged(StatisticsChangedEvent.of(StatisticsChange.TRANSACTION_TOTALS, ALICE_ID));

        assertFalse(holds(USER_DASHBOARD, ALICE_ID));
        assertFalse(holds(USER_POPULAR_VENDORS, ALICE_ID));
        assertTrue(holds(USER_DASHBOARD, BOB_ID));
        assertTrue(holds(USER_POPULAR_VENDORS, BOB_ID));
        assertFalse(holds(ADMIN_DASHBOARD_SUMMARY, SUMMARY_KEY));
        assertFalse(holds(ADMIN_TOP_SPENDERS, TOP_SPENDERS_KEY));
        assertFalse(holds(ADMIN_STATISTICS, CHART_KEY));
    }

    @Test
    void aNewNoteOnATransactionOfAliceOnlyEvictsHerDashboardAndNoAdminFigure() {
        evictor.onStatisticsChanged(StatisticsChangedEvent.of(StatisticsChange.TRANSACTION_DETAILS, ALICE_ID));

        assertFalse(holds(USER_DASHBOARD, ALICE_ID));
        assertTrue(holds(USER_POPULAR_VENDORS, ALICE_ID));
        assertTrue(holds(USER_DASHBOARD, BOB_ID));
        assertTrue(adminFiguresAreAllThere());
    }

    @Test
    void aNewAccountOnlyClearsTheAdminSummaryAndNoUsersEntry() {
        evictor.onStatisticsChanged(StatisticsChangedEvent.of(StatisticsChange.ACCOUNT_CREATED));

        assertFalse(holds(ADMIN_DASHBOARD_SUMMARY, SUMMARY_KEY));
        assertTrue(holds(ADMIN_TOP_SPENDERS, TOP_SPENDERS_KEY));
        assertTrue(holds(ADMIN_STATISTICS, CHART_KEY));
        for (String name : USER_CACHES) {
            assertTrue(holds(name, ALICE_ID) && holds(name, BOB_ID), name);
        }
    }

    @Test
    void aChangeAboutNoOneInParticularEvictsNoUsersEntry() {
        evictor.onStatisticsChanged(StatisticsChangedEvent.of(StatisticsChange.RECEIPT_STATUS));

        assertTrue(holds(USER_DASHBOARD, ALICE_ID) && holds(USER_DASHBOARD, BOB_ID));
        assertFalse(holds(ADMIN_DASHBOARD_SUMMARY, SUMMARY_KEY));
    }

    @Test
    void aRestoredDatabaseEmptiesEveryCache() {
        evictor.onStatisticsChanged(StatisticsChangedEvent.of(StatisticsChange.DATABASE_RESTORED));

        for (String name : ALL_CACHES) {
            assertTrue(((ConcurrentMapCache) cache(name)).getNativeCache().isEmpty(), name);
        }
    }

    @Test
    void aCacheThatCannotBeReachedIsReportedAndTheOthersAreStillEvictedWithoutAnyExceptionEscaping() {
        Cache unreachable = mock(Cache.class);
        when(unreachable.getName()).thenReturn(USER_DASHBOARD);
        RedisConnectionFailureException down = new RedisConnectionFailureException("Unable to connect to Redis");
        doThrow(down).when(unreachable).evict(any());
        List<Cache> caches = new ArrayList<>(ALL_CACHES.stream().filter(name -> !name.equals(USER_DASHBOARD))
                .map(name -> (Cache) new ConcurrentMapCache(name)).toList());
        caches.add(unreachable);
        cacheManager = cacheManagerOf(caches);
        cache(USER_POPULAR_VENDORS).put(ALICE_ID, "alice");
        cache(ADMIN_TOP_SPENDERS).put(TOP_SPENDERS_KEY, "top");
        evictor = new StatisticsCacheEvictor(cacheManager, errorHandler);

        assertDoesNotThrow(() -> evictor.onStatisticsChanged(
                StatisticsChangedEvent.of(StatisticsChange.TRANSACTION_TOTALS, ALICE_ID)));

        verify(errorHandler).handleCacheEvictError(down, unreachable, ALICE_ID);
        assertFalse(holds(USER_POPULAR_VENDORS, ALICE_ID));
        assertFalse(holds(ADMIN_TOP_SPENDERS, TOP_SPENDERS_KEY));
    }

    @Test
    void aClearThatFailsIsReportedToo() {
        Cache unreachable = mock(Cache.class);
        when(unreachable.getName()).thenReturn(ADMIN_DASHBOARD_SUMMARY);
        RedisConnectionFailureException down = new RedisConnectionFailureException("Unable to connect to Redis");
        doThrow(down).when(unreachable).clear();
        List<Cache> caches = new ArrayList<>(ALL_CACHES.stream().filter(name -> !name.equals(ADMIN_DASHBOARD_SUMMARY))
                .map(name -> (Cache) new ConcurrentMapCache(name)).toList());
        caches.add(unreachable);
        evictor = new StatisticsCacheEvictor(cacheManagerOf(caches), errorHandler);

        assertDoesNotThrow(() -> evictor.onStatisticsChanged(StatisticsChangedEvent.of(StatisticsChange.ACCOUNT_CREATED)));

        verify(errorHandler).handleCacheClearError(down, unreachable);
    }

    @Test
    void theListenerRunsAfterTheCommitAndOutsideATransactionAtOnce() throws NoSuchMethodException {
        Method listener = StatisticsCacheEvictor.class.getMethod("onStatisticsChanged", StatisticsChangedEvent.class);

        TransactionalEventListener annotation = AnnotatedElementUtils.findMergedAnnotation(listener, TransactionalEventListener.class);

        assertNotNull(annotation);
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
        assertTrue(annotation.fallbackExecution(), "a write that committed on its own (no transaction) must still evict");
    }
}
