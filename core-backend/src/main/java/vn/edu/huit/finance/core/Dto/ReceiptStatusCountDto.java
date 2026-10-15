package vn.edu.huit.finance.core.Dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** How many receipts have one status (the text stored in Receipt.status). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReceiptStatusCountDto {
    private String status;
    private long count;
}
