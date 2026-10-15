package vn.edu.huit.finance.core.Service;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.edu.huit.finance.core.Config.CacheConfig;
import vn.edu.huit.finance.core.Config.RedisTestContainers;
import vn.edu.huit.finance.core.Dto.AdminDashboardSummaryDto;
import vn.edu.huit.finance.core.Dto.AppDashboardResponseDto;
import vn.edu.huit.finance.core.Dto.TransactionRequestDto;
import vn.edu.huit.finance.core.Dto.TransactionUpdateRequestDto;
import vn.edu.huit.finance.core.Entity.Category;
import vn.edu.huit.finance.core.Entity.Transaction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.UserNotFoundException;
import vn.edu.huit.finance.core.Repository.CategoryRepository;
import vn.edu.huit.finance.core.Repository.ReceiptRepository;
import vn.edu.huit.finance.core.Repository.TransactionRepository;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;
import vn.edu.huit.finance.core.Service.Cache.StatisticsCacheEvictor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The statistics cache around real database transactions: TransactionService on H2, through its real @Transactional
 * proxy, with the real cache configuration on a real Redis (Testcontainers). Nothing here runs in a test transaction,
 * because the commits and rollbacks are what is under test.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "app.cache.key-prefix=" + StatisticsCacheTransactionTest.PREFIX})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
@ImportAutoConfiguration(RedisAutoConfiguration.class)
@Import({CacheConfig.class, StatisticsCacheEvictor.class, UserStatisticsService.class, AdminStatisticsService.class,
        TransactionService.class, ReceiptProcessingService.class, OcrService.class})
class StatisticsCacheTransactionTest {

    static final String PREFIX = "ledgermind:transaction-test";
    private static final BigDecimal WALLET = new BigDecimal("1000000.00");
    private static final BigDecimal THRESHOLD = new BigDecimal("50000");
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    /** Five evictions, each waiting at most the 500 ms command timeout, and room to spare. */
    private static final Duration WRITE_DURING_A_REDIS_HANG = Duration.ofSeconds(10);

    @Container
    private static final GenericContainer<?> REDIS = RedisTestContainers.newRedis();

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(RedisTestContainers.PORT));
        registry.add("spring.data.redis.password", () -> RedisTestContainers.PASSWORD);
    }

    @Autowired private TransactionService service;
    @Autowired private UserStatisticsService userStatisticsService;
    @Autowired private AdminStatisticsService adminStatisticsService;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private StringRedisTemplate redis;
    @SpyBean private TransactionRepository transactionRepository;
    @MockBean private CurrentUserProvider currentUserProvider;
    @MockBean private AuditLogService auditLogService;
    @MockBean private BudgetAlertService budgetAlertService;

    private Long aliceId;
    private Long bobId;
    private Long aliceExpenseId;
    private Category food;
    private Long signedInId;

    @BeforeEach
    void setUp() {
        deleteEveryKeyOfThisTest();
        aliceId = persistUser("alice", "0900000001");
        bobId = persistUser("bob", "0900000002");
        food = new Category();
        food.setName("Ăn uống");
        food.setType("EXPENSE");
        food = categoryRepository.saveAndFlush(food);
        aliceExpenseId = persistExpense(aliceId, "Của Alice");
        persistExpense(bobId, "Của Bob");
        signedIn(aliceId);
        when(currentUserProvider.getCurrentUser()).thenAnswer(invocation -> userRepository.findById(signedInId).orElseThrow());
    }

    @AfterEach
    void cleanUp() {
        transactionRepository.deleteAll();
        receiptRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
        deleteEveryKeyOfThisTest();
    }

    private Long persistUser(String name, String phone) {
        User user = new User();
        user.setUsername(name);
        user.setPassword("hash");
        user.setEmail(name + "@example.com");
        user.setPhone(phone);
        user.setWalletBalance(WALLET);
        return userRepository.saveAndFlush(user).getId();
    }

    private Long persistExpense(Long ownerId, String note) {
        Transaction transaction = new Transaction();
        transaction.setUser(userRepository.findById(ownerId).orElseThrow());
        transaction.setCategory(food);
        transaction.setCategoryName(food.getName());
        transaction.setAmount(new BigDecimal("100000.00"));
        transaction.setExpense(true);
        transaction.setNote(note);
        transaction.setTransactionDate(LocalDateTime.of(2026, 9, 1, 12, 0));
        return transactionRepository.saveAndFlush(transaction).getId();
    }

    private void signedIn(Long userId) {
        signedInId = userId;
    }

    private TransactionRequestDto checkout(String amount, String note) {
        TransactionRequestDto request = new TransactionRequestDto();
        request.setAmount(new BigDecimal(amount));
        request.setExpense(true);
        request.setCategoryId(food.getId());
        request.setNote(note);
        request.setTransactionDate(LocalDateTime.of(2026, 9, 2, 9, 0));
        return request;
    }

    private static String key(String cache, Object key) {
        return PREFIX + ":v1:" + cache + ":" + key;
    }

    private boolean cached(String cache, Object key) {
        return Boolean.TRUE.equals(redis.hasKey(key(cache, key)));
    }

    private Set<String> keysOfThisTest() {
        Set<String> keys = new HashSet<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(PREFIX + ":*").count(100).build())) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }

    private void deleteEveryKeyOfThisTest() {
        Set<String> keys = keysOfThisTest();
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private BigDecimal walletInDatabase(Long userId) {
        return userRepository.findById(userId).orElseThrow().getWalletBalance();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static Set<String> titles(AppDashboardResponseDto dashboard) {
        Set<String> titles = new HashSet<>();
        dashboard.getRecentTransactions().forEach(transaction -> titles.add(transaction.getTitle()));
        return titles;
    }

    // ---------------------------------------------------------------------------------------------------- read path

    @Test
    void theHomePageIsCachedThroughTheProxyOfAnotherBeanSoASecondReadRunsNoQuery() {
        assertTrue(AopUtils.isAopProxy(userStatisticsService));

        AppDashboardResponseDto miss = service.getMyDashboard();
        AppDashboardResponseDto hit = service.getMyDashboard();

        assertEquals(miss, hit);
        assertEquals(Set.of("Của Alice"), titles(hit));
        assertTrue(cached("user-dashboard", aliceId));
        verify(transactionRepository, times(1)).findByUserId(eq(aliceId), any(Pageable.class));
    }

    @Test
    void theKeyIsTheSignedInUsersSoNobodyReadsSomebodyElsesEntry() {
        assertEquals(Set.of("Của Alice"), titles(service.getMyDashboard()));
        signedIn(bobId);

        assertEquals(Set.of("Của Bob"), titles(service.getMyDashboard()));
        assertEquals(Set.of(key("user-dashboard", aliceId), key("user-dashboard", bobId)), keysOfThisTest());
    }

    @Test
    void aHitIsAnsweredBeforeAnyDatabaseTransactionStartsWhileAMissReadsInOne() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        adminStatisticsService.getDashboardSummary(SEPTEMBER, THRESHOLD);
        statistics.clear();

        adminStatisticsService.getDashboardSummary(SEPTEMBER, THRESHOLD);

        assertEquals(0, statistics.getConnectCount(), "a cache hit must not even borrow a database connection");
        assertEquals(0, statistics.getTransactionCount());

        adminStatisticsService.getDashboardSummary(SEPTEMBER.minusMonths(1), THRESHOLD);

        assertTrue(statistics.getConnectCount() > 0, "control: a miss does read the database");
    }

    @Test
    void anAdministratorsLookupSharesTheUsersEntryButAnUnknownUserIsStillNotFound() {
        AppDashboardResponseDto own = service.getMyDashboard();

        assertEquals(own, service.getDashboardOf(aliceId));
        assertThrows(UserNotFoundException.class, () -> service.getDashboardOf(aliceId + bobId + 1000));
        verify(transactionRepository, times(1)).findByUserId(eq(aliceId), any(Pageable.class));
    }

    // --------------------------------------------------------------------------------------- write path: after commit

    @Test
    void aCheckoutEvictsItsOwnersEntriesOnlyOnceItHasCommittedAndTheNextReadSeesIt() {
        service.getMyDashboard();
        signedIn(bobId);
        service.getMyDashboard();
        signedIn(aliceId);
        AtomicBoolean stillCachedInsideTheTransaction = new AtomicBoolean();
        // The audit entry is written inside the checkout's transaction, after the change was announced.
        doAnswer(invocation -> {
            stillCachedInsideTheTransaction.set(cached("user-dashboard", aliceId));
            return null;
        }).when(auditLogService).record(any(AuditEntry.class));

        service.createMyTransaction(checkout("25000", "Cà phê"));

        assertTrue(stillCachedInsideTheTransaction.get(), "evicted before the commit: another request could cache the old state");
        assertFalse(cached("user-dashboard", aliceId));
        assertTrue(cached("user-dashboard", bobId), "Bob's entry is not Alice's business");
        assertEquals(Set.of("Của Alice", "Cà phê"), titles(service.getMyDashboard()));
    }

    @Test
    void aWriteWhoseTransactionRollsBackEvictsNothingAndTheEntryStillMatchesTheDatabase() {
        AppDashboardResponseDto cachedBefore = service.getMyDashboard();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.createMyTransaction(checkout("25000", "Cà phê"));
            status.setRollbackOnly();
        });

        assertTrue(cached("user-dashboard", aliceId));
        assertMoney("1000000", walletInDatabase(aliceId));
        assertEquals(cachedBefore, service.getMyDashboard());
        assertEquals(Set.of("Của Alice"), titles(service.getMyDashboard()));
    }

    @Test
    void theAdminFiguresAreEvictedByACheckoutAndShowTheNewTotalAfterIt() {
        AdminDashboardSummaryDto before = adminStatisticsService.getDashboardSummary(SEPTEMBER, THRESHOLD);
        assertMoney("200000", before.getTotalVolume());

        service.createMyTransaction(checkout("25000", "Cà phê"));

        assertFalse(cached("admin-dashboard-summary", "2026-09:50000"));
        AdminDashboardSummaryDto after = adminStatisticsService.getDashboardSummary(SEPTEMBER, THRESHOLD);
        assertMoney("225000", after.getTotalVolume());
        assertEquals(before.getTransactionsInPeriod() + 1, after.getTransactionsInPeriod());
    }

    @Test
    void aNewNoteEvictsOnlyTheOwnersDashboardWhileANewAmountEvictsEveryFigureItMoves() {
        service.getMyDashboard();
        service.getMyPopularVendors();
        adminStatisticsService.getDashboardSummary(SEPTEMBER, THRESHOLD);
        TransactionUpdateRequestDto newNote = new TransactionUpdateRequestDto();
        newNote.setNote("Bún chả");

        service.updateTransaction(aliceExpenseId, newNote);

        assertFalse(cached("user-dashboard", aliceId));
        assertTrue(cached("user-popular-vendors", aliceId));
        assertTrue(cached("admin-dashboard-summary", "2026-09:50000"));
        assertEquals(Set.of("Bún chả"), titles(service.getMyDashboard()));

        TransactionUpdateRequestDto newAmount = new TransactionUpdateRequestDto();
        newAmount.setAmount(new BigDecimal("120000"));
        service.updateTransaction(aliceExpenseId, newAmount);

        assertEquals(Set.of(), keysOfThisTest());
    }

    // ------------------------------------------------------------------------------------------------ Redis failures

    @Test
    void aRedisThatHangsDuringTheEvictionNeitherFailsNorRollsBackTheWrite() {
        service.getMyDashboard();
        DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertTimeoutPreemptively(WRITE_DURING_A_REDIS_HANG,
                    () -> service.createMyTransaction(checkout("25000", "Cà phê")));
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        assertMoney("975000", walletInDatabase(aliceId));
        assertEquals(2, transactionRepository.findByUserIdOrderByTransactionDateDesc(aliceId).size());
    }
}
