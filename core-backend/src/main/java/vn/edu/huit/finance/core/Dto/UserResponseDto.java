package vn.edu.huit.finance.core.Dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class UserResponseDto {
    private Long id;
    private String username;
    private String email;
    private String phone;
    private String role;
    private BigDecimal walletBalance;
}