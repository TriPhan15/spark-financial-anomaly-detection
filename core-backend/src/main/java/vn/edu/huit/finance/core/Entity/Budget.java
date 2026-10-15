package vn.edu.huit.finance.core.Entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.LocalDate;

@Audited
@Entity
@Table(name = "budgets", uniqueConstraints = @UniqueConstraint(
        name = "uk_budgets_user_category_period",
        columnNames = {"user_id", "category_key", "period_type", "start_date"}))
@Getter
@Setter
@NoArgsConstructor
public class Budget extends BaseEntity {

    public static final int AMOUNT_PRECISION = 19;
    public static final int AMOUNT_SCALE = 2;
    /** Value of {@code category_key} for a budget that covers every category; no real category has id 0. */
    public static final long ALL_CATEGORIES_KEY = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Null when the budget applies to every category. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    /**
     * Mirror of category_id that is never null. SQL treats NULLs as distinct, so a unique constraint on category_id
     * alone would let a user create several "all categories" budgets for the same period. Only setCategory writes it.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "category_key", nullable = false)
    private long categoryKey = ALL_CATEGORIES_KEY;

    @Column(nullable = false, precision = AMOUNT_PRECISION, scale = AMOUNT_SCALE)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 10)
    private BudgetPeriodType periodType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    public static long categoryKeyOf(Long categoryId) {
        return categoryId == null ? ALL_CATEGORIES_KEY : categoryId;
    }

    public void setCategory(Category category) {
        this.category = category;
        this.categoryKey = categoryKeyOf(category == null ? null : category.getId());
    }
}
