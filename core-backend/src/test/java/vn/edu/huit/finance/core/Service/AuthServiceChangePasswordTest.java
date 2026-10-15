package vn.edu.huit.finance.core.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.AuthTokenResponseDto;
import vn.edu.huit.finance.core.Dto.ChangePasswordRequestDto;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.CredentialsChangeConflictException;
import vn.edu.huit.finance.core.Exception.InvalidPasswordException;
import vn.edu.huit.finance.core.Exception.UserNotFoundException;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;
import vn.edu.huit.finance.core.Security.JwtUtil;
import vn.edu.huit.finance.core.Security.TokenClaims;
import vn.edu.huit.finance.core.Security.TokenType;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditEvent;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceChangePasswordTest {

    private static final long USER_ID = 1L;
    private static final String EMAIL = "alice@example.com";
    private static final String OLD_PASSWORD = "OldPass123";
    private static final String NEW_PASSWORD = "NewPass456";
    // What a concurrent change had already committed by the time this request got the row lock.
    private static final String PASSWORD_SET_MEANWHILE = "Meanwhile789";
    private static final int VERSION_BEFORE = 4;
    // The access token that authorised the request; the service puts it on the blacklist once the password has changed.
    private static final String ACCESS_TOKEN = "access.jwt.token";

    @Mock private UserRepository userRepository;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private AuditLogService auditLogService;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private EntityManager entityManager;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    // Real: the tokens handed back are read to check the version they carry.
    private final JwtUtil jwtUtil = new JwtUtil();
    private AuthService service;
    // The copy the request read before it asked for the lock, and the row as the lock hands it over.
    private User readBefore;
    private User lockedRow;

    @BeforeEach
    void setUp() {
        service = new AuthService();
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "currentUserProvider", currentUserProvider);
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(service, "tokenBlacklistService", tokenBlacklistService);
        ReflectionTestUtils.setField(service, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "eventPublisher", eventPublisher);

        readBefore = alice(OLD_PASSWORD, VERSION_BEFORE);
        lockedRow = alice(OLD_PASSWORD, VERSION_BEFORE);
    }

    private User alice(String password, int credentialsVersion) {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("alice");
        user.setEmail(EMAIL);
        user.setPassword(passwordEncoder.encode(password));
        user.setCredentialsVersion(credentialsVersion);
        return user;
    }

    private void callerIsAlice() {
        when(currentUserProvider.getCurrentUser()).thenReturn(readBefore);
        when(entityManager.find(User.class, USER_ID, LockModeType.PESSIMISTIC_WRITE)).thenReturn(lockedRow);
    }

    /** The row still has the version the lock returned, so the guarded UPDATE changes it. */
    private void theGuardedUpdateApplies() {
        when(userRepository.updatePasswordAndEndSessions(eq(USER_ID), anyString(), eq(VERSION_BEFORE))).thenReturn(1);
    }

    /** The hash the one UPDATE wrote, next to the new version. */
    private String storedHash() {
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(userRepository).updatePasswordAndEndSessions(eq(USER_ID), hash.capture(), eq(VERSION_BEFORE));
        return hash.getValue();
    }

    /** The one AUTH_PASSWORD_CHANGED row the change wrote in its transaction. */
    private AuditEntry recordedEntry() {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        return entry.getValue();
    }

    /** The refusal published for a wrong old password: a row of its own, so it stays although the change rolls back. */
    private AuditEntry publishedRefusal() {
        ArgumentCaptor<SecurityAuditEvent> event = ArgumentCaptor.forClass(SecurityAuditEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        return event.getValue().entry();
    }

    private void assertNothingWritten() {
        verify(userRepository, never()).updatePasswordAndEndSessions(anyLong(), anyString(), anyInt());
        verify(userRepository, never()).save(any());
    }

    private ChangePasswordRequestDto request(String oldPassword, String newPassword) {
        ChangePasswordRequestDto dto = new ChangePasswordRequestDto();
        dto.setOldPassword(oldPassword);
        dto.setNewPassword(newPassword);
        return dto;
    }

    @Test
    void changePassword_storesBcryptHashOfNewPasswordAndAudits_whenOldPasswordMatches() {
        callerIsAlice();
        theGuardedUpdateApplies();

        service.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), ACCESS_TOKEN);

        String stored = storedHash();
        assertNotEquals(NEW_PASSWORD, stored);
        assertTrue(stored.startsWith("$2"), "expected a BCrypt hash");
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, stored));
        assertFalse(passwordEncoder.matches(OLD_PASSWORD, stored));
        // The entity mirrors the row; it is never saved (neither column is updatable through it).
        assertEquals(stored, lockedRow.getPassword());
        verify(userRepository, never()).save(any());
        AuditEntry entry = recordedEntry();
        assertEquals(AuditAction.AUTH_PASSWORD_CHANGED, entry.action());
        assertEquals(AuditActor.of(lockedRow), entry.actor(), "the caller, as the locked row names it");
        assertEquals(USER_ID, entry.targetId());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void changePassword_writesThePasswordChangedAuditRow_afterTheUpdate_withoutAnyPasswordHashOrToken() {
        callerIsAlice();
        theGuardedUpdateApplies();

        AuthTokenResponseDto tokens = service.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), ACCESS_TOKEN);

        // Envers never sees the bulk UPDATE, so this row is the audit trail; written once the UPDATE has applied.
        InOrder inOrder = inOrder(userRepository, auditLogService);
        inOrder.verify(userRepository).updatePasswordAndEndSessions(eq(USER_ID), anyString(), eq(VERSION_BEFORE));
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        inOrder.verify(auditLogService).record(entry.capture());
        assertEquals(AuditAction.AUTH_PASSWORD_CHANGED, entry.getValue().action());
        for (String secret : List.of(OLD_PASSWORD, NEW_PASSWORD, storedHash(), ACCESS_TOKEN, tokens.getToken(),
                tokens.getRefreshToken())) {
            assertFalse(entry.getValue().description().contains(secret), "the audit row must not contain " + secret);
        }
    }

    @Test
    void changePassword_writesThePasswordAndRaisesTheCredentialsVersionOnce_inOneUpdateGuardedByTheLockedVersion() {
        callerIsAlice();
        theGuardedUpdateApplies();

        service.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), ACCESS_TOKEN);

        verify(userRepository, times(1)).updatePasswordAndEndSessions(eq(USER_ID), anyString(), eq(VERSION_BEFORE));
        assertEquals(VERSION_BEFORE + 1, lockedRow.getCredentialsVersion());
    }

    @Test
    void changePassword_isRefusedWithAConflict_andIssuesAndRevokesNothing_whenTheGuardedUpdateMatchesNoRow() {
        callerIsAlice();
        // The credentials changed between the lock and the UPDATE: impossible under the lock, refused all the same.
        when(userRepository.updatePasswordAndEndSessions(eq(USER_ID), anyString(), eq(VERSION_BEFORE))).thenReturn(0);

        assertThrows(CredentialsChangeConflictException.class,
                () -> service.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), ACCESS_TOKEN));

        assertTrue(passwordEncoder.matches(OLD_PASSWORD, lockedRow.getPassword()));
        assertEquals(VERSION_BEFORE, lockedRow.getCredentialsVersion());
        verifyNoInteractions(auditLogService, tokenBlacklistService);
    }

    @Test
    void changePassword_handsBackATokenPairOfTheNewVersion_soThisDeviceStaysSignedIn() {
        callerIsAlice();
        theGuardedUpdateApplies();

        AuthTokenResponseDto tokens = service.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), ACCESS_TOKEN);

        TokenClaims access = jwtUtil.parse(tokens.getToken(), TokenType.ACCESS);
        TokenClaims refresh = jwtUtil.parse(tokens.getRefreshToken(), TokenType.REFRESH);
        for (TokenClaims claims : List.of(access, refresh)) {
            assertEquals(EMAIL, claims.subject());
            assertEquals(VERSION_BEFORE + 1, claims.credentialsVersion());
        }
        assertNotEquals(tokens.getToken(), tokens.getRefreshToken());
    }

    @Test
    void changePassword_locksTheRowAndReadsItAgain_beforeAnythingIsChecked() {
        callerIsAlice();
        theGuardedUpdateApplies();

        service.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), ACCESS_TOKEN);

        // Detached first: a lock on the copy read before would keep the password and version it was read with.
        InOrder inOrder = inOrder(entityManager, userRepository);
        inOrder.verify(entityManager).detach(readBefore);
        inOrder.verify(entityManager).find(User.class, USER_ID, LockModeType.PESSIMISTIC_WRITE);
        inOrder.verify(userRepository).updatePasswordAndEndSessions(eq(USER_ID), anyString(), eq(VERSION_BEFORE));
    }

    @Test
    void changePassword_checksTheOldPasswordAgainstTheLockedRow_soAChangeCommittedMeanwhileIsNotOverwritten() {
        // Another device changed the password while this request waited for the lock; its copy still has the old hash.
        lockedRow = alice(PASSWORD_SET_MEANWHILE, VERSION_BEFORE + 1);
        callerIsAlice();

        assertThrows(InvalidPasswordException.class,
                () -> service.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), ACCESS_TOKEN));

        assertTrue(passwordEncoder.matches(PASSWORD_SET_MEANWHILE, lockedRow.getPassword()));
        assertEquals(VERSION_BEFORE + 1, lockedRow.getCredentialsVersion());
        assertNothingWritten();
        verifyNoInteractions(auditLogService, tokenBlacklistService);
    }

    @Test
    void changePassword_revokesTheAccessTokenOfTheRequest_afterThePasswordIsSaved() {
        callerIsAlice();
        theGuardedUpdateApplies();

        service.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), ACCESS_TOKEN);

        InOrder inOrder = inOrder(userRepository, tokenBlacklistService);
        inOrder.verify(userRepository).updatePasswordAndEndSessions(eq(USER_ID), anyString(), eq(VERSION_BEFORE));
        inOrder.verify(tokenBlacklistService).revoke(ACCESS_TOKEN);
    }

    @Test
    void changePassword_doesNotSwallowAFailedRevocation_soTheTransactionRollsTheNewPasswordBack() {
        callerIsAlice();
        theGuardedUpdateApplies();
        doThrow(new IllegalStateException("database went away")).when(tokenBlacklistService).revoke(ACCESS_TOKEN);

        assertThrows(IllegalStateException.class, () -> service.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), ACCESS_TOKEN));

        // The failure reaches the transaction proxy, which rolls the saved password back with it (see TokenRevocationRollbackTest).
        verifyNoInteractions(auditLogService);
    }

    @Test
    void changePassword_andLogout_runInAWritableTransaction() throws NoSuchMethodException {
        List<Method> writers = List.of(
                AuthService.class.getMethod("changePassword", ChangePasswordRequestDto.class, String.class),
                AuthService.class.getMethod("logout", String.class, String.class));

        for (Method method : writers) {
            Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
            assertNotNull(transactional, method.getName() + " must be @Transactional");
            assertFalse(transactional.readOnly(), method.getName() + " writes, so it cannot be read-only");
        }
    }

    @Test
    void changePassword_throwsInvalidPassword_andKeepsHash_whenOldPasswordIsWrong() {
        callerIsAlice();
        String hashBefore = lockedRow.getPassword();

        assertThrows(InvalidPasswordException.class, () -> service.changePassword(request("wrong-old", NEW_PASSWORD), ACCESS_TOKEN));

        assertEquals(hashBefore, lockedRow.getPassword());
        assertEquals(VERSION_BEFORE, lockedRow.getCredentialsVersion());
        assertNothingWritten();
        verifyNoInteractions(auditLogService, tokenBlacklistService);
        // No success row; the refusal is recorded on its own, with the caller and without any password.
        AuditEntry refusal = publishedRefusal();
        assertEquals(AuditAction.AUTH_PASSWORD_CHANGE_REJECTED, refusal.action());
        assertEquals(AuditActor.of(lockedRow), refusal.actor());
        assertEquals(USER_ID, refusal.targetId());
        assertFalse(refusal.description().contains("wrong-old"));
        assertFalse(refusal.description().contains(NEW_PASSWORD));
    }

    @Test
    void changePassword_throwsInvalidPassword_whenNewPasswordEqualsOld() {
        callerIsAlice();
        String hashBefore = lockedRow.getPassword();

        assertThrows(InvalidPasswordException.class, () -> service.changePassword(request(OLD_PASSWORD, OLD_PASSWORD), ACCESS_TOKEN));

        assertEquals(hashBefore, lockedRow.getPassword());
        assertNothingWritten();
        // The old password was right: a new one equal to it is a mistake, not a guess, so nothing is recorded.
        verifyNoInteractions(auditLogService, tokenBlacklistService, eventPublisher);
    }

    @Test
    void changePassword_passesOnUserNotFound_andTouchesNothing_whenTheCallerCannotBeResolved() {
        when(currentUserProvider.getCurrentUser()).thenThrow(new UserNotFoundException("gone"));

        assertThrows(UserNotFoundException.class, () -> service.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), ACCESS_TOKEN));

        verifyNoInteractions(userRepository, auditLogService, tokenBlacklistService, entityManager);
    }
}
