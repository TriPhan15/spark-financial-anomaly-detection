package vn.edu.huit.finance.core.Dto;

import lombok.Builder;
import lombok.Getter;

/**
 * A freshly issued token pair. The field names are the ones the login and refresh answers have always used ("token" for
 * the access token), which the mobile app reads. Deliberately no toString(): it would print both tokens.
 */
@Getter
@Builder
public class AuthTokenResponseDto {

    private final String token;
    private final String refreshToken;
}
