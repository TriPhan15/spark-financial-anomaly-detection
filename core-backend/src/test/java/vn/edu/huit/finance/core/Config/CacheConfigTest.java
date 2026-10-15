package vn.edu.huit.finance.core.Config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.autoconfigure.data.redis.RedisHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.data.redis.RedisReactiveHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisKeyValueTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.SerializationException;
import vn.edu.huit.finance.core.Dto.AdminDashboardSummaryDto;
import vn.edu.huit.finance.core.Dto.AppDashboardResponseDto;
import vn.edu.huit.finance.core.Dto.LowBalanceUserDto;
import vn.edu.huit.finance.core.Dto.MonthlyTransactionCountDto;
import vn.edu.huit.finance.core.Dto.PopularVendorResponseDto;
import vn.edu.huit.finance.core.Dto.ReceiptStatusCountDto;
import vn.edu.huit.finance.core.Dto.TopSpenderDto;
import vn.edu.huit.finance.core.Dto.TransactionResponseDto;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.huit.finance.core.Service.Cache.CacheNames.*;

/**
 * How the caches are configured, without a Redis: CacheConfig builds everything without connecting (Lettuce connects on
 * first use). What happens against a real Redis is in RedisStatisticsCacheIntegrationTest and RedisOutageTest.
 */
class CacheConfigTest {

    private static final int SAMPLES = 200;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
            .withUserConfiguration(CacheConfig.class);

    private static Map<String, RedisCacheConfiguration> configurations(AssertableApplicationContext context) {
        return context.getBean(RedisCacheManager.class).getCacheConfigurations();
    }

    @SuppressWarnings("unchecked")
    private static SerializationPair<Object> valuesOf(AssertableApplicationContext context, String cacheName) {
        return (SerializationPair<Object>) configurations(context).get(cacheName).getValueSerializationPair();
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    // -------------------------------------------------------------------------------------------------------- the caches

    @Test
    void exactlyTheFiveStatisticsCachesExistAndAMisspeltNameCreatesNone() {
        runner.run(context -> {
            RedisCacheManager manager = context.getBean(RedisCacheManager.class);
            assertEquals(ALL_CACHES, Set.copyOf(manager.getCacheNames()));
            assertNull(manager.getCache("dashboard"), "an unknown name must not quietly create an untyped cache");
        });
    }

    @Test
    void theCacheErrorHandlerFallsBackInsteadOfFailingTheRequest() {
        runner.run(context -> {
            assertInstanceOf(FallbackCacheErrorHandler.class, context.getBean(CachingConfigurer.class).errorHandler());
            assertSame(context.getBean(CachingConfigurer.class).errorHandler(), context.getBean("cacheErrorHandler"),
                    "the evictor and the @Cacheable methods log failures the same way");
        });
    }

    @Test
    void aCacheFailureIsLoggedWithTheCauseThatExplainsItAndNothingElse() {
        RedisSystemException rejected = new RedisSystemException("Redis exception",
                new RedisException("Currently not connected. Commands are rejected."));

        assertEquals("RedisSystemException: Redis exception (RedisException: Currently not connected. Commands are rejected.)",
                FallbackCacheErrorHandler.describe(rejected));
        assertEquals("IllegalStateException: no cause", FallbackCacheErrorHandler.describe(new IllegalStateException("no cause")));
    }

    @Test
    void nullIsNeverStoredAndIsRefusedBeforeRedisIsEvenAsked() {
        runner.run(context -> {
            for (String name : ALL_CACHES) {
                assertFalse(configurations(context).get(name).getAllowCacheNullValues(), name);
                Cache cache = context.getBean(RedisCacheManager.class).getCache(name);
                assertThrows(IllegalArgumentException.class, () -> cache.put("1", null), name);
            }
        });
    }

    // ------------------------------------------------------------------------------------------------------------- TTLs

    @Test
    void everyEntryExpiresWithinItsCachesTtlAndTheJitterOnlyEverShortensIt() {
        Map<String, Duration> defaults = Map.of(
                USER_DASHBOARD, Duration.ofSeconds(45),
                USER_POPULAR_VENDORS, Duration.ofSeconds(60),
                ADMIN_DASHBOARD_SUMMARY, Duration.ofSeconds(45),
                ADMIN_TOP_SPENDERS, Duration.ofSeconds(60),
                ADMIN_STATISTICS, Duration.ofSeconds(60));
        runner.run(context -> defaults.forEach((name, ttl) -> {
            RedisCacheWriter.TtlFunction function = configurations(context).get(name).getTtlFunction();
            Duration shortest = ttl.multipliedBy(90).dividedBy(100);
            Set<Duration> seen = new HashSet<>();
            for (int sample = 0; sample < SAMPLES; sample++) {
                Duration entryTtl = function.getTimeToLive("1", "value");
                assertTrue(entryTtl.compareTo(shortest) >= 0 && entryTtl.compareTo(ttl) <= 0, name + ": " + entryTtl);
                seen.add(entryTtl);
            }
            assertTrue(seen.size() > 1, name + ": entries written together must not all expire together");
        }));
    }

    @Test
    void theTtlsComeFromTheCacheEnvironmentVariablesAndABareNumberMeansSeconds() {
        runner.withPropertyValues("CACHE_TTL_USER_DASHBOARD=30s", "CACHE_TTL_ADMIN_STATISTICS=90",
                        "CACHE_TTL_JITTER_PERCENT=0")
                .run(context -> {
                    assertEquals(Duration.ofSeconds(30),
                            configurations(context).get(USER_DASHBOARD).getTtlFunction().getTimeToLive("1", "v"));
                    assertEquals(Duration.ofSeconds(90),
                            configurations(context).get(ADMIN_STATISTICS).getTtlFunction().getTimeToLive("1", "v"));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"CACHE_TTL_ADMIN_TOP_SPENDERS=0", "CACHE_TTL_ADMIN_TOP_SPENDERS=-5s",
            "CACHE_TTL_ADMIN_TOP_SPENDERS=500ms"})
    void aTtlThatWouldNeverExpireOrIsBelowASecondStopsTheStartup(String setting) {
        runner.withPropertyValues(setting).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootMessage(context.getStartupFailure()).contains("admin-top-spenders"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "51"})
    void aJitterOutsideZeroToHalfStopsTheStartup(String percent) {
        runner.withPropertyValues("CACHE_TTL_JITTER_PERCENT=" + percent).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootMessage(context.getStartupFailure()).contains("ttl-jitter-percent"));
        });
    }

    @Test
    void withoutJitterTheTtlIsExactAndWithHalfItNeverDropsBelowHalf() {
        CacheConfig.JitteredTtl exact = new CacheConfig.JitteredTtl(Duration.ofSeconds(60), 0);
        CacheConfig.JitteredTtl half = new CacheConfig.JitteredTtl(Duration.ofSeconds(60), 50);

        for (int sample = 0; sample < SAMPLES; sample++) {
            assertEquals(Duration.ofSeconds(60), exact.getTimeToLive("1", "v"));
            Duration jittered = half.getTimeToLive("1", "v");
            assertTrue(jittered.compareTo(Duration.ofSeconds(30)) >= 0 && jittered.compareTo(Duration.ofSeconds(60)) <= 0);
        }
    }

    // ------------------------------------------------------------------------------------------------------------- keys

    @Test
    void withoutAPrefixTheKeysNameTheApplicationTheEnvironmentAndTheSchemaVersion() {
        runner.run(context -> assertEquals("ledgermind:default:v1:user-dashboard:",
                configurations(context).get(USER_DASHBOARD).getKeyPrefixFor(USER_DASHBOARD)));
        runner.withPropertyValues("spring.profiles.active=staging").run(context -> assertEquals(
                "ledgermind:staging:v1:admin-dashboard-summary:",
                configurations(context).get(ADMIN_DASHBOARD_SUMMARY).getKeyPrefixFor(ADMIN_DASHBOARD_SUMMARY)));
    }

    @Test
    void thePrefixComesFromCacheKeyPrefixAndTheSchemaVersionIsAlwaysAppended() {
        runner.withPropertyValues("CACHE_KEY_PREFIX=ledgermind:prod").run(context -> assertEquals(
                "ledgermind:prod:v1:admin-top-spenders:",
                configurations(context).get(ADMIN_TOP_SPENDERS).getKeyPrefixFor(ADMIN_TOP_SPENDERS)));
    }

    @Test
    void severalProfilesAreJoinedAndABlankPrefixMeansTheDefault() {
        assertEquals("ledgermind:prod-eu:v1", CacheConfig.versionedKeyPrefix(null, new String[]{"prod", "eu"}));
        assertEquals("ledgermind:default:v1", CacheConfig.versionedKeyPrefix("  ", new String[0]));
        assertEquals("shop:test:v1", CacheConfig.versionedKeyPrefix(" shop:test ", new String[]{"prod"}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ledger*mind", "ledgermind:[prod]", "ledger mind", "ledgermind?"})
    void aPrefixThatWouldTurnIntoAScanPatternStopsTheStartup(String prefix) {
        runner.withPropertyValues("CACHE_KEY_PREFIX=" + prefix).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootMessage(context.getStartupFailure()).contains("app.cache.key-prefix"));
        });
    }

    // ----------------------------------------------------------------------------------------------------------- values

    private static TransactionResponseDto transaction(long id, boolean expense, String receiptStatus) {
        return TransactionResponseDto.builder()
                .id(id)
                .title("Bún bò " + id)
                .amount(new BigDecimal("150000.00"))
                .date(LocalDateTime.of(2026, 9, 1, 8, 30, 15, 123_000_000))
                .category("Ăn uống")
                .isExpense(expense)
                .imageUrl(receiptStatus == null ? null : "https://img.example.com/" + id + ".jpg")
                .receiptStatus(receiptStatus)
                .build();
    }

    static Stream<Arguments> oneValueOfEveryCache() {
        AppDashboardResponseDto dashboard = AppDashboardResponseDto.builder()
                .recentTransactions(List.of(transaction(1, true, "PENDING"), transaction(2, false, null)))
                .biggestTransaction(transaction(3, false, "SUCCESS"))
                .saleTransactions(List.of())
                .build();
        return Stream.of(
                Arguments.of(USER_DASHBOARD, dashboard),
                Arguments.of(USER_POPULAR_VENDORS, List.of(
                        new PopularVendorResponseDto("Highlands", 5), new PopularVendorResponseDto("Circle K", 2))),
                Arguments.of(ADMIN_DASHBOARD_SUMMARY, new AdminDashboardSummaryDto(new BigDecimal("1250000.50"), 42, 17,
                        List.of(new LowBalanceUserDto(7L, "an", "0900000007", new BigDecimal("12000.00"))),
                        List.of(new ReceiptStatusCountDto("SUCCESS", 5), new ReceiptStatusCountDto("FAILED", 2)))),
                Arguments.of(ADMIN_TOP_SPENDERS, List.of(new TopSpenderDto("an", new BigDecimal("900000.00")))),
                Arguments.of(ADMIN_STATISTICS, List.of(
                        new MonthlyTransactionCountDto(YearMonth.of(2026, 8), 3),
                        new MonthlyTransactionCountDto(YearMonth.of(2026, 9), 17))));
    }

    @ParameterizedTest
    @MethodSource("oneValueOfEveryCache")
    void everyCacheWritesPlainJsonAndReadsBackTheSameClassWithTheSameValues(String cacheName, Object value) {
        runner.run(context -> {
            SerializationPair<Object> values = valuesOf(context, cacheName);
            String json = UTF_8.decode(values.write(value)).toString();

            Object back = values.read(values.write(value));

            assertEquals(value, back, cacheName);
            Object element = value instanceof List<?> list ? list.get(0) : value;
            Object elementBack = back instanceof List<?> list ? list.get(0) : back;
            assertSame(element.getClass(), elementBack.getClass(), cacheName);
            assertFalse(json.contains("@class") || json.contains("java.") || json.contains("vn.edu"),
                    cacheName + " must hold no type information: " + json);
        });
    }

    @Test
    void theHomePageKeepsTheExpenseFlagTheExactAmountAndTheDateTheWayTheApiWritesThem() {
        runner.run(context -> {
            SerializationPair<Object> values = valuesOf(context, USER_DASHBOARD);
            AppDashboardResponseDto dashboard = AppDashboardResponseDto.builder()
                    .recentTransactions(List.of(transaction(1, true, "PENDING")))
                    .saleTransactions(List.of())
                    .build();

            String json = UTF_8.decode(values.write(dashboard)).toString();

            assertTrue(json.contains("\"isExpense\":true"), json);
            assertTrue(json.contains("\"amount\":150000.00"), json);
            assertTrue(json.contains("\"date\":\"2026-09-01T08:30:15.123\""), json);
            assertTrue(json.contains("\"biggestTransaction\":null"), json);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"username\":\"an\",\"totalSpent\":900000}]",
            "{\"@class\":\"java.lang.ProcessBuilder\",\"command\":[\"id\"]}",
            "not json"})
    void anEntryOfAnotherShapeIsASerializationErrorNeverAnotherClassOrAClassCastException(String stored) {
        runner.run(context -> {
            SerializationPair<Object> dashboards = valuesOf(context, USER_DASHBOARD);

            assertThrows(SerializationException.class, () -> dashboards.read(ByteBuffer.wrap(stored.getBytes(UTF_8))));
        });
    }

    // ----------------------------------------------------------------------------------------------------- connection

    @Test
    void theConnectionComesFromTheRedisEnvironmentVariables() {
        runner.withPropertyValues("REDIS_HOST=redis", "REDIS_PORT=6380", "REDIS_PASSWORD=from-the-environment")
                .run(context -> {
                    RedisProperties properties = context.getBean(RedisProperties.class);
                    assertEquals("redis", properties.getHost());
                    assertEquals(6380, properties.getPort());
                    assertEquals("from-the-environment", properties.getPassword());
                    LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                    assertEquals("redis", factory.getHostName());
                    assertTrue(factory.getStandaloneConfiguration().getPassword().isPresent());
                });
    }

    @Test
    void withoutRedisPasswordNoPasswordIsSent() {
        runner.run(context -> assertFalse(context.getBean(LettuceConnectionFactory.class)
                .getStandaloneConfiguration().getPassword().isPresent()));
    }

    @Test
    void whileRedisIsDisconnectedCommandsFailAtOnceAndTheTimeoutsComeFromTheSettings() {
        runner.run(context -> {
            LettuceClientConfiguration client = context.getBean(LettuceConnectionFactory.class).getClientConfiguration();
            ClientOptions options = client.getClientOptions().orElseThrow();
            assertEquals(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS, options.getDisconnectedBehavior());
            assertTrue(options.isAutoReconnect(), "Lettuce keeps reconnecting in the background");
            assertEquals(Duration.ofSeconds(1), options.getSocketOptions().getConnectTimeout());
            assertEquals(Duration.ofMillis(500), client.getCommandTimeout());
        });
        runner.withPropertyValues("REDIS_CONNECT_TIMEOUT=2s", "REDIS_COMMAND_TIMEOUT=250ms").run(context -> {
            LettuceClientConfiguration client = context.getBean(LettuceConnectionFactory.class).getClientConfiguration();
            assertEquals(Duration.ofSeconds(2), client.getClientOptions().orElseThrow().getSocketOptions().getConnectTimeout());
            assertEquals(Duration.ofMillis(250), client.getCommandTimeout());
            assertEquals(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS,
                    client.getClientOptions().orElseThrow().getDisconnectedBehavior());
        });
    }

    @Test
    void aRedisOutageDoesNotTurnTheHealthOfTheApiDown() {
        ApplicationContextRunner withHealth = runner.withConfiguration(AutoConfigurations.of(
                RedisReactiveHealthContributorAutoConfiguration.class, RedisHealthContributorAutoConfiguration.class));

        withHealth.run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("redisHealthContributor"));
        });
        // Control: the same setup with the indicator switched back on does register it.
        withHealth.withPropertyValues("management.health.redis.enabled=true")
                .run(context -> assertTrue(context.containsBean("redisHealthContributor")));
    }

    @Test
    void redisHoldsOnlyTheCacheNoSpringDataRepositories() {
        runner.withConfiguration(AutoConfigurations.of(RedisRepositoriesAutoConfiguration.class)).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("false", context.getEnvironment().getProperty("spring.data.redis.repositories.enabled"));
            assertTrue(context.getBeansOfType(RedisKeyValueTemplate.class).isEmpty());
        });
    }
}
