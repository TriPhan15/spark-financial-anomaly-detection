package vn.edu.huit.finance.core.Security;

/**
 * What a verified token says: whose it is and which credentials version it was issued for. Only
 * {@link JwtUtil#parse(String, TokenType)} creates one from a token, after checking the signature, the expiry and the
 * type, so neither value is ever null there.
 */
public record TokenClaims(String subject, Integer credentialsVersion) {
}
