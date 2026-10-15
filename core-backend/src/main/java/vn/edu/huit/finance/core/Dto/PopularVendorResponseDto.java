package vn.edu.huit.finance.core.Dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A vendor of the scanned receipts and how many transactions carry it. Cached in Redis as JSON. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PopularVendorResponseDto {
    private String vendorName;
    private long transactionCount;
}
