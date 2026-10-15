package vn.edu.huit.finance.core.Dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ReceiptDto {
    private Long id;
    private String vendorName;
    private BigDecimal total;
    private LocalDate date;
    private String imageUrl;
}
