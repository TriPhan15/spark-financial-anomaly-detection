package vn.edu.huit.finance.core.Dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** The data of the app's home page for one user. Cached in Redis as JSON, so it must read back from JSON too. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppDashboardResponseDto {
    private List<TransactionResponseDto> recentTransactions;
    /** Null while the user has no transaction. */
    private TransactionResponseDto biggestTransaction;
    private List<TransactionResponseDto> saleTransactions;
}
