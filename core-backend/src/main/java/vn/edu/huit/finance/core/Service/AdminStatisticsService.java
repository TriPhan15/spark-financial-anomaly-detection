package vn.edu.huit.finance.core.Service;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.AdminDashboardSummaryDto;
import vn.edu.huit.finance.core.Dto.LowBalanceUserDto;
import vn.edu.huit.finance.core.Dto.MonthlyTransactionCountDto;
import vn.edu.huit.finance.core.Dto.ReceiptStatusCountDto;
import vn.edu.huit.finance.core.Dto.TopSpenderDto;
import vn.edu.huit.finance.core.Entity.ReceiptStatus;
import vn.edu.huit.finance.core.Repository.ReceiptRepository;
import vn.edu.huit.finance.core.Repository.TransactionRepository;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Service.Cache.CacheNames;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The aggregates of the admin dashboard, computed across every user and cached in Redis (see Config/CacheConfig). Each
 * method takes everything its result depends on, and its cache key holds all of it, so two different questions never
 * share an entry. For administrators only: the admin pages are closed to everybody without ROLE_ADMIN by SecurityConfig.
 */
@Service
@RequiredArgsConstructor
public class AdminStatisticsService {

    /** A month ends at 23:59:59 of its last day, as the admin dashboard has always counted it. */
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    private final TransactionRepository transactionRepo;
    private final UserRepository userRepo;
    private final ReceiptRepository receiptRepo;

    /** Key {@code <period>:<threshold>}, e.g. {@code 2026-09:50000}. */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.ADMIN_DASHBOARD_SUMMARY, unless = "#result == null",
            key = "#period.toString() + ':' + #lowBalanceThreshold.stripTrailingZeros().toPlainString()")
    public AdminDashboardSummaryDto getDashboardSummary(YearMonth period, BigDecimal lowBalanceThreshold) {
        BigDecimal totalVolume = transactionRepo.sumTotalVolume();
        return AdminDashboardSummaryDto.builder()
                .totalVolume(totalVolume != null ? totalVolume : BigDecimal.ZERO)
                .totalUsers(userRepo.count())
                .transactionsInPeriod(countTransactionsIn(period))
                .lowBalanceUsers(userRepo.findByWalletBalanceLessThan(lowBalanceThreshold).stream()
                        .map(LowBalanceUserDto::from)
                        .toList())
                .receiptStatuses(countReceiptsByStatus())
                .build();
    }

    /**
     * Receipts counted by status, under the names the dashboard has always shown (ReceiptStatus.legacyName): every status a
     * receipt that was read can be in (COMPLETED_RAW, EDITED, APPROVED, and SUCCESS in rows that predate the lifecycle) is
     * one figure, SUCCESS. In the order the statuses first appear.
     */
    private List<ReceiptStatusCountDto> countReceiptsByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : receiptRepo.getReceiptStatusStatistics()) {
            counts.merge(ReceiptStatus.legacyNameOf(String.valueOf(row[0])), ((Number) row[1]).longValue(), Long::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> ReceiptStatusCountDto.builder()
                        .status(entry.getKey())
                        .count(entry.getValue())
                        .build())
                .toList();
    }

    /** The users who spent the most (expenses only), highest first. Key {@code <page>:<limit>}, e.g. {@code 0:5}. */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.ADMIN_TOP_SPENDERS, key = "#page + ':' + #limit", unless = "#result == null")
    public List<TopSpenderDto> getTopSpenders(int page, int limit) {
        return transactionRepo.findTopSpenders(PageRequest.of(page, limit)).stream()
                .map(row -> TopSpenderDto.builder()
                        .username((String) row[0])
                        .totalSpent((BigDecimal) row[1])
                        .build())
                .toList();
    }

    /**
     * The number of transactions of each of the {@code months} months that end with {@code lastMonth}, oldest first.
     * Key {@code monthly-transactions:<last month>:<months>}, e.g. {@code monthly-transactions:2026-09:6}.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.ADMIN_STATISTICS, unless = "#result == null",
            key = "'monthly-transactions:' + #lastMonth.toString() + ':' + #months")
    public List<MonthlyTransactionCountDto> getMonthlyTransactionCounts(YearMonth lastMonth, int months) {
        List<MonthlyTransactionCountDto> counts = new ArrayList<>();
        for (int back = months - 1; back >= 0; back--) {
            YearMonth month = lastMonth.minusMonths(back);
            counts.add(MonthlyTransactionCountDto.builder()
                    .month(month)
                    .transactionCount(countTransactionsIn(month))
                    .build());
        }
        return counts;
    }

    private long countTransactionsIn(YearMonth month) {
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.atEndOfMonth().atTime(END_OF_DAY);
        return transactionRepo.countByTransactionDateBetween(start, end);
    }
}
