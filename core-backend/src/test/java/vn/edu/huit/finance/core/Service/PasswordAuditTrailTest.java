package vn.edu.huit.finance.core.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.AuthTokenResponseDto;
import vn.edu.huit.finance.core.Dto.ChangePasswordRequestDto;
import vn.edu.huit.finance.core.Entity.ActionLog;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.AuditResult;
import vn.edu.huit.finance.core.Entity.AuditTargetType;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.AuditLogWriteException;
import vn.edu.huit.finance.core.Exception.InvalidPasswordException;
import vn.edu.huit.finance.core.Repository.ActionLogRepository;
import vn.edu.huit.finance.core.Repository.RevokedTokenRepository;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;
import vn.edu.huit.finance.core.Security.CustomUserDetailsService;
import vn.edu.huit.finance.core.Security.JwtUtil;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditListener;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The audit trail of a password change or reset. The password is written by a bulk UPDATE, which Hibernate Envers never
 * sees, so the action_logs row written by the real AuditLogService is the only record. It joins the transaction of the
 * change: no row for a change that did not happen, and no change without its row. A refusal (wrong old password, wrong
 * OTP) is written on its own by the real SecurityAuditListener, and stays although the refused change rolls back.
 * Nothing runs inside a test transaction; every check reads H2 again.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AuthService.class, AuditLogService.class, SecurityAuditListener.class, TokenBlacklistService.class, JwtUtil.class,
        CustomUserDetailsService.class, PasswordAuditTrailTest.EncoderConfig.class})
class PasswordAuditTrailTest {

    private static final String EMAIL = "alice@example.com";
    private static final String OLD_PASSWORD = "OldPass123";
    private static final String NEW_PASSWORD = "NewPass456";
    private static final String PASSWORD_CHANGED = AuditAction.AUTH_PASSWORD_CHANGED.name();
    private static final String PASSWORD_RESET = AuditAction.AUTH_PASSWORD_RESET.name();
    private static final Pattern OTP_IN_MAIL = Pattern.compile("\\d{6}");

    @TestConfiguration
    static class EncoderConfig {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }

    @Autowired private AuthService authService;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserRepository userRepository;
    @Autowired private RevokedTokenRepository revokedTokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManagerFactory entityManagerFactory;
    // Real behaviour; a spy only so that one test can make the audit insert fail.
    @SpyBean private ActionLogRepository actionLogRepository;
    @MockBean private CurrentUserProvider currentUserProvider;
    @MockBean private JavaMailSender mailSender;

    private Long aliceId;

    @BeforeEach
    void setUp() {
        User alice = new User();
        alice.setUsername("alice");
        alice.setEmail(EMAIL);
        alice.setPhone("0900000001");
        alice.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        aliceId = userRepository.saveAndFlush(alice).getId();
        when(currentUserProvider.getCurrentUser()).thenAnswer(invocation -> userRepository.findById(aliceId).orElseThrow());
    }

    @AfterEach
    void cleanUp() {
        actionLogRepository.deleteAll();
        revokedTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    private static ChangePasswordRequestDto request(String oldPassword, String newPassword) {
        ChangePasswordRequestDto dto = new ChangePasswordRequestDto();
        dto.setOldPassword(oldPassword);
        dto.setNewPassword(newPassword);
        return dto;
    }

    private User aliceInDatabase() {
        return userRepository.findById(aliceId).orElseThrow();
    }

    /** Requests an OTP and reads it back from the mail the service sent. */
    private String mailedOtp() {
        authService.generateAndSendOtp(EMAIL);
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        Matcher otp = OTP_IN_MAIL.matcher(mail.getValue().getText());
        assertTrue(otp.find(), "the mail should contain the OTP");
        return otp.group();
    }

    private int enversRevisionsOfAlice() {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            return AuditReaderFactory.get(entityManager).getRevisions(User.class, aliceId).size();
        }
    }

    private List<ActionLog> rowsOf(AuditAction action) {
        return actionLogRepository.findAll().stream().filter(row -> action.name().equals(row.getAction())).toList();
    }

    private static void assertContainsNone(ActionLog row, List<String> secrets) {
        String everything = row.getAction() + " " + row.getDescription() + " " + row.getUserEmail() + " " + row.getIpAddress();
        for (String secret : secrets) {
            assertFalse(everything.contains(secret), "the audit row must not contain " + secret);
        }
    }

    // ------------------------------------------------------------------------------------------------- change password

    @Test
    void aPasswordChange_leavesOnePasswordChangedRow_althoughEnversRecordsNoRevisionForIt() {
        String accessToken = jwtUtil.generateToken(EMAIL, 0);
        int revisionsBefore = enversRevisionsOfAlice();

        AuthTokenResponseDto renewed = authService.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), accessToken);

        assertEquals(revisionsBefore, enversRevisionsOfAlice(), "the bulk UPDATE is invisible to Envers");
        List<ActionLog> rows = actionLogRepository.findAll();
        assertEquals(1, rows.size());
        ActionLog row = rows.get(0);
        assertEquals(PASSWORD_CHANGED, row.getAction());
        assertEquals(EMAIL, row.getUserEmail());
        assertEquals(aliceId, row.getUserId());
        assertEquals(AuditTargetType.USER.name(), row.getTargetType());
        assertEquals(aliceId, row.getTargetId());
        assertEquals(AuditResult.SUCCESS.name(), row.getResult());
        assertNotNull(row.getTimestamp());
        assertContainsNone(row, List.of(OLD_PASSWORD, NEW_PASSWORD, aliceInDatabase().getPassword(), accessToken,
                renewed.getToken(), renewed.getRefreshToken()));
    }

    @Test
    void whenTheAuditRowCannotBeWritten_thePasswordChangeIsRolledBackWhole() {
        doThrow(new IllegalStateException("action_logs unavailable")).when(actionLogRepository).save(any(ActionLog.class));
        String accessToken = jwtUtil.generateToken(EMAIL, 0);

        AuditLogWriteException failed = assertThrows(AuditLogWriteException.class,
                () -> authService.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), accessToken));
        assertFalse(failed.getMessage().contains("action_logs unavailable"), "the cause stays on the server");

        User alice = aliceInDatabase();
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, alice.getPassword()), "no password change without its audit row");
        assertEquals(0, alice.getCredentialsVersion());
        assertEquals(0, revokedTokenRepository.count(), "the revocation of the request's token goes back too");
        assertEquals(0, actionLogRepository.count());
    }

    @Test
    void aRefusedPasswordChange_writesNoSuccessRow_butItsRefusalStays_althoughTheChangeRolledBack() {
        String accessToken = jwtUtil.generateToken(EMAIL, 0);

        assertThrows(InvalidPasswordException.class,
                () -> authService.changePassword(request("WrongOld123", NEW_PASSWORD), accessToken));

        assertEquals(List.of(), rowsOf(AuditAction.AUTH_PASSWORD_CHANGED));
        List<ActionLog> refusals = rowsOf(AuditAction.AUTH_PASSWORD_CHANGE_REJECTED);
        assertEquals(1, refusals.size(), "written in a transaction of its own, so the rollback of the change kept it");
        ActionLog refusal = refusals.get(0);
        assertEquals(aliceId, refusal.getUserId());
        assertEquals(aliceId, refusal.getTargetId());
        assertEquals(AuditResult.FAILURE.name(), refusal.getResult());
        assertContainsNone(refusal, List.of("WrongOld123", NEW_PASSWORD, OLD_PASSWORD, accessToken));
        assertEquals(1, actionLogRepository.count());
    }

    // ---------------------------------------------------------------------------------------------- reset password (OTP)

    @Test
    void aResetWithAnOtp_leavesOnePasswordResetRow_withoutTheOtpOrAnyPassword() {
        String otp = mailedOtp();

        authService.verifyOtpAndResetPassword(EMAIL, otp, NEW_PASSWORD);

        // The request of the OTP has its own row; the reset has one, in its transaction.
        assertEquals(1, rowsOf(AuditAction.AUTH_PASSWORD_RESET_REQUESTED).size());
        List<ActionLog> rows = rowsOf(AuditAction.AUTH_PASSWORD_RESET);
        assertEquals(1, rows.size());
        ActionLog row = rows.get(0);
        assertEquals(PASSWORD_RESET, row.getAction());
        assertEquals(EMAIL, row.getUserEmail());
        assertNotNull(row.getTimestamp());
        assertContainsNone(row, List.of(otp, OLD_PASSWORD, NEW_PASSWORD, aliceInDatabase().getPassword()));
        assertEquals(1, aliceInDatabase().getCredentialsVersion());
    }

    @Test
    void whenTheResetAuditRowCannotBeWritten_theResetIsRolledBackWhole() {
        String otp = mailedOtp();
        doThrow(new IllegalStateException("action_logs unavailable")).when(actionLogRepository).save(any(ActionLog.class));

        assertThrows(AuditLogWriteException.class, () -> authService.verifyOtpAndResetPassword(EMAIL, otp, NEW_PASSWORD));

        User alice = aliceInDatabase();
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, alice.getPassword()));
        assertEquals(0, alice.getCredentialsVersion(), "the other devices keep their sessions after a reset that failed");
        assertEquals(List.of(), rowsOf(AuditAction.AUTH_PASSWORD_RESET));
    }
}
