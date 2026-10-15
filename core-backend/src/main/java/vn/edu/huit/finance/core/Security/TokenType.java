package vn.edu.huit.finance.core.Security;

/**
 * What a JWT may be used for, written into its JOSE header "typ". An access token opens the API; a refresh token is only
 * good for trading in at /api/auth/refresh. Each side refuses the other kind, and a token without a type is refused by
 * both (tokens of the releases before the type existed: their holders sign in again).
 */
public enum TokenType {
    ACCESS,
    REFRESH
}
