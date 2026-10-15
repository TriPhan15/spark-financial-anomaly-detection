package vn.edu.huit.finance.core.Security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthPathsTest {

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/login", "/api/auth/register", "/api/auth/refresh", "/api/auth/forgot-password",
            "/api/auth/reset-password", "/api/auth/social-login"})
    void theOtherAuthEndpointsArePublic(String path) {
        assertTrue(AuthPaths.isPublic(path));
    }

    @Test
    void changePasswordAndLogoutNeedAJwt_becauseTheyActOnTheCallersOwnSession() {
        assertFalse(AuthPaths.isPublic(AuthPaths.CHANGE_PASSWORD_URL));
        assertFalse(AuthPaths.isPublic(AuthPaths.LOGOUT_URL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/api/budgets", "/api/notifications", "/api/authentic/login", "/api/auth"})
    void pathsOutsideTheAuthApiAreNotPublicAuthEndpoints(String path) {
        assertFalse(AuthPaths.isPublic(path));
    }

    @Test
    void theUrlsAreBuiltFromTheBase() {
        assertEquals("/api/auth/logout", AuthPaths.LOGOUT_URL);
        assertEquals("/api/auth/change-password", AuthPaths.CHANGE_PASSWORD_URL);
    }
}
