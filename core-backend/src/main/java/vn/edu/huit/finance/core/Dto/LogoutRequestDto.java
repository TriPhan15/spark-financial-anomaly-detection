package vn.edu.huit.finance.core.Dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

// Deliberately not @Data: its generated toString() would print the token.
@Getter
@Setter
public class LogoutRequestDto {

    private static final int TOKEN_MAX_LENGTH = 2048;

    /** Optional. The refresh token of the session that ends; when it is left out, the app could sign in again with it. */
    @Size(max = TOKEN_MAX_LENGTH, message = "Refresh token không hợp lệ")
    private String refreshToken;
}
