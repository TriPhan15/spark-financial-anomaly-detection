package vn.edu.huit.finance.core.Dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** The query of GET /api/transactions/filter. There is deliberately no user field: it always searches the caller's own. */
@Getter
@Builder
public class TransactionFilterRequestDto {

    public static final String DEFAULT_SORT_BY = "transactionDate";
    public static final String DEFAULT_SORT_DIRECTION = "DESC";

    private final Long categoryId;
    private final BigDecimal minPrice;
    private final BigDecimal maxPrice;
    private final String vendorName;
    private final String sortBy;
    private final String sortDirection;
}
