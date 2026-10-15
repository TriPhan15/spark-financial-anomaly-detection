package vn.edu.huit.finance.core.Dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** A user and the sum of all their expenses, for the admin dashboard's ranking. Cached in Redis as JSON. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopSpenderDto {
    private String username;
    private BigDecimal totalSpent;
}
