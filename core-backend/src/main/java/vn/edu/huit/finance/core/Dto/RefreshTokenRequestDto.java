package vn.edu.huit.finance.core.Dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

// Deliberately not @Data: its generated toString() would print the token.
@Getter
@Setter
public class RefreshTokenRequestDto {

    private static final int TOKEN_MAX_LENGTH = 2048;

    /** Not @NotBlank on purpose: a missing token keeps its old answer, 401 from the service, not a validation 400. */
    @Size(max = TOKEN_MAX_LENGTH, message = "Refresh token không hợp lệ")
    private String refreshToken;
}
