package vn.edu.huit.finance.core.Config;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.ClientOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.lang.Nullable;
import vn.edu.huit.finance.core.Dto.AdminDashboardSummaryDto;
import vn.edu.huit.finance.core.Dto.AppDashboardResponseDto;
import vn.edu.huit.finance.core.Dto.MonthlyTransactionCountDto;
import vn.edu.huit.finance.core.Dto.PopularVendorResponseDto;
import vn.edu.huit.finance.core.Dto.TopSpenderDto;
import vn.edu.huit.finance.core.Service.Cache.CacheNames;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Spring Cache on Redis for the statistics (home page dashboards, popular vendors, admin aggregates), shared by every
 * instance of the API. Each cache holds one DTO type as plain JSON and reads it back with a serializer typed to that DTO:
 * no Java serialization and no type information in the payload, so an entry can only ever become the class its cache
 * holds, and no entity is ever cached. Keys are {@code <application>:<environment>:<schema version>:<cache>:<key>}.
 * Entries always expire and null is never stored. Clearing a cache walks its keys with SCAN, never KEYS. A Redis that is
 * down, slow or holding an unreadable entry never fails a request: see {@link FallbackCacheErrorHandler}.
 */
@Slf4j
@Configuration
@EnableCaching(order = CacheConfig.CACHING_ORDER)
@EnableConfigurationProperties(StatisticsCacheProperties.class)
@PropertySource("classpath:redis-cache.properties")
public class CacheConfig implements CachingConfigurer {

    /**
     * The cache advice wraps the @Transactional one (whose order is the lowest precedence), so a hit is answered before
     * any database transaction starts and never borrows a connection, and a miss is cached only once its read-only
     * transaction has completed. Method security (@PreAuthorize) still runs before both.
     */
    static final int CACHING_ORDER = Ordered.LOWEST_PRECEDENCE - 1;
    /** How many keys one SCAN round trip visits while a cache is being cleared. */
    static final int CLEAR_SCAN_BATCH_SIZE = 500;
    private static final String APPLICATION_NAME = "ledgermind";
    private static final String DEFAULT_ENVIRONMENT = "default";
    private static final String KEY_SEPARATOR = ":";
    private static final String PROFILE_SEPARATOR = "-";
    /** No blanks and no glob characters: the prefix is part of the SCAN MATCH pattern that clears a cache. */
    private static final Pattern SAFE_KEY_PREFIX = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final long PERCENT = 100;

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, StatisticsCacheProperties properties,
                                          Environment environment) {
        String keyPrefix = versionedKeyPrefix(properties.keyPrefix(), environment.getActiveProfiles());
        ObjectMapper mapper = cacheObjectMapper();
        TypeFactory types = mapper.getTypeFactory();
        StatisticsCacheProperties.Ttl ttl = properties.ttl();
        int jitter = properties.ttlJitterPercent();

        Map<String, RedisCacheConfiguration> caches = Map.of(
                CacheNames.USER_DASHBOARD, cacheOf(keyPrefix, new JitteredTtl(ttl.userDashboard(), jitter), mapper,
                        types.constructType(AppDashboardResponseDto.class)),
                CacheNames.USER_POPULAR_VENDORS, cacheOf(keyPrefix, new JitteredTtl(ttl.userPopularVendors(), jitter), mapper,
                        types.constructCollectionType(List.class, PopularVendorResponseDto.class)),
                CacheNames.ADMIN_DASHBOARD_SUMMARY, cacheOf(keyPrefix, new JitteredTtl(ttl.adminDashboardSummary(), jitter), mapper,
                        types.constructType(AdminDashboardSummaryDto.class)),
                CacheNames.ADMIN_TOP_SPENDERS, cacheOf(keyPrefix, new JitteredTtl(ttl.adminTopSpenders(), jitter), mapper,
                        types.constructCollectionType(List.class, TopSpenderDto.class)),
                CacheNames.ADMIN_STATISTICS, cacheOf(keyPrefix, new JitteredTtl(ttl.adminStatistics(), jitter), mapper,
                        types.constructCollectionType(List.class, MonthlyTransactionCountDto.class)));

        log.info("Redis statistics caches configured, keyPrefix={}, {}, ttlJitterPercent={}", keyPrefix, ttl, jitter);
        return RedisCacheManager.builder(RedisCacheWriter.nonLockingRedisCacheWriter(
                        connectionFactory, BatchStrategies.scan(CLEAR_SCAN_BATCH_SIZE)))
                .withInitialCacheConfigurations(caches)
                .disableCreateOnMissingCache()
                .enableStatistics()
                .build();
    }

    @Bean
    public CacheErrorHandler cacheErrorHandler() {
        return new FallbackCacheErrorHandler();
    }

    /** Used by every @Cacheable; StatisticsCacheEvictor uses the same bean for its own evictions. */
    @Override
    public CacheErrorHandler errorHandler() {
        return cacheErrorHandler();
    }

    /**
     * While the connection to Redis is down, a command fails at once instead of waiting in Lettuce's queue for the whole
     * command timeout, so during an outage every request goes straight to PostgreSQL; Lettuce keeps reconnecting in the
     * background. Everything else Spring Boot configured (connect timeout, command timeout) is kept as it is.
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer failFastWhileRedisIsDisconnected() {
        return builder -> builder.build().getClientOptions().ifPresent(options -> builder.clientOptions(options.mutate()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build()));
    }

    /**
     * {@code <application>:<environment>:<schema version>}: the configured prefix, or ledgermind:&lt;active profiles&gt;
     * when none is set, so that test, staging and production never read each other's entries, even in one Redis.
     */
    static String versionedKeyPrefix(@Nullable String configured, String[] activeProfiles) {
        String prefix = (configured != null && !configured.isBlank())
                ? configured.trim()
                : APPLICATION_NAME + KEY_SEPARATOR
                        + (activeProfiles.length == 0 ? DEFAULT_ENVIRONMENT : String.join(PROFILE_SEPARATOR, activeProfiles));
        if (!SAFE_KEY_PREFIX.matcher(prefix).matches()) {
            throw new IllegalStateException("app.cache.key-prefix may only hold letters, digits and . _ : - "
                    + "(it is part of the SCAN pattern that clears a cache), was '" + prefix + "'");
        }
        return prefix + KEY_SEPARATOR + CacheNames.SCHEMA_VERSION;
    }

    /** Plain JSON without type information; dates as ISO-8601 text, the way the API writes them. */
    static ObjectMapper cacheObjectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    private static RedisCacheConfiguration cacheOf(String keyPrefix, RedisCacheWriter.TtlFunction ttl,
                                                   ObjectMapper mapper, JavaType valueType) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .computePrefixWith(cacheName -> keyPrefix + KEY_SEPARATOR + cacheName + KEY_SEPARATOR)
                .serializeKeysWith(SerializationPair.fromSerializer(RedisSerializer.string()))
                .serializeValuesWith(SerializationPair.fromSerializer(new Jackson2JsonRedisSerializer<>(mapper, valueType)));
    }

    /**
     * The TTL of a cache shortened by a random share of at most {@code jitterPercent}, so that entries written at the
     * same moment (after a whole cache was cleared, say) do not all expire at the same moment either.
     */
    record JitteredTtl(Duration ttl, int jitterPercent) implements RedisCacheWriter.TtlFunction {

        @Override
        public Duration getTimeToLive(Object key, @Nullable Object value) {
            long maxJitterMillis = ttl.toMillis() * jitterPercent / PERCENT;
            return ttl.minusMillis(ThreadLocalRandom.current().nextLong(maxJitterMillis + 1));
        }
    }
}
