package vn.edu.huit.finance.core.Dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreatePaymentRequestDto {

    @NotNull(message = "type khong duoc rong")
    private String type;

    private String provider;

    @NotNull(message = "amount khong duoc rong")
    @DecimalMin(value = "1000", message = "So tien toi thieu 1.000")
    private BigDecimal amount;

    private String description;
}
