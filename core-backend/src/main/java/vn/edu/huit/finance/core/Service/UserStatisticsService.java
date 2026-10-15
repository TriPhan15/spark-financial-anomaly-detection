package vn.edu.huit.finance.core.Service;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.AppDashboardResponseDto;
import vn.edu.huit.finance.core.Dto.PopularVendorResponseDto;
import vn.edu.huit.finance.core.Dto.TransactionResponseDto;
import vn.edu.huit.finance.core.Entity.Transaction;
import vn.edu.huit.finance.core.Entity.TransactionSortField;
import vn.edu.huit.finance.core.Repository.TransactionRepository;
import vn.edu.huit.finance.core.Service.Cache.CacheNames;

import java.util.List;

/**
 * The statistics of one user's home page, cached in Redis under that user's id (see Config/CacheConfig) and evicted
 * after every committed change of that user's data (see StatisticsChange). For TransactionService only, which takes the
 * id from the signed-in caller, or checks an administrator's request, before it asks: nothing here checks who asks, so
 * no controller may use this class. The methods are public and live apart from their callers on purpose: @Cacheable
 * only works on a call that goes through the Spring proxy, never on a call from inside the same class.
 */
@Service
@RequiredArgsConstructor
public class UserStatisticsService {

    private static final int DASHBOARD_RECENT_COUNT = 5;

    private final TransactionRepository transactionRepo;

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.USER_POPULAR_VENDORS, key = "#userId", unless = "#result == null")
    public List<PopularVendorResponseDto> popularVendorsOf(Long userId) {
        return transactionRepo.findPopularVendors(userId).stream()
                .map(row -> PopularVendorResponseDto.builder()
                        .vendorName((String) row[0])
                        .transactionCount(((Number) row[1]).longValue())
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.USER_DASHBOARD, key = "#userId", unless = "#result == null")
    public AppDashboardResponseDto dashboardOf(Long userId) {
        Pageable latest = PageRequest.of(0, DASHBOARD_RECENT_COUNT,
                Sort.by(TransactionSortField.TRANSACTION_DATE.getProperty()).descending());
        Transaction biggest = transactionRepo.findTopByUserIdOrderByAmountDesc(userId);

        return AppDashboardResponseDto.builder()
                .recentTransactions(transactionRepo.findByUserId(userId, latest).stream()
                        .map(TransactionResponseDto::from).toList())
                .biggestTransaction(biggest != null ? TransactionResponseDto.from(biggest) : null)
                .saleTransactions(transactionRepo.findByUserIdAndVoucherCodeNotNull(userId).stream()
                        .map(TransactionResponseDto::from).toList())
                .build();
    }
}
