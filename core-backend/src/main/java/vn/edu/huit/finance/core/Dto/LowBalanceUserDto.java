package vn.edu.huit.finance.core.Dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.edu.huit.finance.core.Entity.User;

import java.math.BigDecimal;

/** A user whose wallet runs low, as the admin dashboard lists them: only what the page shows. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LowBalanceUserDto {
    private Long id;
    private String username;
    private String phone;
    private BigDecimal walletBalance;

    public static LowBalanceUserDto from(User user) {
        return LowBalanceUserDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .phone(user.getPhone())
                .walletBalance(user.getWalletBalance())
                .build();
    }
}
