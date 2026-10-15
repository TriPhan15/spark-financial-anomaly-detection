package vn.edu.huit.finance.core.Security;

import java.util.Set;

/** Auth API paths shared by the controller, the security filter chain and {@link JwtAuthFilter}. */
public final class AuthPaths {

    public static final String BASE = "/api/auth";
    public static final String CHANGE_PASSWORD = "/change-password";
    public static final String LOGOUT = "/logout";

    public static final String CHANGE_PASSWORD_URL = BASE + CHANGE_PASSWORD;
    public static final String LOGOUT_URL = BASE + LOGOUT;

    /** The only endpoints under {@link #BASE} that need a JWT (they act on the caller's own session); every other one is public. */
    private static final Set<String> AUTHENTICATED_URLS = Set.of(CHANGE_PASSWORD_URL, LOGOUT_URL);

    private AuthPaths() {
    }

    /** True for an auth endpoint that is open to everybody, so the JWT filter has nothing to check on it. */
    public static boolean isPublic(String servletPath) {
        return servletPath.startsWith(BASE + "/") && !AUTHENTICATED_URLS.contains(servletPath);
    }
}
