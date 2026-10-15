package vn.edu.huit.finance.core.Config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.edu.huit.finance.core.Dto.AppDashboardResponseDto;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Redis is only a cache: whatever happens to it, a statistics request still answers, from PostgreSQL, within a bounded
 * time. Covers a Redis that was never reachable, one that stops answering (paused: the connection stays open, so every
 * command waits for the command timeout), and one that goes down while connected (Lettuce rejects commands at once while
 * it reconnects in the background).
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisOutageTest {

    private static final Long ALICE_ID = 1L;
    /** Far above the worst case (a read and a write each waiting for the 500 ms command timeout), far below a hang. */
    private static final Duration HANG = Duration.ofSeconds(5);
    private static final Duration BOTH_COMMAND_TIMEOUTS = Duration.ofSeconds(1);
    /** Well below one command timeout: only a command that was rejected at once can be this quick. */
    private static final Duration REJECTED_AT_ONCE = Duration.ofMillis(450);

    private static ApplicationContextRunner instanceOn(String... connection) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withUserConfiguration(CacheConfig.class, UserStatisticsService.class, AdminStatisticsService.class,
                        StatisticsCacheEvictor.class)
                .withBean(TransactionalEventListenerFactory.class)
                .withBean(TransactionRepository.class, () -> mock(TransactionRepository.class))
                .withBean(UserRepository.class, () -> mock(UserRepository.class))
                .withBean(ReceiptRepository.class, () -> mock(ReceiptRepository.class))
                .withPropertyValues(connection)
                .withPropertyValues("app.cache.key-prefix=ledgermind:outage-test");
    }

    private static Transaction transactionOf(String note) {
        User owner = new User();
        owner.setId(ALICE_ID);
        Transaction transaction = new Transaction();
        transaction.setId(100L);
        transaction.setUser(owner);
        transaction.setNote(note);
        transaction.setCategoryName("Ăn uống");
        transaction.setAmount(new BigDecimal("150000.00"));
        transaction.setExpense(true);
        transaction.setTransactionDate(LocalDateTime.of(2026, 9, 1, 8, 30));
        return transaction;
    }

    /** The database answers with these notes, one per read, in order (the last one from then on). */
    private static TransactionRepository databaseAnswers(AssertableApplicationContext context, String first, String... next) {
        TransactionRepository repository = context.getBean(TransactionRepository.class);
        List<Transaction> firstPage = List.of(transactionOf(first));
        @SuppressWarnings("unchecked")
        List<Transaction>[] nextPages = java.util.Arrays.stream(next).map(note -> List.of(transactionOf(note))).toArray(List[]::new);
        when(repository.findByUserId(eq(ALICE_ID), any(Pageable.class))).thenReturn(firstPage, nextPages);
        when(repository.findByUserIdAndVoucherCodeNotNull(ALICE_ID)).thenReturn(List.of());
        return repository;
    }

    private static <T> T timed(Duration limit, Supplier<T> call) {
        long started = System.nanoTime();
        T result = assertTimeoutPreemptively(HANG, call::get, "a statistics request hung on Redis");
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertTrue(took.compareTo(limit) < 0, "took " + took.toMillis() + " ms, expected under " + limit.toMillis());
        return result;
    }

    private static String firstTitle(AppDashboardResponseDto dashboard) {
        return dashboard.getRecentTransactions().get(0).getTitle();
    }

    @Test
    void aRedisThatCannotBeReachedLeavesEveryReadToTheDatabaseAndEveryEvictionHarmless() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        instanceOn("spring.data.redis.host=localhost", "spring.data.redis.port=" + closedPort).run(context -> {
            TransactionRepository repository = databaseAnswers(context, "Bún bò", "Bún chả");
            UserStatisticsService statistics = context.getBean(UserStatisticsService.class);

            assertEquals("Bún bò", firstTitle(timed(HANG, () -> statistics.dashboardOf(ALICE_ID))));
            assertEquals("Bún chả", firstTitle(timed(HANG, () -> statistics.dashboardOf(ALICE_ID))));
            assertNotNull(context.getBean(AdminStatisticsService.class).getDashboardSummary(YearMonth.of(2026, 9), BigDecimal.TEN));

            verify(repository, times(2)).findByUserId(eq(ALICE_ID), any(Pageable.class));
            assertDoesNotThrow(() -> context.publishEvent(
                    StatisticsChangedEvent.of(StatisticsChange.TRANSACTION_TOTALS, ALICE_ID)));
        });
    }

    @Test
    void aRedisThatStopsAnsweringCostsARequestNoMoreThanTheCommandTimeouts() {
        GenericContainer<?> redis = RedisTestContainers.newRedis();
        redis.start();
        try {
            instanceOn(RedisTestContainers.connectionProperties(redis)).run(context -> {
                databaseAnswers(context, "Bún bò", "Bún chả");
                UserStatisticsService statistics = context.getBean(UserStatisticsService.class);
                statistics.dashboardOf(ALICE_ID);
                assertEquals("Bún bò", firstTitle(statistics.dashboardOf(ALICE_ID)), "cached before the outage");

                DockerClientFactory.instance().client().pauseContainerCmd(redis.getContainerId()).exec();
                try {
                    AppDashboardResponseDto duringOutage = timed(
                            BOTH_COMMAND_TIMEOUTS.plusSeconds(2), () -> statistics.dashboardOf(ALICE_ID));

                    assertEquals("Bún chả", firstTitle(duringOutage), "read from the database, not a stale cache");
                    assertDoesNotThrow(() -> context.publishEvent(
                            StatisticsChangedEvent.of(StatisticsChange.TRANSACTION_TOTALS, ALICE_ID)));
                } finally {
                    DockerClientFactory.instance().client().unpauseContainerCmd(redis.getContainerId()).exec();
                }
            });
        } finally {
            redis.stop();
        }
    }

    @Test
    void aRedisThatWentDownIsSkippedAtOnceInsteadOfWaitingForTheTimeout() {
        GenericContainer<?> redis = RedisTestContainers.newRedis();
        redis.start();
        try {
            instanceOn(RedisTestContainers.connectionProperties(redis)).run(context -> {
                TransactionRepository repository = databaseAnswers(context, "Bún bò", "Bún chả");
                UserStatisticsService statistics = context.getBean(UserStatisticsService.class);
                statistics.dashboardOf(ALICE_ID);

                redis.stop();
                // The first request after the crash may still find the connection open; from then on Lettuce knows.
                timed(HANG, () -> statistics.dashboardOf(ALICE_ID));
                AppDashboardResponseDto whileDown = timed(REJECTED_AT_ONCE, () -> statistics.dashboardOf(ALICE_ID));

                assertEquals("Bún chả", firstTitle(whileDown));
                verify(repository, times(3)).findByUserId(eq(ALICE_ID), any(Pageable.class));
            });
        } finally {
            redis.stop();
        }
    }
}
