package vn.edu.huit.finance.core.Dto;

import lombok.Builder;
import lombok.Data;
import vn.edu.huit.finance.core.Entity.Budget;
import vn.edu.huit.finance.core.Entity.BudgetPeriodType;
import vn.edu.huit.finance.core.Entity.Category;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class BudgetResponseDto {
    private Long id;
    /** Null, like categoryName, when the budget applies to every category. */
    private Long categoryId;
    private String categoryName;
    private BigDecimal amount;
    private BudgetPeriodType periodType;
    private LocalDate startDate;
    private LocalDate endDate;

    public static BudgetResponseDto from(Budget budget) {
        Category category = budget.getCategory();
        return BudgetResponseDto.builder()
                .id(budget.getId())
                .categoryId(category == null ? null : category.getId())
                .categoryName(category == null ? null : category.getName())
                .amount(budget.getAmount())
                .periodType(budget.getPeriodType())
                .startDate(budget.getStartDate())
                .endDate(budget.getEndDate())
                .build();
    }
}
