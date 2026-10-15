package vn.edu.huit.finance.core.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.CredentialsChangeConflictException;
import vn.edu.huit.finance.core.Exception.InvalidOtpException;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditEvent;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for the OTP reset-password flow: what reaches the database, how long an OTP lives, how many wrong
 * guesses it survives, and that a successful reset ends every session of the account (credentials version + 1).
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceResetPasswordTest {

    private static final String EMAIL = "alice@example.com";
    private static final String OLD_PASSWORD = "OldPass123";
    private static final String NEW_PASSWORD = "NewPass456";
    private static final String OTHER_PASSWORD = "OtherPass789";
    // Never a valid OTP (they are 6 digits), so a wrong guess cannot collide with the random one.
    private static final String WRONG_OTP = "wrong-otp";

    // The requirements under test, spelled out here instead of read from the service.
    private static final Duration OTP_VALIDITY = Duration.ofMinutes(5);
    private static final int MAX_FAILED_ATTEMPTS = 3;
    private static final int PARALLEL_ROUNDS = 25;
    private static final int OTP_BOUND = 1_000_000;
    private static final String OTP_EXPIRED_MESSAGE =
            "Mã OTP không tồn tại hoặc đã hết hạn. Vui lòng yêu cầu gửi lại mã mới.";
    private static final String TWO_ATTEMPTS_LEFT_MESSAGE = "Mã OTP không chính xác. Bạn còn 2 lần thử.";
    private static final String ONE_ATTEMPT_LEFT_MESSAGE = "Mã OTP không chính xác. Bạn còn 1 lần thử.";
    private static final String ATTEMPTS_EXHAUSTED_MESSAGE =
            "Bạn đã nhập sai OTP quá số lần cho phép. Vui lòng yêu cầu gửi lại mã mới.";

    private static final Instant ISSUED_AT = Instant.parse("2026-01-01T10:00:00Z");
    // "$2a$" + 2-digit cost + "$" + 53 characters of salt and hash = a 60-character BCrypt string.
    private static final Pattern BCRYPT_HASH = Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");
    private static final Pattern OTP_IN_MAIL = Pattern.compile("\\d{6}");

    @Mock private UserRepository userRepository;
    @Mock private JavaMailSender mailSender;
    @Mock private EntityManager entityManager;
    @Mock private AuditLogService auditLogService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private AuthService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AuthService();
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "mailSender", mailSender);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(service, "eventPublisher", eventPublisher);
        timeIs(ISSUED_AT);

        user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        user.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        // The row lock of a reset hands the user back, and the guarded UPDATE finds the version the lock read; lenient,
        // because most tests are refused before the database.
        lenient().when(entityManager.find(User.class, 1L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(user);
        lenient().when(userRepository.updatePasswordAndEndSessions(eq(1L), anyString(), eq(0))).thenReturn(1);
    }

    private void timeIs(Instant now) {
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(now, ZoneOffset.UTC));
    }

    /** Makes the service draw these numbers in order, so a test knows (and can tell apart) its OTPs. */
    private void drawing(int... draws) {
        AtomicInteger next = new AtomicInteger();
        ReflectionTestUtils.setField(service, "secureRandom", new SecureRandom() {
            @Override
            public int nextInt(int bound) {
                return draws[next.getAndIncrement()];
            }
        });
    }

    /** Requests an OTP like a client would and reads it back from the newest mail the service sent. */
    private String requestOtp() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        service.generateAndSendOtp(EMAIL);

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, atLeastOnce()).send(mail.capture());
        Matcher otp = OTP_IN_MAIL.matcher(mail.getValue().getText());
        assertTrue(otp.find(), "the mail should contain the OTP");
        return otp.group();
    }

    private InvalidOtpException rejectionOf(String otp) {
        return assertThrows(InvalidOtpException.class,
                () -> service.verifyOtpAndResetPassword(EMAIL, otp, NEW_PASSWORD));
    }

    /** Every row published to be written on its own (requests and refusals), in order. */
    private List<AuditEntry> publishedEntries() {
        ArgumentCaptor<SecurityAuditEvent> events = ArgumentCaptor.forClass(SecurityAuditEvent.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(events.capture());
        return events.getAllValues().stream().map(SecurityAuditEvent::entry).toList();
    }

    private static void assertContainsNone(String text, String... secrets) {
        for (String secret : secrets) {
            assertFalse(text.contains(secret), "the audit row must not contain " + secret);
        }
    }

    /** The hash the one guarded UPDATE wrote, together with the new credentials version. */
    private String storedHash() {
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(userRepository).updatePasswordAndEndSessions(eq(1L), hash.capture(), eq(0));
        return hash.getValue();
    }

    @Test
    void verifyOtpAndResetPassword_savesBcryptHash_neverThePlaintext() {
        String otp = requestOtp();

        service.verifyOtpAndResetPassword(EMAIL, otp, NEW_PASSWORD);

        String stored = storedHash();
        assertNotEquals(NEW_PASSWORD, stored, "the plaintext password must never reach the database");
        assertTrue(BCRYPT_HASH.matcher(stored).matches(), "expected a BCrypt hash but was: " + stored);
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, stored));
        assertFalse(passwordEncoder.matches(OLD_PASSWORD, stored));
    }

    @Test
    void verifyOtpAndResetPassword_countsDownTheAttempts_andKeepsPassword_whenOtpIsWrong() {
        requestOtp();
        String hashBefore = user.getPassword();

        assertEquals(TWO_ATTEMPTS_LEFT_MESSAGE, rejectionOf(WRONG_OTP).getMessage());
        assertEquals(ONE_ATTEMPT_LEFT_MESSAGE, rejectionOf(WRONG_OTP).getMessage());

        assertEquals(hashBefore, user.getPassword());
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).updatePasswordAndEndSessions(any(), any(), any());
        assertEquals(0, user.getCredentialsVersion(), "a refused reset must leave every session alone");
    }

    @Test
    void verifyOtpAndResetPassword_endsEverySessionOfTheAccount_byRaisingTheCredentialsVersionOnce() {
        String otp = requestOtp();

        service.verifyOtpAndResetPassword(EMAIL, otp, NEW_PASSWORD);

        verify(userRepository, times(1)).updatePasswordAndEndSessions(eq(1L), anyString(), eq(0));
        assertEquals(1, user.getCredentialsVersion());
        // Never through a save of the entity: neither column is updatable through it.
        verify(userRepository, never()).save(any());
    }

    @Test
    void verifyOtpAndResetPassword_writesThePasswordResetAuditRow_afterTheUpdate_withoutTheOtpOrAnyPassword() {
        String otp = requestOtp();

        service.verifyOtpAndResetPassword(EMAIL, otp, NEW_PASSWORD);

        // Envers never sees the bulk UPDATE, so this row is the audit trail; written once the UPDATE has applied.
        InOrder inOrder = inOrder(userRepository, auditLogService);
        inOrder.verify(userRepository).updatePasswordAndEndSessions(eq(1L), anyString(), eq(0));
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        inOrder.verify(auditLogService).record(entry.capture());
        assertEquals(AuditAction.AUTH_PASSWORD_RESET, entry.getValue().action());
        // The OTP proved the account: the row names it as the actor and as the target.
        assertEquals(AuditActor.of(user), entry.getValue().actor());
        assertEquals(1L, entry.getValue().targetId());
        assertContainsNone(entry.getValue().description(), otp, OLD_PASSWORD, NEW_PASSWORD, storedHash());
    }

    @Test
    void verifyOtpAndResetPassword_writesNoSuccessRow_butRecordsTheRefusalOnItsOwn_whenTheOtpIsRefused() {
        String otp = requestOtp();

        rejectionOf(WRONG_OTP);

        verifyNoInteractions(auditLogService);
        List<AuditEntry> published = publishedEntries();
        assertEquals(List.of(AuditAction.AUTH_PASSWORD_RESET_REQUESTED, AuditAction.AUTH_PASSWORD_RESET_REJECTED),
                published.stream().map(AuditEntry::action).toList());
        AuditEntry refusal = published.get(1);
        // Nobody is signed in: the email is only claimed, no account id is taken from it.
        assertEquals(AuditActor.unauthenticated(EMAIL), refusal.actor());
        assertEquals(TWO_ATTEMPTS_LEFT_MESSAGE, refusal.description().substring(refusal.description().indexOf(": ") + 2));
        assertContainsNone(refusal.description(), otp, WRONG_OTP, NEW_PASSWORD);
    }

    @Test
    void generateAndSendOtp_recordsTheRequest_forTheAccount_withoutTheOtp() {
        String otp = requestOtp();

        List<AuditEntry> published = publishedEntries();
        assertEquals(1, published.size());
        AuditEntry request = published.get(0);
        assertEquals(AuditAction.AUTH_PASSWORD_RESET_REQUESTED, request.action());
        assertEquals(AuditActor.unauthenticated(EMAIL), request.actor());
        assertEquals(1L, request.targetId());
        assertContainsNone(request.description(), otp);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void generateAndSendOtp_recordsARequestForAnUnknownEmail_andMailsNothing() {
        String unknown = "nobody@example.com";
        when(userRepository.findByEmail(unknown)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> service.generateAndSendOtp(unknown));

        verifyNoInteractions(mailSender, auditLogService);
        AuditEntry refusal = publishedEntries().get(0);
        assertEquals(AuditAction.AUTH_PASSWORD_RESET_REJECTED, refusal.action());
        assertEquals(AuditActor.unauthenticated(unknown), refusal.actor());
        assertNull(refusal.targetId());
    }

    @Test
    void verifyOtpAndResetPassword_writesNoAuditRow_whenTheGuardedUpdateMatchesNoRow() {
        String otp = requestOtp();
        when(userRepository.updatePasswordAndEndSessions(eq(1L), anyString(), eq(0))).thenReturn(0);

        assertThrows(CredentialsChangeConflictException.class,
                () -> service.verifyOtpAndResetPassword(EMAIL, otp, NEW_PASSWORD));

        // No audit row claims a reset that did not happen.
        verifyNoInteractions(auditLogService);
        assertEquals(0, user.getCredentialsVersion());
    }

    @Test
    void verifyOtpAndResetPassword_locksTheRowBeforeWritingThePasswordAndTheVersion() {
        String otp = requestOtp();

        service.verifyOtpAndResetPassword(EMAIL, otp, NEW_PASSWORD);

        InOrder inOrder = inOrder(entityManager, userRepository);
        inOrder.verify(entityManager).detach(user);
        inOrder.verify(entityManager).find(User.class, 1L, LockModeType.PESSIMISTIC_WRITE);
        inOrder.verify(userRepository).updatePasswordAndEndSessions(eq(1L), anyString(), eq(0));
    }

    @Test
    void verifyOtpAndResetPassword_stillAcceptsTheOtp_afterTwoWrongAttempts() {
        String otp = requestOtp();
        rejectionOf(WRONG_OTP);
        rejectionOf(WRONG_OTP);

        service.verifyOtpAndResetPassword(EMAIL, otp, NEW_PASSWORD);

        assertTrue(passwordEncoder.matches(NEW_PASSWORD, storedHash()));
    }

    @Test
    void verifyOtpAndResetPassword_dropsTheOtpOnTheThirdWrongAttempt_soEvenTheRightCodeIsRefusedAfterwards() {
        String otp = requestOtp();
        rejectionOf(WRONG_OTP);
        rejectionOf(WRONG_OTP);

        assertEquals(ATTEMPTS_EXHAUSTED_MESSAGE, rejectionOf(WRONG_OTP).getMessage());
        assertEquals(OTP_EXPIRED_MESSAGE, rejectionOf(otp).getMessage());

        verify(userRepository, never()).save(any());
    }

    @Test
    void generateAndSendOtp_issuesAWorkingOtp_afterTheOldOneWasLockedOut() {
        requestOtp();
        rejectionOf(WRONG_OTP);
        rejectionOf(WRONG_OTP);
        rejectionOf(WRONG_OTP);

        String freshOtp = requestOtp();
        service.verifyOtpAndResetPassword(EMAIL, freshOtp, NEW_PASSWORD);

        assertTrue(passwordEncoder.matches(NEW_PASSWORD, storedHash()));
    }

    @Test
    void generateAndSendOtp_replacesThePendingOtp_andRestartsTheAttempts() {
        drawing(111_111, 222_222);
        String first = requestOtp();
        rejectionOf(WRONG_OTP);
        rejectionOf(WRONG_OTP);
        String second = requestOtp();

        // The old code is now just a wrong guess, and the counter is back at zero (2 left, not exhausted).
        assertEquals(TWO_ATTEMPTS_LEFT_MESSAGE, rejectionOf(first).getMessage());
        service.verifyOtpAndResetPassword(EMAIL, second, NEW_PASSWORD);

        assertTrue(passwordEncoder.matches(NEW_PASSWORD, storedHash()));
    }

    @Test
    void verifyOtpAndResetPassword_acceptsTheOtp_untilTheExpiryInstant() {
        String otp = requestOtp();
        timeIs(ISSUED_AT.plus(OTP_VALIDITY));

        service.verifyOtpAndResetPassword(EMAIL, otp, NEW_PASSWORD);

        assertTrue(passwordEncoder.matches(NEW_PASSWORD, storedHash()));
    }

    @Test
    void verifyOtpAndResetPassword_rejectsAnExpiredOtp_evenWithTheRightCode_andDropsIt() {
        String otp = requestOtp();
        timeIs(ISSUED_AT.plus(OTP_VALIDITY).plusSeconds(1));

        assertEquals(OTP_EXPIRED_MESSAGE, rejectionOf(otp).getMessage());

        // It was dropped, not just refused: turning the clock back into the validity window does not revive it.
        timeIs(ISSUED_AT);
        assertEquals(OTP_EXPIRED_MESSAGE, rejectionOf(otp).getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    void verifyOtpAndResetPassword_rejectsAnOtpThatWasNeverRequested() {
        assertEquals(OTP_EXPIRED_MESSAGE, rejectionOf("123456").getMessage());

        verifyNoInteractions(userRepository);
    }

    @Test
    void verifyOtpAndResetPassword_rejectsReplayOfTheSameOtp() {
        String otp = requestOtp();
        service.verifyOtpAndResetPassword(EMAIL, otp, NEW_PASSWORD);
        String storedAfterReset = user.getPassword();

        InvalidOtpException thrown = assertThrows(InvalidOtpException.class,
                () -> service.verifyOtpAndResetPassword(EMAIL, otp, OTHER_PASSWORD));

        assertEquals(OTP_EXPIRED_MESSAGE, thrown.getMessage());
        assertEquals(storedAfterReset, user.getPassword());
        verify(userRepository, times(1)).updatePasswordAndEndSessions(any(), any(), any());
    }

    @Test
    void verifyOtpAndResetPassword_allowsOnlyThreeWrongAttempts_evenWhenTheGuessesArriveInParallel() throws Exception {
        int guesses = 40;
        ExecutorService pool = Executors.newFixedThreadPool(guesses);
        try {
            // Repeated: whether a race shows up is a matter of timing, so one round proves little.
            for (int round = 0; round < PARALLEL_ROUNDS; round++) {
                String otp = requestOtp();
                CountDownLatch startGate = new CountDownLatch(1);
                List<Future<String>> outcomes = new ArrayList<>();
                for (int i = 0; i < guesses; i++) {
                    String wrongOtp = WRONG_OTP + "-" + i;
                    outcomes.add(pool.submit(() -> {
                        startGate.await();
                        return rejectionOf(wrongOtp).getMessage();
                    }));
                }
                startGate.countDown();
                List<String> messages = new ArrayList<>();
                for (Future<String> outcome : outcomes) {
                    messages.add(outcome.get(10, TimeUnit.SECONDS));
                }

                // Exactly three guesses were ever weighed; every later one found nothing pending.
                assertEquals(1, Collections.frequency(messages, TWO_ATTEMPTS_LEFT_MESSAGE), "round " + round);
                assertEquals(1, Collections.frequency(messages, ONE_ATTEMPT_LEFT_MESSAGE), "round " + round);
                assertEquals(1, Collections.frequency(messages, ATTEMPTS_EXHAUSTED_MESSAGE), "round " + round);
                assertEquals(guesses - MAX_FAILED_ATTEMPTS,
                        Collections.frequency(messages, OTP_EXPIRED_MESSAGE), "round " + round);
                assertEquals(OTP_EXPIRED_MESSAGE, rejectionOf(otp).getMessage(), "round " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void otpSource_isSecureRandomByDefault() {
        // java.util.Random is predictable; the injected stand-ins in the tests below would hide a regression to it.
        assertInstanceOf(SecureRandom.class, ReflectionTestUtils.getField(new AuthService(), "secureRandom"));
    }

    @ParameterizedTest
    @CsvSource({"0,000000", "42,000042", "999999,999999"})
    void generateAndSendOtp_mailsASixDigitCode_drawnFromTheWholeSecureRandomRange(int draw, String expectedOtp) {
        AtomicInteger requestedBound = new AtomicInteger();
        ReflectionTestUtils.setField(service, "secureRandom", new SecureRandom() {
            @Override
            public int nextInt(int bound) {
                requestedBound.set(bound);
                return draw;
            }
        });

        assertEquals(expectedOtp, requestOtp());
        assertEquals(OTP_BOUND, requestedBound.get());
    }

    @Test
    void verifyOtpAndResetPassword_runsInsideATransaction() throws NoSuchMethodException {
        // Mockito bypasses Spring's proxy, so the transaction boundary can only be checked declaratively.
        Method method = AuthService.class.getMethod(
                "verifyOtpAndResetPassword", String.class, String.class, String.class);

        assertNotNull(AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class),
                "read-modify-write of the user must be atomic");
    }
}
