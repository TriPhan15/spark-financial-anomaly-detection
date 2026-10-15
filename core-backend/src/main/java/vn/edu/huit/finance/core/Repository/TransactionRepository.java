package vn.edu.huit.finance.core.Repository;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import vn.edu.huit.finance.core.Entity.Transaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /** One transaction with its owner, category and receipt in a single query, so mapping it to a DTO adds no queries. */
    @Query("SELECT t FROM Transaction t " +
           "JOIN FETCH t.user " +
           "JOIN FETCH t.category " +
           "LEFT JOIN FETCH t.receipt " +
           "WHERE t.id = :id")
    Optional<Transaction> findDetailedById(@Param("id") Long id);

    /**
     * The transaction this user already created with this client id (the idempotency key of a checkout), if any. Only what
     * the transaction row itself holds is needed to answer a repeated checkout, so nothing is fetched along with it.
     */
    @Query("SELECT t FROM Transaction t WHERE t.user.id = :userId AND t.clientId = :clientId")
    Optional<Transaction> findByUserIdAndClientId(@Param("userId") Long userId, @Param("clientId") String clientId);

    @Query("SELECT t FROM Transaction t " +
           "JOIN FETCH t.user " +
           "JOIN FETCH t.category " +
           "LEFT JOIN FETCH t.receipt r " +
           "WHERE t.user.id = :userId " +
           "AND (:categoryId IS NULL OR t.category.id = :categoryId) " +
           "AND (:minPrice IS NULL OR t.amount >= :minPrice) " +
           "AND (:maxPrice IS NULL OR t.amount <= :maxPrice) " +
           "AND (:vendorName IS NULL OR LOWER(r.vendorName) LIKE LOWER(CONCAT('%', :vendorName, '%')))")
    List<Transaction> filterTransactions(
            @Param("userId") Long userId,
            @Param("categoryId") Long categoryId,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice,
            @Param("vendorName") String vendorName,
            Sort sort
    );

    @Query("SELECT SUM(t.amount) FROM Transaction t")
    BigDecimal sumTotalVolume();

    @Query("SELECT t.receipt.vendorName, COUNT(t.id) as freq " +
            "FROM Transaction t " +
            "WHERE t.user.id = :userId AND t.receipt.vendorName IS NOT NULL " +
            "GROUP BY t.receipt.vendorName " +
            "ORDER BY freq DESC")
    List<Object[]> findPopularVendors(@org.springframework.data.repository.query.Param("userId") Long userId);


    @Query("SELECT t FROM Transaction t " +
           "JOIN FETCH t.user " +
           "JOIN FETCH t.category " +
           "LEFT JOIN FETCH t.receipt r " +
           "WHERE t.user.id = :userId " +
           "AND (:categoryId IS NULL OR t.category.id = :categoryId) " +
           "AND (:minPrice IS NULL OR t.amount >= :minPrice) " +
           "AND (:maxPrice IS NULL OR t.amount <= :maxPrice) " +
           "AND (:vendorName IS NULL OR LOWER(r.vendorName) LIKE LOWER(CONCAT('%', :vendorName, '%'))) " +
           "AND (CAST(:startDate AS date) IS NULL OR t.transactionDate >= :startDate) " +
           "AND (CAST(:endDate AS date) IS NULL OR t.transactionDate <= :endDate)")
    List<Transaction> filterTransactions(
            @Param("userId") Long userId,
            @Param("categoryId") Long categoryId,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice,
            @Param("vendorName") String vendorName,
            @Param("startDate") java.time.LocalDate startDate,
            @Param("endDate") java.time.LocalDate endDate,
            org.springframework.data.domain.Sort sort
    );

    Transaction findTopByUserIdOrderByAmountDesc(Long userId);


    @Query("SELECT t FROM Transaction t JOIN FETCH t.category LEFT JOIN FETCH t.receipt WHERE t.user.id = :userId AND t.voucherCode IS NOT NULL")
    List<Transaction> findByUserIdAndVoucherCodeNotNull(@Param("userId") Long userId);

    long countByTransactionDateBetween(java.time.LocalDateTime startDate, java.time.LocalDateTime endDate);


    @Query(value = "SELECT t FROM Transaction t JOIN FETCH t.category LEFT JOIN FETCH t.receipt WHERE t.user.id = :userId",
           countQuery = "SELECT count(t) FROM Transaction t WHERE t.user.id = :userId")
    List<Transaction> findByUserId(@Param("userId") Long userId, org.springframework.data.domain.Pageable pageable);


    @Query("SELECT t FROM Transaction t JOIN FETCH t.category LEFT JOIN FETCH t.receipt WHERE t.user.id = :userId ORDER BY t.transactionDate DESC")
    List<Transaction> findByUserIdOrderByTransactionDateDesc(@Param("userId") Long userId);

    @Query("SELECT t.user.username, SUM(t.amount) as totalSpent FROM Transaction t WHERE t.isExpense = true GROUP BY t.user.id, t.user.username ORDER BY totalSpent DESC")
    List<Object[]> findTopSpenders(org.springframework.data.domain.Pageable pageable);

    /** What a user spent (expenses only) from rangeStart, inclusive, to rangeEnd, exclusive; null when there is nothing. */
    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.user.id = :userId AND t.isExpense = true " +
           "AND t.transactionDate >= :rangeStart AND t.transactionDate < :rangeEnd")
    BigDecimal sumExpensesBetween(@Param("userId") Long userId,
                                  @Param("rangeStart") java.time.LocalDateTime rangeStart,
                                  @Param("rangeEnd") java.time.LocalDateTime rangeEnd);

    /** Same as {@link #sumExpensesBetween}, restricted to one category. */
    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.user.id = :userId AND t.isExpense = true " +
           "AND t.category.id = :categoryId AND t.transactionDate >= :rangeStart AND t.transactionDate < :rangeEnd")
    BigDecimal sumExpensesInCategoryBetween(@Param("userId") Long userId,
                                            @Param("categoryId") Long categoryId,
                                            @Param("rangeStart") java.time.LocalDateTime rangeStart,
                                            @Param("rangeEnd") java.time.LocalDateTime rangeEnd);
}