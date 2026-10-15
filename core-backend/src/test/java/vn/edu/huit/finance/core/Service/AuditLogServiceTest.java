package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vn.edu.huit.finance.core.Entity.ActionLog;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.AuditLogWriteException;
import vn.edu.huit.finance.core.Exception.UserNotFoundException;
import vn.edu.huit.finance.core.Repository.ActionLogRepository;
import vn.edu.huit.finance.core.Security.ClientIp;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;
import vn.edu.huit.finance.core.Security.JwtUtil;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** What one action_logs row holds, whoever writes it, and what happens when it cannot be written. */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T08:30:00Z");
    private static final String CLIENT = "203.0.113.7";
    private static final String PROXY = "10.0.0.2";

    @Mock private ActionLogRepository actionLogRepository;
    @Mock private CurrentUserProvider currentUserProvider;
    @InjectMocks private AuditLogService service;

    private User alice;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        alice = new User();
        alice.setId(7L);
        alice.setEmail("alice@example.com");
    }

    @AfterEach
    void forgetTheRequest() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private void servingARequestFrom(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(PROXY);
        request.addHeader(ClientIp.FORWARDED_FOR_HEADER, forwardedFor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private ActionLog written() {
        ArgumentCaptor<ActionLog> row = ArgumentCaptor.forClass(ActionLog.class);
        verify(actionLogRepository).save(row.capture());
        return row.getValue();
    }

    @Test
    void aRowNamesTheActorTheActionTheTargetTheResultTheClientAndTheTime() {
        servingARequestFrom(CLIENT + ", " + PROXY);

        service.record(AuditEntry.of(AuditAction.TRANSACTION_DELETED, AuditActor.of(alice), 42L, "Xóa giao dịch chi 150000 VNĐ"));

        ActionLog row = written();
        assertEquals(7L, row.getUserId());
        assertEquals("alice@example.com", row.getUserEmail());
        assertEquals("TRANSACTION_DELETED", row.getAction());
        assertEquals("TRANSACTION", row.getTargetType());
        assertEquals(42L, row.getTargetId());
        assertEquals("SUCCESS", row.getResult());
        assertEquals("Xóa giao dịch chi 150000 VNĐ", row.getDescription());
        assertEquals(CLIENT, row.getIpAddress(), "the client behind the proxy, never the proxy or the whole header");
        assertEquals(LocalDateTime.of(2026, 10, 1, 8, 30), row.getTimestamp());
    }

    @Test
    void aRefusalIsWrittenAsAFailure() {
        service.recordIndependently(AuditEntry.of(AuditAction.AUTH_LOGIN_FAILED, AuditActor.unauthenticated("bob@example.com"),
                null, "Sai thông tin đăng nhập"));

        ActionLog row = written();
        assertEquals("FAILURE", row.getResult());
        assertNull(row.getUserId(), "a caller that was not signed in is no account");
        assertEquals("bob@example.com", row.getUserEmail());
    }

    @Test
    void aRowAboutNoRecordHasNoTarget_andOutsideARequestNoAddress() {
        service.record(AuditEntry.of(AuditAction.SYSTEM_LOW_BALANCE_WARNING, AuditActor.system(), null, "Số dư thấp"));

        ActionLog row = written();
        assertNull(row.getTargetType());
        assertNull(row.getTargetId());
        assertNull(row.getUserId());
        assertEquals(AuditActor.SYSTEM_LABEL, row.getUserEmail());
        assertNull(row.getIpAddress(), "a scheduled job serves no request");
    }

    @Test
    void whatTheClientClaimedIsCleanedAndCut_soItCanNeitherForgeALineNorOverflowItsColumn() {
        String forged = "x".repeat(300) + "\r\nAUTH_LOGIN_SUCCESS admin@example.com";

        service.recordIndependently(AuditEntry.of(AuditAction.AUTH_LOGIN_FAILED, AuditActor.unauthenticated(forged), null,
                "Sai thông tin đăng nhập\nAUTH_LOGIN_SUCCESS"));

        ActionLog row = written();
        assertEquals(ActionLog.USER_EMAIL_MAX_LENGTH, row.getUserEmail().length());
        assertFalse(row.getUserEmail().contains("\n"));
        assertEquals("Sai thông tin đăng nhập AUTH_LOGIN_SUCCESS", row.getDescription());
    }

    @Test
    void aDescriptionLongerThanItsColumnIsCut_insteadOfMakingTheInsertFailAndTheChangeRollBack() {
        service.record(AuditEntry.of(AuditAction.USER_UPDATED, AuditActor.of(alice), 7L, "y".repeat(5000)));

        assertEquals(ActionLog.DESCRIPTION_MAX_LENGTH, written().getDescription().length());
    }

    @Test
    void aTokenOrAPasswordHashNeverReachesTheTable_evenIfACallerPassedOne() {
        String token = new JwtUtil().generateToken("alice@example.com", 0);
        String hash = "$2a$10$" + "a".repeat(53);

        service.record(AuditEntry.of(AuditAction.AUTH_LOGOUT, AuditActor.of(alice), 7L,
                "token " + token + " header Bearer " + token + " hash " + hash));

        String description = written().getDescription();
        assertFalse(description.contains(token));
        assertFalse(description.contains(hash));
        assertFalse(description.contains("eyJ"));
    }

    @Test
    void theCurrentCallerIsLookedUpWhenTheRowIsWritten() {
        when(currentUserProvider.getCurrentUser()).thenReturn(alice);

        service.record(AuditEntry.of(AuditAction.RECEIPT_REJECTED, AuditActor.currentCaller(), 3L, "Từ chối hóa đơn"));

        ActionLog row = written();
        assertEquals(7L, row.getUserId());
        assertEquals("alice@example.com", row.getUserEmail());
    }

    @Test
    void aCurrentCallerThatIsNotAnAccountIsNamedByItsPrincipal_withoutAnyAccountId() {
        when(currentUserProvider.getCurrentUser()).thenThrow(new UserNotFoundException("gone"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "deleted-admin", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));

        service.recordIndependently(AuditEntry.of(AuditAction.AUTH_ACCESS_DENIED, AuditActor.currentCaller(), null, "GET /admin"));

        ActionLog row = written();
        assertNull(row.getUserId());
        assertEquals("deleted-admin", row.getUserEmail());
    }

    @Test
    void anAnonymousCurrentCallerIsAnonymous() {
        when(currentUserProvider.getCurrentUser()).thenThrow(new UserNotFoundException("anonymous"));
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        service.recordIndependently(AuditEntry.of(AuditAction.AUTH_ACCESS_DENIED, AuditActor.currentCaller(), null, "GET /x"));

        assertEquals(AuditActor.ANONYMOUS_LABEL, written().getUserEmail());
    }

    @Test
    void aRowThatCannotBeWrittenIsNeverSwallowed_theCauseStaysOnTheServerAndTheClientGetsAFixedText() {
        DataIntegrityViolationException cause = new DataIntegrityViolationException(
                "could not execute statement [ERROR: value too long for type character varying(255)] [insert into action_logs ...]");
        when(actionLogRepository.save(any(ActionLog.class))).thenThrow(cause);

        AuditLogWriteException thrown = assertThrows(AuditLogWriteException.class, () -> service.record(
                AuditEntry.of(AuditAction.TRANSACTION_CREATED, AuditActor.of(alice), 1L, "Tạo giao dịch")));

        assertSame(cause, thrown.getCause());
        assertFalse(thrown.getMessage().contains("action_logs"));
        assertFalse(thrown.getMessage().contains("ERROR"));
    }

    @Test
    void aChangeIsRecordedInsideItsTransaction_aRefusalInATransactionOfItsOwn() throws NoSuchMethodException {
        Transactional record = AnnotatedElementUtils.findMergedAnnotation(
                AuditLogService.class.getMethod("record", AuditEntry.class), Transactional.class);
        Transactional independent = AnnotatedElementUtils.findMergedAnnotation(
                AuditLogService.class.getMethod("recordIndependently", AuditEntry.class), Transactional.class);

        assertNotNull(record);
        assertEquals(Propagation.MANDATORY, record.propagation(), "a change and its row commit or roll back together");
        assertNotNull(independent);
        assertEquals(Propagation.REQUIRES_NEW, independent.propagation(), "a refusal stays although its request rolls back");
    }
}
