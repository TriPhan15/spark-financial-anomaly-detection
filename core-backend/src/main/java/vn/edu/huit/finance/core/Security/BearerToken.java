package vn.edu.huit.finance.core.Security;

import java.util.Optional;

/** Reads the JWT out of an {@code Authorization: Bearer <token>} header value; shared by the JWT filter and the auth controller. */
public final class BearerToken {

    private static final String PREFIX = "Bearer ";

    private BearerToken() {
    }

    /** The token, or empty when the header is missing, is not a Bearer header or carries nothing after the prefix. */
    public static Optional<String> extract(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String token = authorizationHeader.substring(PREFIX.length());
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
