package vn.edu.huit.finance.core.Config;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.cache.CacheMetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.edu.huit.finance.core.Dto.AdminDashboardSummaryDto;
import vn.edu.huit.finance.core.Dto.AppDashboardResponseDto;
import vn.edu.huit.finance.core.Dto.MonthlyTransactionCountDto;
import vn.edu.huit.finance.core.Dto.PopularVendorResponseDto;
import vn.edu.huit.finance.core.Dto.TopSpenderDto;
import vn.edu.huit.finance.core.Entity.Transaction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Repository.ReceiptRepository;
import vn.edu.huit.finance.core.Repository.TransactionRepository;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Service.AdminStatisticsService;
import vn.edu.huit.finance.core.Service.Cache.StatisticsCacheEvictor;
import vn.edu.huit.finance.core.Service.Cache.StatisticsChange;
import vn.edu.huit.finance.core.Service.Cache.StatisticsChangedEvent;
import vn.edu.huit.finance.core.Service.UserStatisticsService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static vn.edu.huit.finance.core.Service.Cache.CacheNames.*;

/**
 * The statistics caches on a real Redis (Testcontainers). Every "instance" is an application context of its own, with
 * the real CacheConfig, statistics services and evictor over mocked repositories: two of them against one Redis behave
 * like two core-api containers. The Redis runs with KEYS disabled, so every clear that works here went through SCAN.
 * Each test uses a key prefix of its own, so no test sees another's entries.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisStatisticsCacheIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = RedisTestContainers.newRedis();

    private static final Long ALICE_ID = 1L;
    private static final Long BOB_ID = 2L;
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final BigDecimal THRESHOLD = new BigDecimal("50000");
    private static final long DASHBOARD_TTL_MILLIS = 45_000;
    private static final long DASHBOARD_SHORTEST_TTL_MILLIS = 40_500;

    private String prefix;

    @BeforeEach
    void aPrefixOfItsOwn() {
        prefix = "ledgermind:it-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** One instance of the API as far as the cache goes, with its own connection to the shared Redis. */
    private static ApplicationContextRunner instance(String keyPrefix) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withUserConfiguration(CacheConfig.class, UserStatisticsService.class, AdminStatisticsService.class,
                        StatisticsCacheEvictor.class)
                // What @EnableTransactionManagement registers in the application: without a transaction around the
                // publisher, the evictor runs at once (fallbackExecution), exactly as it does there.
                .withBean(TransactionalEventListenerFactory.class)
                .withBean(TransactionRepository.class, () -> mock(TransactionRepository.class))
                .withBean(UserRepository.class, () -> mock(UserRepository.class))
                .withBean(ReceiptRepository.class, () -> mock(ReceiptRepository.class))
                .withPropertyValues(RedisTestContainers.connectionProperties(REDIS))
                .withPropertyValues("app.cache.key-prefix=" + keyPrefix);
    }

    private String key(String cache, String key) {
        return prefix + ":v1:" + cache + ":" + key;
    }

    private static TransactionRepository transactions(AssertableApplicationContext context) {
        return context.getBean(TransactionRepository.class);
    }

    private static UserStatisticsService userStatistics(AssertableApplicationContext context) {
        return context.getBean(UserStatisticsService.class);
    }

    private static AdminStatisticsService adminStatistics(AssertableApplicationContext context) {
        return context.getBean(AdminStatisticsService.class);
    }

    private static StringRedisTemplate redis(AssertableApplicationContext context) {
        return context.getBean(StringRedisTemplate.class);
    }

    private static Set<String> keysMatching(AssertableApplicationContext context, String pattern) {
        Set<String> keys = new HashSet<>();
        try (Cursor<String> cursor = redis(context).scan(ScanOptions.scanOptions().match(pattern).count(100).build())) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }

    private Set<String> ownKeys(AssertableApplicationContext context) {
        return keysMatching(context, prefix + ":*");
    }

    private static Transaction transactionOf(Long userId, String note) {
        User owner = new User();
        owner.setId(userId);
        Transaction transaction = new Transaction();
        transaction.setId(userId * 100);
        transaction.setUser(owner);
        transaction.setNote(note);
        transaction.setCategoryName("Ăn uống");
        transaction.setAmount(new BigDecimal("150000.00"));
        transaction.setExpense(true);
        transaction.setTransactionDate(LocalDateTime.of(2026, 9, 1, 8, 30, 15));
        return transaction;
    }

    /** What the database answers for this user's home page from now on. */
    private static void databaseHolds(TransactionRepository repository, Long userId, String note) {
        Transaction transaction = transactionOf(userId, note);
        when(repository.findByUserId(eq(userId), any(Pageable.class))).thenReturn(List.of(transaction));
        when(repository.findTopByUserIdOrderByAmountDesc(userId)).thenReturn(transaction);
        when(repository.findByUserIdAndVoucherCodeNotNull(userId)).thenReturn(List.of());
    }

    private static String firstTitle(AppDashboardResponseDto dashboard) {
        return dashboard.getRecentTransactions().get(0).getTitle();
    }

    // -------------------------------------------------------------------------------------------------------- miss / hit

    @Test
    void aMissReadsTheDatabaseOnceAndTheHitsAfterItComeFromRedisThroughTheProxy() {
        instance(prefix).run(context -> {
            UserStatisticsService statistics = userStatistics(context);
            assertTrue(AopUtils.isAopProxy(statistics), "@Cacheable only works through the Spring proxy");
            databaseHolds(transactions(context), ALICE_ID, "Bún bò");

            AppDashboardResponseDto miss = statistics.dashboardOf(ALICE_ID);
            AppDashboardResponseDto hit = statistics.dashboardOf(ALICE_ID);

            assertEquals(miss, hit);
            assertNotSame(miss, hit, "the second answer was read back from Redis");
            assertEquals("Bún bò", firstTitle(hit));
            verify(transactions(context), times(1)).findByUserId(eq(ALICE_ID), any(Pageable.class));
        });
    }

    @Test
    void theEntryIsPlainJsonUnderAKeyThatNamesTheEnvironmentTheSchemaTheCacheAndTheUserAndItExpires() {
        instance(prefix).run(context -> {
            databaseHolds(transactions(context), ALICE_ID, "Bún bò");

            userStatistics(context).dashboardOf(ALICE_ID);

            String key = key(USER_DASHBOARD, "1");
            assertEquals(Set.of(key), ownKeys(context));
            String json = redis(context).opsForValue().get(key);
            assertNotNull(json);
            assertTrue(json.startsWith("{") && json.contains("\"title\":\"Bún bò\"") && json.contains("\"isExpense\":true"), json);
            assertFalse(json.contains("@class"), json);
            long ttl = redis(context).getExpire(key, TimeUnit.MILLISECONDS);
            assertTrue(ttl > DASHBOARD_SHORTEST_TTL_MILLIS - 5_000 && ttl <= DASHBOARD_TTL_MILLIS, "ttl " + ttl);
        });
    }

    @Test
    void aliceNeverReadsBobsEntryNorBobAlices() {
        instance(prefix).run(context -> {
            databaseHolds(transactions(context), ALICE_ID, "Của Alice");
            databaseHolds(transactions(context), BOB_ID, "Của Bob");
            UserStatisticsService statistics = userStatistics(context);

            statistics.dashboardOf(ALICE_ID);
            AppDashboardResponseDto bobs = statistics.dashboardOf(BOB_ID);

            assertEquals("Của Bob", firstTitle(bobs), "Alice's entry must not answer Bob");
            assertEquals("Của Alice", firstTitle(statistics.dashboardOf(ALICE_ID)));
            assertEquals("Của Bob", firstTitle(statistics.dashboardOf(BOB_ID)));
            assertEquals(Set.of(key(USER_DASHBOARD, "1"), key(USER_DASHBOARD, "2")), ownKeys(context));
            verify(transactions(context), times(1)).findByUserId(eq(BOB_ID), any(Pageable.class));
        });
    }

    @Test
    void theAdminKeysHoldEveryParameterOfTheQuestionSoDifferentQuestionsNeverShareAnEntry() {
        instance(prefix).run(context -> {
            AdminStatisticsService admin = adminStatistics(context);

            admin.getDashboardSummary(SEPTEMBER, THRESHOLD);
            admin.getDashboardSummary(SEPTEMBER, new BigDecimal("50000.00"));
            admin.getDashboardSummary(YearMonth.of(2026, 8), THRESHOLD);
            admin.getDashboardSummary(SEPTEMBER, new BigDecimal("100000"));
            admin.getTopSpenders(0, 5);
            admin.getTopSpenders(0, 5);
            admin.getTopSpenders(1, 5);
            admin.getTopSpenders(0, 10);
            admin.getMonthlyTransactionCounts(SEPTEMBER, 6);
            admin.getMonthlyTransactionCounts(SEPTEMBER, 6);
            admin.getMonthlyTransactionCounts(SEPTEMBER, 3);

            assertEquals(Set.of(
                    key(ADMIN_DASHBOARD_SUMMARY, "2026-09:50000"),
                    key(ADMIN_DASHBOARD_SUMMARY, "2026-08:50000"),
                    key(ADMIN_DASHBOARD_SUMMARY, "2026-09:100000"),
                    key(ADMIN_TOP_SPENDERS, "0:5"),
                    key(ADMIN_TOP_SPENDERS, "1:5"),
                    key(ADMIN_TOP_SPENDERS, "0:10"),
                    key(ADMIN_STATISTICS, "monthly-transactions:2026-09:6"),
                    key(ADMIN_STATISTICS, "monthly-transactions:2026-09:3")), ownKeys(context));
            verify(context.getBean(UserRepository.class), times(3)).count();
            verify(transactions(context), times(3)).findTopSpenders(any(Pageable.class));
        });
    }

    @Test
    void everyAdminFigureReadsBackAsItsOwnClassWithEveryValue() {
        instance(prefix).run(context -> {
            User lowOnMoney = new User();
            lowOnMoney.setId(7L);
            lowOnMoney.setUsername("an");
            lowOnMoney.setPhone("0900000007");
            lowOnMoney.setWalletBalance(new BigDecimal("12000.00"));
            when(context.getBean(UserRepository.class).findByWalletBalanceLessThan(THRESHOLD)).thenReturn(List.of(lowOnMoney));
            when(context.getBean(UserRepository.class).count()).thenReturn(42L);
            when(transactions(context).sumTotalVolume()).thenReturn(new BigDecimal("1250000.50"));
            when(transactions(context).countByTransactionDateBetween(any(), any())).thenReturn(17L);
            when(context.getBean(ReceiptRepository.class).getReceiptStatusStatistics())
                    .thenReturn(List.<Object[]>of(new Object[]{"SUCCESS", 5L}));
            when(transactions(context).findTopSpenders(any(Pageable.class)))
                    .thenReturn(List.<Object[]>of(new Object[]{"an", new BigDecimal("900000.00")}));
            AdminStatisticsService admin = adminStatistics(context);

            AdminDashboardSummaryDto summary = admin.getDashboardSummary(SEPTEMBER, THRESHOLD);
            List<TopSpenderDto> top = admin.getTopSpenders(0, 5);
            List<MonthlyTransactionCountDto> chart = admin.getMonthlyTransactionCounts(SEPTEMBER, 6);

            assertEquals(summary, admin.getDashboardSummary(SEPTEMBER, THRESHOLD));
            assertEquals(top, admin.getTopSpenders(0, 5));
            assertSame(TopSpenderDto.class, admin.getTopSpenders(0, 5).get(0).getClass());
            List<MonthlyTransactionCountDto> chartBack = admin.getMonthlyTransactionCounts(SEPTEMBER, 6);
            assertEquals(chart, chartBack);
            assertEquals(YearMonth.of(2026, 4), chartBack.get(0).getMonth());
            verify(transactions(context), times(1)).sumTotalVolume();
            verify(transactions(context), times(1)).findTopSpenders(any(Pageable.class));
        });
    }

    @Test
    void thePopularVendorsReadBackAsAListOfTheirOwnClass() {
        instance(prefix).run(context -> {
            when(transactions(context).findPopularVendors(ALICE_ID))
                    .thenReturn(List.<Object[]>of(new Object[]{"Highlands", 5L}, new Object[]{"Circle K", 2L}));

            List<PopularVendorResponseDto> miss = userStatistics(context).popularVendorsOf(ALICE_ID);
            List<PopularVendorResponseDto> hit = userStatistics(context).popularVendorsOf(ALICE_ID);

            assertEquals(miss, hit);
            assertSame(PopularVendorResponseDto.class, hit.get(0).getClass());
            verify(transactions(context), times(1)).findPopularVendors(ALICE_ID);
        });
    }

    @Test
    void nullNeverReachesRedis() {
        instance(prefix).run(context -> {
            RedisCacheManager manager = context.getBean(RedisCacheManager.class);

            assertThrows(IllegalArgumentException.class, () -> manager.getCache(USER_DASHBOARD).put(ALICE_ID, null));

            assertEquals(Set.of(), ownKeys(context));
        });
    }

    @Test
    void aComputationThatFailsCachesNothingAndTheNextRequestComputesAgain() {
        instance(prefix).run(context -> {
            when(transactions(context).findTopByUserIdOrderByAmountDesc(ALICE_ID))
                    .thenThrow(new DataAccessResourceFailureException("database down"));

            assertThrows(DataAccessResourceFailureException.class, () -> userStatistics(context).dashboardOf(ALICE_ID));
            assertEquals(Set.of(), ownKeys(context));

            reset(transactions(context));
            databaseHolds(transactions(context), ALICE_ID, "Bún bò");
            assertEquals("Bún bò", firstTitle(userStatistics(context).dashboardOf(ALICE_ID)));
            assertEquals(Set.of(key(USER_DASHBOARD, "1")), ownKeys(context));
        });
    }

    @Test
    void hitsMissesWritesAndEvictionsAreCountedPerCacheForTheMetrics() {
        // The same metrics auto-configurations as in the application (the composite one fixes their order).
        instance(prefix)
                .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class, SimpleMetricsExportAutoConfiguration.class,
                        CacheMetricsAutoConfiguration.class))
                .run(context -> {
                    databaseHolds(transactions(context), ALICE_ID, "Bún bò");

                    userStatistics(context).dashboardOf(ALICE_ID);
                    userStatistics(context).dashboardOf(ALICE_ID);
                    context.publishEvent(StatisticsChangedEvent.of(StatisticsChange.TRANSACTION_DETAILS, ALICE_ID));

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertEquals(1.0, registry.get("cache.gets").tags("cache", USER_DASHBOARD, "result", "miss")
                            .functionCounter().count());
                    assertEquals(1.0, registry.get("cache.gets").tags("cache", USER_DASHBOARD, "result", "hit")
                            .functionCounter().count());
                    assertEquals(1.0, registry.get("cache.puts").tags("cache", USER_DASHBOARD).functionCounter().count());
                    assertEquals(1.0, registry.get("cache.removals").tags("cache", USER_DASHBOARD).functionCounter().count());
                    assertEquals(0.0, registry.get("cache.gets").tags("cache", ADMIN_TOP_SPENDERS, "result", "hit")
                            .functionCounter().count());
                });
    }

    // -------------------------------------------------------------------------------------------- two instances, one Redis

    @Test
    void twoInstancesShareOneRedisSoWhatOneComputedTheOtherReads() {
        instance(prefix).run(first -> instance(prefix).run(second -> {
            databaseHolds(transactions(first), ALICE_ID, "Bún bò");

            AppDashboardResponseDto written = userStatistics(first).dashboardOf(ALICE_ID);
            AppDashboardResponseDto read = userStatistics(second).dashboardOf(ALICE_ID);

            assertEquals(written, read);
            verifyNoInteractions(transactions(second));
        }));
    }

    @Test
    void anEvictionOnOneInstanceMakesTheOtherReadTheDatabaseAgain() {
        instance(prefix).run(first -> instance(prefix).run(second -> {
            databaseHolds(transactions(first), ALICE_ID, "Bún bò");
            userStatistics(first).dashboardOf(ALICE_ID);
            userStatistics(second).dashboardOf(ALICE_ID);
            // The first instance saved a change, and this is what the database now answers to either of them.
            databaseHolds(transactions(second), ALICE_ID, "Bún chả");

            first.publishEvent(StatisticsChangedEvent.of(StatisticsChange.TRANSACTION_TOTALS, ALICE_ID));

            assertEquals("Bún chả", firstTitle(userStatistics(second).dashboardOf(ALICE_ID)));
            verify(transactions(second), times(1)).findByUserId(eq(ALICE_ID), any(Pageable.class));
        }));
    }

    @Test
    void environmentsNeverReadNorEvictEachOthersEntries() {
        String staging = prefix + "-staging";
        String production = prefix + "-prod";
        instance(staging).run(stagingApi -> instance(production).run(productionApi -> {
            databaseHolds(transactions(stagingApi), ALICE_ID, "Staging");
            databaseHolds(transactions(productionApi), ALICE_ID, "Production");
            userStatistics(stagingApi).dashboardOf(ALICE_ID);
            adminStatistics(stagingApi).getTopSpenders(0, 5);

            assertEquals("Production", firstTitle(userStatistics(productionApi).dashboardOf(ALICE_ID)));
            productionApi.publishEvent(StatisticsChangedEvent.of(StatisticsChange.DATABASE_RESTORED));

            assertEquals(Set.of(staging + ":v1:user-dashboard:1", staging + ":v1:admin-top-spenders:0:5"),
                    keysMatching(stagingApi, staging + ":*"));
            assertEquals(Set.of(), keysMatching(productionApi, production + ":*"));
        }));
    }

    // --------------------------------------------------------------------------------------------------------- eviction

    @Test
    void eachChangeEvictsOnlyWhatItMadeStaleAndEveryClearGoesThroughScan() {
        instance(prefix).run(context -> {
            databaseHolds(transactions(context), ALICE_ID, "Của Alice");
            databaseHolds(transactions(context), BOB_ID, "Của Bob");
            UserStatisticsService users = userStatistics(context);
            AdminStatisticsService admin = adminStatistics(context);
            for (Long userId : List.of(ALICE_ID, BOB_ID)) {
                users.dashboardOf(userId);
                users.popularVendorsOf(userId);
            }
            admin.getDashboardSummary(SEPTEMBER, THRESHOLD);
            admin.getTopSpenders(0, 5);
            admin.getMonthlyTransactionCounts(SEPTEMBER, 6);
            String otherEnvironment = "ledgermind:other-environment:v1:admin-top-spenders:0:5";
            redis(context).opsForValue().set(otherEnvironment, "[]");
            Set<String> keys = new HashSet<>(ownKeys(context));
            assertEquals(7, keys.size());

            context.publishEvent(StatisticsChangedEvent.of(StatisticsChange.ACCOUNT_CREATED));
            keys.remove(key(ADMIN_DASHBOARD_SUMMARY, "2026-09:50000"));
            assertEquals(keys, ownKeys(context), "a new account only clears the admin summary");

            context.publishEvent(StatisticsChangedEvent.of(StatisticsChange.TRANSACTION_DETAILS, ALICE_ID));
            keys.remove(key(USER_DASHBOARD, "1"));
            assertEquals(keys, ownKeys(context), "a new note only evicts its owner's dashboard");

            context.publishEvent(StatisticsChangedEvent.of(StatisticsChange.TRANSACTION_TOTALS, BOB_ID));
            keys.removeAll(Set.of(key(USER_DASHBOARD, "2"), key(USER_POPULAR_VENDORS, "2"),
                    key(ADMIN_TOP_SPENDERS, "0:5"), key(ADMIN_STATISTICS, "monthly-transactions:2026-09:6")));
            assertEquals(Set.of(key(USER_POPULAR_VENDORS, "1")), ownKeys(context), "Alice's vendors are not Bob's business");
            assertEquals(keys, ownKeys(context));

            context.publishEvent(StatisticsChangedEvent.of(StatisticsChange.DATABASE_RESTORED));
            assertEquals(Set.of(), ownKeys(context));
            assertEquals("[]", redis(context).opsForValue().get(otherEnvironment), "another environment's keys stay");
        });
    }

    // ---------------------------------------------------------------------------------------------- unreadable entries

    @Test
    void aCorruptEntryIsReadFromTheDatabaseInsteadAndOverwritten() {
        instance(prefix).run(context -> {
            databaseHolds(transactions(context), ALICE_ID, "Bún bò");
            redis(context).opsForValue().set(key(USER_DASHBOARD, "1"), "not json at all");

            assertEquals("Bún bò", firstTitle(userStatistics(context).dashboardOf(ALICE_ID)));

            String json = redis(context).opsForValue().get(key(USER_DASHBOARD, "1"));
            assertTrue(json.contains("\"title\":\"Bún bò\""), json);
        });
    }

    @Test
    void anEntryOfAnotherShapeNeverBecomesAClassCastExceptionButAReadFromTheDatabase() {
        instance(prefix).run(context -> {
            databaseHolds(transactions(context), ALICE_ID, "Bún bò");
            redis(context).opsForValue().set(key(USER_DASHBOARD, "1"), "[{\"username\":\"an\",\"totalSpent\":900000}]");

            AppDashboardResponseDto dashboard = assertDoesNotThrow(() -> userStatistics(context).dashboardOf(ALICE_ID));

            assertEquals("Bún bò", firstTitle(dashboard));
            verify(transactions(context), times(1)).findByUserId(eq(ALICE_ID), any(Pageable.class));
        });
    }
}
