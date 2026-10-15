package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.UserNotFoundException;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceLogoutTest {

    private static final String EMAIL = "alice@example.com";
    private static final String ACCESS_TOKEN = "access.jwt.token";
    private static final String REFRESH_TOKEN = "refresh.jwt.token";

    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private AuditLogService auditLogService;
    @Mock private TokenBlacklistService tokenBlacklistService;

    private AuthService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AuthService();
        ReflectionTestUtils.setField(service, "currentUserProvider", currentUserProvider);
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(service, "tokenBlacklistService", tokenBlacklistService);

        user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setEmail(EMAIL);
    }

    /** The AUTH_LOGOUT row, by the caller and about the caller's account, with no token in it. */
    private void assertLogoutRecorded() {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        assertEquals(AuditAction.AUTH_LOGOUT, entry.getValue().action());
        assertEquals(AuditActor.of(user), entry.getValue().actor());
        assertEquals(user.getId(), entry.getValue().targetId());
        assertFalse(entry.getValue().description().contains(ACCESS_TOKEN));
        assertFalse(entry.getValue().description().contains(REFRESH_TOKEN));
    }

    @Test
    void logout_revokesTheAccessTokenAndTheRefreshToken_andAudits() {
        when(currentUserProvider.getCurrentUser()).thenReturn(user);

        service.logout(ACCESS_TOKEN, REFRESH_TOKEN);

        verify(tokenBlacklistService).revoke(ACCESS_TOKEN);
        verify(tokenBlacklistService).revokeIfValid(REFRESH_TOKEN);
        assertLogoutRecorded();
    }

    @Test
    void logout_treatsTheRefreshTokenAsBestEffort_becauseTheClientSuppliedIt() {
        when(currentUserProvider.getCurrentUser()).thenReturn(user);

        service.logout(ACCESS_TOKEN, REFRESH_TOKEN);

        // revokeIfValid, not revoke: a garbage or expired refresh token must not make the logout fail.
        verify(tokenBlacklistService, never()).revoke(REFRESH_TOKEN);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void logout_withoutARefreshToken_revokesOnlyTheAccessToken(String refreshToken) {
        when(currentUserProvider.getCurrentUser()).thenReturn(user);

        service.logout(ACCESS_TOKEN, refreshToken);

        verify(tokenBlacklistService).revoke(ACCESS_TOKEN);
        verify(tokenBlacklistService, never()).revokeIfValid(any());
        assertLogoutRecorded();
    }

    @Test
    void logout_failsAndTouchesNothing_whenTheCallerCannotBeResolved() {
        when(currentUserProvider.getCurrentUser()).thenThrow(new UserNotFoundException("gone"));

        assertThrows(UserNotFoundException.class, () -> service.logout(ACCESS_TOKEN, REFRESH_TOKEN));

        verifyNoInteractions(tokenBlacklistService, auditLogService);
    }

    @Test
    void logout_doesNotSwallowAFailedRevocation_andWritesNoAuditEntryForALogoutThatDidNotHappen() {
        when(currentUserProvider.getCurrentUser()).thenReturn(user);
        doThrow(new IllegalStateException("database went away")).when(tokenBlacklistService).revoke(ACCESS_TOKEN);

        assertThrows(IllegalStateException.class, () -> service.logout(ACCESS_TOKEN, REFRESH_TOKEN));

        verify(tokenBlacklistService, never()).revokeIfValid(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void logout_revokesTheAccessTokenBeforeTheRefreshToken() {
        when(currentUserProvider.getCurrentUser()).thenReturn(user);

        service.logout(ACCESS_TOKEN, REFRESH_TOKEN);

        InOrder inOrder = inOrder(tokenBlacklistService);
        inOrder.verify(tokenBlacklistService).revoke(ACCESS_TOKEN);
        inOrder.verify(tokenBlacklistService).revokeIfValid(REFRESH_TOKEN);
    }
}
