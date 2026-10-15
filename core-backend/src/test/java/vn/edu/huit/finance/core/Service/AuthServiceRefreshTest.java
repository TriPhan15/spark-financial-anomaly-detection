package vn.edu.huit.finance.core.Service;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.MissingClaimException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.AuthTokenResponseDto;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.InvalidTokenException;
import vn.edu.huit.finance.core.Security.CustomUserDetailsService;
import vn.edu.huit.finance.core.Security.JwtUtil;
import vn.edu.huit.finance.core.Security.Roles;
import vn.edu.huit.finance.core.Security.TokenClaims;
import vn.edu.huit.finance.core.Security.TokenType;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditEvent;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The order of the checks in AuthService.refresh, which is what makes it safe: only a usable token of type REFRESH gets
 * as far as the database; then the user's row is locked, a locked account is refused, the blacklist is read and the
 * credentials version compared against the row as the lock returned it, then the old token is revoked, and only then
 * is a new pair issued. The same rules against a real database and real threads are in CrossSessionTokenRevocationTest.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceRefreshTest {

    private static final long USER_ID = 1L;
    private static final String EMAIL = "alice@example.com";
    private static final String REFRESH_TOKEN = "refresh.jwt.token";
    private static final int CURRENT_VERSION = 2;
    private static final String REFUSED_MESSAGE = "Refresh token không hợp lệ hoặc đã hết hạn";

    @Mock private JwtUtil jwtUtil;
    @Mock private CustomUserDetailsService userDetailsService;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private EntityManager entityManager;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AuthService service;
    // The copy read before the lock was asked for, and the row as the lock hands it over.
    private User readBefore;
    private User lockedRow;

    @BeforeEach
    void setUp() {
        service = new AuthService();
        ReflectionTestUtils.setField(service, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(service, "userDetailsService", userDetailsService);
        ReflectionTestUtils.setField(service, "tokenBlacklistService", tokenBlacklistService);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "eventPublisher", eventPublisher);

        readBefore = alice(CURRENT_VERSION);
        lockedRow = alice(CURRENT_VERSION);
    }

    private static User alice(int credentialsVersion) {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("alice");
        user.setEmail(EMAIL);
        user.setCredentialsVersion(credentialsVersion);
        return user;
    }

    /** A signed, unexpired refresh token of Alice's, issued for {@code tokenVersion}. */
    private void aSignedRefreshTokenOfAlice(int tokenVersion) {
        when(jwtUtil.parse(REFRESH_TOKEN, TokenType.REFRESH)).thenReturn(new TokenClaims(EMAIL, tokenVersion));
        when(userDetailsService.loadApplicationUser(EMAIL)).thenReturn(readBefore);
        when(entityManager.find(User.class, USER_ID, LockModeType.PESSIMISTIC_WRITE)).thenReturn(lockedRow);
    }

    /** Refused with the one generic answer, nothing revoked or issued, and the refusal recorded once, on its own. */
    private AuditEntry assertRefusedAndNothingIssued() {
        InvalidTokenException refused = assertThrows(InvalidTokenException.class, () -> service.refresh(REFRESH_TOKEN));

        assertEquals(REFUSED_MESSAGE, refused.getMessage());
        verify(tokenBlacklistService, never()).revoke(anyString());
        verify(jwtUtil, never()).generateToken(anyString(), anyInt());
        verify(jwtUtil, never()).generateRefreshToken(anyString(), anyInt());
        return recordedRefusal();
    }

    private AuditEntry recordedRefusal() {
        ArgumentCaptor<SecurityAuditEvent> event = ArgumentCaptor.forClass(SecurityAuditEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        AuditEntry entry = event.getValue().entry();
        assertEquals(AuditAction.AUTH_TOKEN_REFRESH_REJECTED, entry.action());
        assertFalse(entry.description().contains(REFRESH_TOKEN), "the token itself is never recorded");
        return entry;
    }

    /** The caller is not signed in: the token's subject is only the identity claimed, the account is the target. */
    private static void assertAboutAlicesAccount(AuditEntry refusal) {
        assertEquals(AuditActor.unauthenticated(EMAIL), refusal.actor());
        assertEquals(USER_ID, refusal.targetId());
    }

    @Test
    void refresh_locksTheUserBeforeReadingTheBlacklist_andRevokesTheOldTokenBeforeIssuingTheNewPair() {
        aSignedRefreshTokenOfAlice(CURRENT_VERSION);
        when(jwtUtil.generateToken(EMAIL, CURRENT_VERSION)).thenReturn("new.access.token");
        when(jwtUtil.generateRefreshToken(EMAIL, CURRENT_VERSION)).thenReturn("new.refresh.token");

        AuthTokenResponseDto tokens = service.refresh(REFRESH_TOKEN);

        assertEquals("new.access.token", tokens.getToken());
        assertEquals("new.refresh.token", tokens.getRefreshToken());
        InOrder inOrder = inOrder(entityManager, tokenBlacklistService, jwtUtil);
        inOrder.verify(jwtUtil).parse(REFRESH_TOKEN, TokenType.REFRESH);
        inOrder.verify(entityManager).detach(readBefore);
        inOrder.verify(entityManager).find(User.class, USER_ID, LockModeType.PESSIMISTIC_WRITE);
        inOrder.verify(tokenBlacklistService).isRevoked(REFRESH_TOKEN);
        inOrder.verify(tokenBlacklistService).revoke(REFRESH_TOKEN);
        inOrder.verify(jwtUtil).generateToken(EMAIL, CURRENT_VERSION);
        inOrder.verify(jwtUtil).generateRefreshToken(EMAIL, CURRENT_VERSION);
        // A refresh that succeeds is not recorded: it happens every few minutes for every signed-in device.
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void refresh_refusesATokenOfTheVersionBeforeAPasswordChangeThatCommittedWhileItWaitedForTheLock() {
        // Read before the change committed, so the copy still has the token's version; the locked row has the new one.
        lockedRow = alice(CURRENT_VERSION + 1);
        aSignedRefreshTokenOfAlice(CURRENT_VERSION);

        AuditEntry refusal = assertRefusedAndNothingIssued();
        assertAboutAlicesAccount(refusal);
        assertTrue(refusal.description().contains("đổi mật khẩu"), refusal.description());
    }

    @Test
    void refresh_refusesATokenThatWasAlreadyUsedOrRevoked() {
        aSignedRefreshTokenOfAlice(CURRENT_VERSION);
        when(tokenBlacklistService.isRevoked(REFRESH_TOKEN)).thenReturn(true);

        AuditEntry refusal = assertRefusedAndNothingIssued();
        assertAboutAlicesAccount(refusal);
        assertTrue(refusal.description().contains("thu hồi"), refusal.description());
    }

    // How JwtUtil.parse turns down each token that is not a usable refresh token.
    static Stream<Arguments> unusableRefreshTokens() {
        return Stream.of(
                Arguments.of("an access token", new UnsupportedJwtException("not of type REFRESH")),
                Arguments.of("a token without a type", new UnsupportedJwtException("not of type REFRESH")),
                Arguments.of("a token without a credentials version", new MissingClaimException(null, null, "no version")),
                Arguments.of("an expired token", new ExpiredJwtException(null, null, "expired")),
                Arguments.of("a token signed with another key", new SignatureException("signature does not match")),
                Arguments.of("garbage", new MalformedJwtException("not a JWT")),
                Arguments.of("an empty token", new IllegalArgumentException("empty")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableRefreshTokens")
    void refresh_refusesATokenThatIsNotAUsableRefreshToken_withoutTouchingTheDatabase(String kind, RuntimeException refusal) {
        when(jwtUtil.parse(REFRESH_TOKEN, TokenType.REFRESH)).thenThrow(refusal);

        AuditEntry recorded = assertRefusedAndNothingIssued();
        verifyNoInteractions(userDetailsService, entityManager, tokenBlacklistService);
        // Nothing in an unusable token is trusted: nobody is named, no account is the target, only the kind of failure.
        assertEquals(AuditActor.unauthenticated(null), recorded.actor());
        assertNull(recorded.targetId());
        assertTrue(recorded.description().contains(refusal.getClass().getSimpleName()), recorded.description());
    }

    @Test
    void refresh_refusesATokenWhoseAccountIsGone_withoutLockingAnything() {
        when(jwtUtil.parse(REFRESH_TOKEN, TokenType.REFRESH)).thenReturn(new TokenClaims(EMAIL, CURRENT_VERSION));
        when(userDetailsService.loadApplicationUser(EMAIL)).thenThrow(new UsernameNotFoundException("gone"));

        AuditEntry refusal = assertRefusedAndNothingIssued();
        verifyNoInteractions(entityManager);
        assertEquals(AuditActor.unauthenticated(EMAIL), refusal.actor());
        assertNull(refusal.targetId());
    }

    @Test
    void refresh_refusesTheTokenOfADeactivatedAccount_asTheLockedRowHasIt() {
        // Deactivated by an administrator while the refresh waited for the lock: the copy read before is still active.
        lockedRow.setActive(false);
        aSignedRefreshTokenOfAlice(CURRENT_VERSION);

        assertAboutAlicesAccount(assertRefusedAndNothingIssued());
        verify(entityManager).find(User.class, USER_ID, LockModeType.PESSIMISTIC_WRITE);
        verify(tokenBlacklistService, never()).isRevoked(anyString());
    }

    @Test
    void refresh_refusesTheTokenOfAnAccountWithTheLockedRole() {
        lockedRow.setRole(Roles.LOCKED);
        aSignedRefreshTokenOfAlice(CURRENT_VERSION);

        assertAboutAlicesAccount(assertRefusedAndNothingIssued());
        verify(tokenBlacklistService, never()).isRevoked(anyString());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void refresh_refusesAMissingToken_withoutParsingAnything(String missing) {
        InvalidTokenException refused = assertThrows(InvalidTokenException.class, () -> service.refresh(missing));

        assertEquals(REFUSED_MESSAGE, refused.getMessage());
        verifyNoInteractions(jwtUtil, userDetailsService, entityManager, tokenBlacklistService);
        assertEquals(AuditActor.unauthenticated(null), recordedRefusal().actor());
    }

    @Test
    void refresh_issuesNothing_whenTheOldTokenCannotBeRevoked() {
        aSignedRefreshTokenOfAlice(CURRENT_VERSION);
        doThrow(new IllegalStateException("database went away")).when(tokenBlacklistService).revoke(REFRESH_TOKEN);

        assertThrows(IllegalStateException.class, () -> service.refresh(REFRESH_TOKEN));

        verify(jwtUtil, never()).generateToken(anyString(), anyInt());
        verify(jwtUtil, never()).generateRefreshToken(anyString(), anyInt());
    }

    @Test
    void refresh_runsInOneWritableTransaction() throws NoSuchMethodException {
        // Mockito bypasses Spring's proxy: the lock, the blacklist read and the revocation share this transaction.
        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(
                AuthService.class.getMethod("refresh", String.class), Transactional.class);

        assertNotNull(transactional, "refresh must be @Transactional");
        assertFalse(transactional.readOnly(), "refresh revokes the old token, so it cannot be read-only");
    }
}
