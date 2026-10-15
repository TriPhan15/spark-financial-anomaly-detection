package vn.edu.huit.finance.core.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.huit.finance.core.Entity.Budget;
import vn.edu.huit.finance.core.Entity.BudgetPeriodType;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

    // LEFT JOIN FETCH: the category is optional, and fetching it here keeps the whole list at one query, so reading
    // category names afterwards cannot trigger a query per budget. b.user.id compares the user_id column, no join.
    @Query("select b from Budget b left join fetch b.category "
            + "where b.user.id = :userId order by b.startDate desc, b.id desc")
    List<Budget> findAllWithCategoryByUserId(@Param("userId") Long userId);

    // Ownership is part of the query: another user's budget is simply "not found".
    @Query("select b from Budget b left join fetch b.category where b.id = :id and b.user.id = :userId")
    Optional<Budget> findWithCategoryByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    // The budgets in force on a day for the given category keys (Budget.categoryKey: 0 is "every category"). The category
    // is fetched with them because the budget alert names it, so reading it afterwards cannot cost a query per budget.
    @Query("select b from Budget b left join fetch b.category where b.user.id = :userId "
            + "and b.startDate <= :coveredOn and b.endDate >= :coveredOn and b.categoryKey in (:categoryKeys)")
    List<Budget> findCoveringDate(@Param("userId") Long userId, @Param("coveredOn") LocalDate coveredOn,
                                  @Param("categoryKeys") Collection<Long> categoryKeys);

    @Query("select count(b) > 0 from Budget b where b.user.id = :userId and b.categoryKey = :categoryKey "
            + "and b.periodType = :periodType and b.startDate = :startDate")
    boolean existsDuplicate(@Param("userId") Long userId, @Param("categoryKey") long categoryKey,
                            @Param("periodType") BudgetPeriodType periodType, @Param("startDate") LocalDate startDate);

    /** Same check for an update: the budget being updated must not count as its own duplicate. */
    @Query("select count(b) > 0 from Budget b where b.user.id = :userId and b.categoryKey = :categoryKey "
            + "and b.periodType = :periodType and b.startDate = :startDate and b.id <> :excludedId")
    boolean existsDuplicateExcluding(@Param("userId") Long userId, @Param("categoryKey") long categoryKey,
                                     @Param("periodType") BudgetPeriodType periodType,
                                     @Param("startDate") LocalDate startDate, @Param("excludedId") Long excludedId);
}
