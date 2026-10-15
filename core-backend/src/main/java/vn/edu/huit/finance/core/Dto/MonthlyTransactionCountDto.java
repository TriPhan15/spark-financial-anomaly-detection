package vn.edu.huit.finance.core.Dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.YearMonth;

/** How many transactions (of every user) are dated inside one month: a bar of the admin dashboard's chart. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyTransactionCountDto {
    private YearMonth month;
    private long transactionCount;
}
