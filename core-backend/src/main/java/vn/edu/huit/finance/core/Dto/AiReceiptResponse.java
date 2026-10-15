package vn.edu.huit.finance.core.Dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class AiReceiptResponse {
    private String vendorName;
    private BigDecimal totalAmount;
    private String receiptDate;
}
