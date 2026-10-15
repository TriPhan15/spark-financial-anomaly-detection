package vn.edu.huit.finance.core.Dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class SavingGoalDto {
    private Long id;
    private String title;
    private BigDecimal target;
    private BigDecimal current;
    private String deadline;
}