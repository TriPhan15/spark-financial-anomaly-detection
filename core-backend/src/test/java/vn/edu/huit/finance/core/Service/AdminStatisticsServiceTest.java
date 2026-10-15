package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.domain.Pageable;
import vn.edu.huit.finance.core.Dto.AdminDashboardSummaryDto;
import vn.edu.huit.finance.core.Dto.LowBalanceUserDto;
import vn.edu.huit.finance.core.Dto.MonthlyTransactionCountDto;
import vn.edu.huit.finance.core.Dto.ReceiptStatusCountDto;
import vn.edu.huit.finance.core.Dto.TopSpenderDto;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Repository.ReceiptRepository;
import vn.edu.huit.finance.core.Repository.TransactionRepository;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Service.Cache.CacheNames;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** What the admin dashboard figures are computed from; how they are cached is tested on a real Redis. */
@ExtendWith(MockitoExtension.class)
class AdminStatisticsServiceTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final BigDecimal THRESHOLD = new BigDecimal("50000");

    @Mock private TransactionRepository transactionRepo;
    @Mock private UserRepository userRepo;
    @Mock private ReceiptRepository receiptRepo;
    @InjectMocks private AdminStatisticsService service;

    private static User user(Long id, String username, String phone, String wallet) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPhone(phone);
        user.setWalletBalance(new BigDecimal(wallet));
        user.setPassword("$2a$10$hash-that-must-never-reach-the-cache");
        user.setEmail(username + "@example.com");
        return user;
    }

    @Test
    void theSummaryHoldsTheTotalsOfEveryUserAndTheCountOfTheMonthItWasAskedFor() {
        when(transactionRepo.sumTotalVolume()).thenReturn(new BigDecimal("1250000.50"));
        when(userRepo.count()).thenReturn(42L);
        when(transactionRepo.countByTransactionDateBetween(any(), any())).thenReturn(17L);
        when(userRepo.findByWalletBalanceLessThan(THRESHOLD)).thenReturn(List.of(user(7L, "an", "0900000007", "12000")));
        when(receiptRepo.getReceiptStatusStatistics()).thenReturn(List.of(
                new Object[]{"SUCCESS", 5L}, new Object[]{"FAILED", 2L}));

        AdminDashboardSummaryDto summary = service.getDashboardSummary(SEPTEMBER, THRESHOLD);

        assertEquals(0, new BigDecimal("1250000.50").compareTo(summary.getTotalVolume()));
        assertEquals(42L, summary.getTotalUsers());
        assertEquals(17L, summary.getTransactionsInPeriod());
        assertEquals(List.of(new LowBalanceUserDto(7L, "an", "0900000007", new BigDecimal("12000"))), summary.getLowBalanceUsers());
        assertEquals(List.of(new ReceiptStatusCountDto("SUCCESS", 5L), new ReceiptStatusCountDto("FAILED", 2L)),
                summary.getReceiptStatuses());
        verify(transactionRepo).countByTransactionDateBetween(
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 30, 23, 59, 59));
    }

    @Test
    void receiptsAreCountedUnderTheNamesTheDashboardShowsSoEveryReadReceiptIsOneFigure() {
        when(receiptRepo.getReceiptStatusStatistics()).thenReturn(List.of(
                new Object[]{"COMPLETED_RAW", 4L}, new Object[]{"FAILED", 2L}, new Object[]{"EDITED", 3L},
                new Object[]{"SUCCESS", 5L}, new Object[]{"APPROVED", 1L}, new Object[]{"WAITING_FOR_REVIEW", 6L},
                new Object[]{"failed", 1L}));

        AdminDashboardSummaryDto summary = service.getDashboardSummary(SEPTEMBER, THRESHOLD);

        // In the order the names first appear; "SUCCESS" is the 5 rows that predate the lifecycle plus the 4 + 3 + 1 since.
        assertEquals(List.of(new ReceiptStatusCountDto("SUCCESS", 13L), new ReceiptStatusCountDto("FAILED", 3L),
                        new ReceiptStatusCountDto("WAITING_FOR_REVIEW", 6L)),
                summary.getReceiptStatuses());
    }

    @Test
    void noTransactionAtAllIsAVolumeOfZeroNotANullThatWouldBeRefusedByTheCache() {
        when(transactionRepo.sumTotalVolume()).thenReturn(null);

        AdminDashboardSummaryDto summary = service.getDashboardSummary(SEPTEMBER, THRESHOLD);

        assertEquals(0, BigDecimal.ZERO.compareTo(summary.getTotalVolume()));
        assertTrue(summary.getLowBalanceUsers().isEmpty());
        assertTrue(summary.getReceiptStatuses().isEmpty());
    }

    @Test
    void theLowBalanceListCarriesOnlyWhatThePageShows() {
        when(userRepo.findByWalletBalanceLessThan(THRESHOLD)).thenReturn(List.of(user(7L, "an", "0900000007", "12000")));

        LowBalanceUserDto listed = service.getDashboardSummary(SEPTEMBER, THRESHOLD).getLowBalanceUsers().get(0);

        assertEquals(new LowBalanceUserDto(7L, "an", "0900000007", new BigDecimal("12000")), listed);
        assertFalse(listed.toString().contains("hash"), "no password hash in a cached DTO");
    }

    @Test
    void theTopSpendersAreAskedForThePageAndLimitGiven() {
        when(transactionRepo.findTopSpenders(any(Pageable.class))).thenReturn(List.<Object[]>of(
                new Object[]{"an", new BigDecimal("900000")}, new Object[]{"binh", new BigDecimal("500000.50")}));

        List<TopSpenderDto> top = service.getTopSpenders(1, 5);

        assertEquals(List.of(new TopSpenderDto("an", new BigDecimal("900000")), new TopSpenderDto("binh", new BigDecimal("500000.50"))), top);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepo).findTopSpenders(page.capture());
        assertEquals(1, page.getValue().getPageNumber());
        assertEquals(5, page.getValue().getPageSize());
    }

    @Test
    void theChartCountsEachMonthFromItsFirstMomentToTheLastSecondOfItsLastDayOldestFirst() {
        when(transactionRepo.countByTransactionDateBetween(any(), any())).thenReturn(3L, 1L, 4L);

        List<MonthlyTransactionCountDto> chart = service.getMonthlyTransactionCounts(YearMonth.of(2026, 2), 3);

        assertEquals(List.of(
                new MonthlyTransactionCountDto(YearMonth.of(2025, 12), 3L),
                new MonthlyTransactionCountDto(YearMonth.of(2026, 1), 1L),
                new MonthlyTransactionCountDto(YearMonth.of(2026, 2), 4L)), chart);
        verify(transactionRepo).countByTransactionDateBetween(
                LocalDateTime.of(2025, 12, 1, 0, 0), LocalDateTime.of(2025, 12, 31, 23, 59, 59));
        verify(transactionRepo).countByTransactionDateBetween(
                LocalDateTime.of(2026, 2, 1, 0, 0), LocalDateTime.of(2026, 2, 28, 23, 59, 59));
    }

    @Test
    void everyFigureIsCachedByAPublicMethodInItsOwnAdminCache() throws NoSuchMethodException {
        List<Method> figures = List.of(
                AdminStatisticsService.class.getMethod("getDashboardSummary", YearMonth.class, BigDecimal.class),
                AdminStatisticsService.class.getMethod("getTopSpenders", int.class, int.class),
                AdminStatisticsService.class.getMethod("getMonthlyTransactionCounts", YearMonth.class, int.class));
        List<String> expectedCaches = List.of(
                CacheNames.ADMIN_DASHBOARD_SUMMARY, CacheNames.ADMIN_TOP_SPENDERS, CacheNames.ADMIN_STATISTICS);

        for (int i = 0; i < figures.size(); i++) {
            Method method = figures.get(i);
            Cacheable cacheable = AnnotatedElementUtils.findMergedAnnotation(method, Cacheable.class);
            assertNotNull(cacheable, method.getName());
            assertTrue(Modifier.isPublic(method.getModifiers()), "@Cacheable only works on a public method called through the proxy");
            assertArrayEquals(new String[]{expectedCaches.get(i)}, cacheable.cacheNames(), method.getName());
            assertEquals("#result == null", cacheable.unless(), method.getName() + " must never cache null");
            assertFalse(cacheable.sync(), "sync would lock the whole cache inside one JVM, not across instances");
        }
    }
}
