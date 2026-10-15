package vn.edu.huit.finance.core.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.AuthTokenResponseDto;
import vn.edu.huit.finance.core.Dto.ChangePasswordRequestDto;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.InvalidPasswordException;
import vn.edu.huit.finance.core.Exception.InvalidTokenException;
import vn.edu.huit.finance.core.Repository.RevokedTokenRepository;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;
import vn.edu.huit.finance.core.Security.CustomUserDetailsService;
import vn.edu.huit.finance.core.Security.CraftedTokens;
import vn.edu.huit.finance.core.Security.JsonAuthenticationEntryPoint;
import vn.edu.huit.finance.core.Security.JwtAuthFilter;
import vn.edu.huit.finance.core.Security.JwtUtil;
import vn.edu.huit.finance.core.Security.Roles;
import vn.edu.huit.finance.core.Security.TokenRejection;
import vn.edu.huit.finance.core.Security.TokenType;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cross-session revocation end to end: a password change or an OTP reset on one device ends every session of the account
 * on every other device, access and refresh tokens alike, through the credentials version. Nothing runs inside a test
 * transaction (that would hide the very commits, rollbacks and row locks under test): the services go through their real
 * @Transactional proxies on H2, tokens are signed by the real JwtUtil, and requests pass the real JwtAuthFilter. The
 * filter itself never answers: a request it leaves anonymous on a protected URL is answered by the entry point that
 * SecurityConfig installs (JsonAuthenticationEntryPoint), which these tests call the way Spring Security would.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AuthService.class, TokenBlacklistService.class, JwtUtil.class, JwtAuthFilter.class,
        CrossSessionTokenRevocationTest.Config.class})
class CrossSessionTokenRevocationTest {

    private static final String EMAIL = "alice@example.com";
    private static final String USERNAME = "alice";
    private static final String OLD_PASSWORD = "OldPass123";
    private static final String NEW_PASSWORD = "NewPass456";
    private static final String OTHER_PASSWORD = "OtherPass789";
    private static final String PROTECTED_URL = "/api/budgets";
    private static final String PUBLIC_URL = "/api/categories";
    private static final String SESSION_OUTDATED_MESSAGE = "Phiên đăng nhập đã hết hiệu lực. Vui lòng đăng nhập lại.";
    private static final String TOKEN_REVOKED_MESSAGE = "Phiên đăng nhập đã bị thu hồi. Vui lòng đăng nhập lại.";
    private static final String TOKEN_EXPIRED_MESSAGE = "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.";
    private static final String TOKEN_INVALID_MESSAGE = "Token xác thực không hợp lệ. Vui lòng đăng nhập lại.";
    private static final String ACCOUNT_LOCKED_MESSAGE = "Tài khoản đã bị khóa.";
    private static final Pattern OTP_IN_MAIL = Pattern.compile("\\d{6}");
    // Well below H2's lock timeout (2 s): a request that has to wait is still waiting when the probe gives up.
    private static final long PROBE_MILLIS = 250;
    private static final long TIMEOUT_SECONDS = 10;

    @TestConfiguration
    static class Config {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }

        // The entry point writes its JSON with it; the JPA slice has no web auto-configuration that would provide one.
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired private AuthService authService;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private JwtAuthFilter jwtAuthFilter;
    @Autowired private UserRepository userRepository;
    @Autowired private RevokedTokenRepository revokedTokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    // Real behaviour; a spy only so that the concurrency tests can hold a refresh right after its revocation.
    @SpyBean private TokenBlacklistService tokenBlacklistService;
    // Real behaviour; a spy only so that one test can make the filter work on a copy of the user that goes stale.
    @SpyBean private CustomUserDetailsService userDetailsService;
    @MockBean private CurrentUserProvider currentUserProvider;
    @MockBean private AuditLogService auditLogService;
    @MockBean private JavaMailSender mailSender;

    private Long aliceId;

    @BeforeEach
    void setUp() {
        User alice = new User();
        alice.setUsername(USERNAME);
        alice.setEmail(EMAIL);
        alice.setPhone("0900000001");
        alice.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        aliceId = userRepository.saveAndFlush(alice).getId();
        // Every call answers with a fresh read, inside whatever transaction the service has opened by then.
        when(currentUserProvider.getCurrentUser()).thenAnswer(invocation -> userRepository.findById(aliceId).orElseThrow());
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        revokedTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    // --------------------------------------------------------------------------------------------------------- helpers

    /** The pair a device got when it signed in, before the change under test: issued for the version the account has now. */
    private AuthTokenResponseDto signedInDevice() {
        int version = versionInDatabase();
        return AuthTokenResponseDto.builder()
                .token(jwtUtil.generateToken(EMAIL, version))
                .refreshToken(jwtUtil.generateRefreshToken(EMAIL, version))
                .build();
    }

    private int versionInDatabase() {
        return userRepository.findById(aliceId).orElseThrow().getCredentialsVersion();
    }

    private String passwordInDatabase() {
        return userRepository.findById(aliceId).orElseThrow().getPassword();
    }

    private int versionIn(String token, TokenType type) {
        return jwtUtil.parse(token, type).credentialsVersion();
    }

    private static ChangePasswordRequestDto request(String oldPassword, String newPassword) {
        ChangePasswordRequestDto dto = new ChangePasswordRequestDto();
        dto.setOldPassword(oldPassword);
        dto.setNewPassword(newPassword);
        return dto;
    }

    private void resetPasswordWithTheMailedOtp(String newPassword) {
        authService.generateAndSendOtp(EMAIL);
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        Matcher otp = OTP_IN_MAIL.matcher(mail.getValue().getText());
        assertTrue(otp.find(), "the mail should contain the OTP");

        authService.verifyOtpAndResetPassword(EMAIL, otp.group(), newPassword);
    }

    private record FilterOutcome(boolean reachedTheEndpoint, String caller, MockHttpServletResponse response) {
    }

    private static MockHttpServletRequest requestTo(String url, String accessToken) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", url);
        request.setServletPath(url);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        return request;
    }

    /**
     * A request to a protected URL with this bearer token, through the real JwtAuthFilter. A request the filter leaves
     * without a caller is answered as Spring Security would answer it on a protected URL: by the entry point.
     */
    private FilterOutcome protectedRequestWith(String accessToken) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = requestTo(PROTECTED_URL, accessToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        // Records the request only if the filter passes it on.
        MockFilterChain chain = new MockFilterChain();

        jwtAuthFilter.doFilter(request, response, chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.clearContext();
        assertNotNull(chain.getRequest(), "the filter never answers a request itself");
        if (authentication == null) {
            new JsonAuthenticationEntryPoint(objectMapper)
                    .commence(request, response, new InsufficientAuthenticationException("anonymous request"));
            return new FilterOutcome(false, null, response);
        }
        return new FilterOutcome(true, authentication.getName(), response);
    }

    /**
     * A request to a public URL with this bearer token: the filter must pass it on anonymously, answer nothing and
     * record why it did not take the token. Returns that reason.
     */
    private TokenRejection assertServedAnonymouslyOnAPublicUrl(String accessToken) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = requestTo(PUBLIC_URL, accessToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        jwtAuthFilter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "the public endpoint must be reached");
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "nobody may be authenticated");
        assertEquals(200, response.getStatus(), "the filter must not answer");
        assertEquals("", response.getContentAsString());
        return (TokenRejection) request.getAttribute(TokenRejection.REQUEST_ATTRIBUTE);
    }

    private void assertGetsThrough(String accessToken) throws Exception {
        FilterOutcome outcome = protectedRequestWith(accessToken);

        assertTrue(outcome.reachedTheEndpoint(), "the request should have reached the endpoint");
        assertEquals(USERNAME, outcome.caller());
    }

    /** Refused with a 401 in the JSON ApiResponse envelope, before the endpoint; returns the message. */
    private String assertRefusedWith401(String accessToken) throws Exception {
        return assertRefusedWith(401, accessToken);
    }

    private String assertRefusedWith(int status, String accessToken) throws Exception {
        FilterOutcome outcome = protectedRequestWith(accessToken);

        assertFalse(outcome.reachedTheEndpoint(), "the request must not reach the endpoint");
        assertNull(outcome.caller(), "nobody may be authenticated");
        MockHttpServletResponse response = outcome.response();
        assertEquals(status, response.getStatus());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(response.getContentType())),
                "expected JSON but was " + response.getContentType());
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals(status, body.get("code").asInt());
        // Left unset, like every error of the ApiResponse envelope (Jackson writes it as null).
        assertTrue(body.path("data").isMissingNode() || body.get("data").isNull(), "an error carries no data");
        return body.get("message").asText();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------------------- change password

    @Test
    void changePassword_handsBackAPairOfTheNewVersion_andItsAccessTokenGetsThroughToAProtectedEndpoint() throws Exception {
        AuthTokenResponseDto phone = signedInDevice();

        AuthTokenResponseDto renewed = authService.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), phone.getToken());

        assertTrue(passwordEncoder.matches(NEW_PASSWORD, passwordInDatabase()));
        assertEquals(1, versionInDatabase());
        assertEquals(1, versionIn(renewed.getToken(), TokenType.ACCESS));
        assertEquals(1, versionIn(renewed.getRefreshToken(), TokenType.REFRESH));
        assertGetsThrough(renewed.getToken());
    }

    @Test
    void changePassword_endsTheSessionsOfEveryDevice_accessAndRefreshTokensAlike() throws Exception {
        AuthTokenResponseDto phone = signedInDevice();
        AuthTokenResponseDto tablet = signedInDevice();

        authService.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), phone.getToken());

        // The tablet took no part in the change: only the version tells its tokens apart from valid ones.
        assertEquals(SESSION_OUTDATED_MESSAGE, assertRefusedWith401(tablet.getToken()));
        assertThrows(InvalidTokenException.class, () -> authService.refresh(tablet.getRefreshToken()));
        assertRefusedWith401(phone.getToken());
        assertThrows(InvalidTokenException.class, () -> authService.refresh(phone.getRefreshToken()));
    }

    // ---------------------------------------------------------------------------------------------- reset password (OTP)

    @Test
    void resetPasswordWithAnOtp_endsEverySessionOfTheAccount_andHandsOutNoToken() throws Exception {
        AuthTokenResponseDto phone = signedInDevice();
        AuthTokenResponseDto tablet = signedInDevice();

        resetPasswordWithTheMailedOtp(NEW_PASSWORD);

        assertTrue(passwordEncoder.matches(NEW_PASSWORD, passwordInDatabase()));
        assertEquals(1, versionInDatabase());
        for (AuthTokenResponseDto device : List.of(phone, tablet)) {
            assertEquals(SESSION_OUTDATED_MESSAGE, assertRefusedWith401(device.getToken()));
            assertThrows(InvalidTokenException.class, () -> authService.refresh(device.getRefreshToken()));
        }
        // The next session starts with a sign-in, whose tokens carry the new version.
        assertGetsThrough(jwtUtil.generateToken(EMAIL, versionInDatabase()));
    }

    // --------------------------------------------------------------------- tokens that are not usable access tokens

    @Test
    void aTokenIssuedBeforeCredentialsVersionsAndTypesExisted_isRefusedByTheFilterAndByRefresh() throws Exception {
        String legacy = CraftedTokens.legacy(EMAIL);

        assertEquals(TOKEN_INVALID_MESSAGE, assertRefusedWith401(legacy));
        assertThrows(InvalidTokenException.class, () -> authService.refresh(legacy));
        assertEquals(0, revokedTokenRepository.count(), "a refused refresh revokes nothing");
    }

    @Test
    void anExpiredAccessToken_getsA401_notThe403OfAnAnonymousRequest() throws Exception {
        String expired = CraftedTokens.expired(EMAIL, versionInDatabase(), TokenType.ACCESS);

        // 401 is what makes the app trade its refresh token for a new pair.
        assertEquals(TOKEN_EXPIRED_MESSAGE, assertRefusedWith401(expired));
    }

    @Test
    void anAccessTokenSignedWithAnotherKey_getsA401() throws Exception {
        assertEquals(TOKEN_INVALID_MESSAGE,
                assertRefusedWith401(CraftedTokens.signedWithAnotherKey(EMAIL, versionInDatabase(), TokenType.ACCESS)));
    }

    @Test
    void anAccessTokenWithoutATypeOrWithoutACredentialsVersion_getsA401() throws Exception {
        assertEquals(TOKEN_INVALID_MESSAGE, assertRefusedWith401(CraftedTokens.withoutType(EMAIL, versionInDatabase())));
        assertEquals(TOKEN_INVALID_MESSAGE,
                assertRefusedWith401(CraftedTokens.withoutCredentialsVersion(EMAIL, TokenType.ACCESS)));
    }

    @Test
    void aRefreshTokenSentToAProtectedEndpoint_getsA401_andStaysUsableForARefresh() throws Exception {
        AuthTokenResponseDto phone = signedInDevice();

        assertEquals(TOKEN_INVALID_MESSAGE, assertRefusedWith401(phone.getRefreshToken()));
        assertNotNull(authService.refresh(phone.getRefreshToken()));
    }

    @Test
    void anAccessTokenTradedInAtRefresh_isRefused_revokesNothing_andStaysUsableAsAnAccessToken() throws Exception {
        AuthTokenResponseDto phone = signedInDevice();

        assertThrows(InvalidTokenException.class, () -> authService.refresh(phone.getToken()));

        assertEquals(0, revokedTokenRepository.count());
        assertGetsThrough(phone.getToken());
    }

    @Test
    void anExpiredRefreshToken_orOneWithoutAType_isRefusedByRefresh_andRevokesNothing() {
        int version = versionInDatabase();

        assertThrows(InvalidTokenException.class,
                () -> authService.refresh(CraftedTokens.expired(EMAIL, version, TokenType.REFRESH)));
        assertThrows(InvalidTokenException.class, () -> authService.refresh(CraftedTokens.withoutType(EMAIL, version)));
        assertThrows(InvalidTokenException.class,
                () -> authService.refresh(CraftedTokens.withoutCredentialsVersion(EMAIL, TokenType.REFRESH)));
        assertEquals(0, revokedTokenRepository.count());
    }

    // ---------------------------------------------------------------------------------------------- locked accounts

    @Test
    void aDeactivatedAccount_cannotRefresh_andItsAccessTokenGetsAJson403() throws Exception {
        AuthTokenResponseDto phone = signedInDevice();
        User alice = userRepository.findById(aliceId).orElseThrow();
        alice.setActive(false);
        userRepository.saveAndFlush(alice);

        assertThrows(InvalidTokenException.class, () -> authService.refresh(phone.getRefreshToken()));
        assertEquals(0, revokedTokenRepository.count(), "a refused refresh revokes nothing");
        assertEquals(ACCOUNT_LOCKED_MESSAGE, assertRefusedWith(403, phone.getToken()));
    }

    @Test
    void anAccountWithTheLockedRole_cannotRefresh() {
        AuthTokenResponseDto phone = signedInDevice();
        User alice = userRepository.findById(aliceId).orElseThrow();
        alice.setRole(Roles.LOCKED);
        userRepository.saveAndFlush(alice);

        assertThrows(InvalidTokenException.class, () -> authService.refresh(phone.getRefreshToken()));
        assertEquals(0, revokedTokenRepository.count());
    }

    // ------------------------------------------------------------------------------------------------------- refresh

    @Test
    void refresh_issuesAPairOfTheLatestVersion_andUsesTheRefreshTokenItWasGivenUp() throws Exception {
        AuthTokenResponseDto phone = signedInDevice();
        AuthTokenResponseDto afterFirstChange = authService.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), phone.getToken());
        AuthTokenResponseDto afterSecondChange =
                authService.changePassword(request(NEW_PASSWORD, OTHER_PASSWORD), afterFirstChange.getToken());

        AuthTokenResponseDto refreshed = authService.refresh(afterSecondChange.getRefreshToken());

        assertEquals(2, versionInDatabase());
        assertEquals(2, versionIn(refreshed.getToken(), TokenType.ACCESS));
        assertEquals(2, versionIn(refreshed.getRefreshToken(), TokenType.REFRESH));
        assertGetsThrough(refreshed.getToken());
        // Rotation: the refresh token just traded in is on the blacklist, so it cannot mint a second pair.
        assertTrue(tokenBlacklistService.isRevoked(afterSecondChange.getRefreshToken()));
        assertThrows(InvalidTokenException.class, () -> authService.refresh(afterSecondChange.getRefreshToken()));
        // The refresh token handed out by the first change belongs to a version that is gone.
        assertThrows(InvalidTokenException.class, () -> authService.refresh(afterFirstChange.getRefreshToken()));
        assertNotNull(authService.refresh(refreshed.getRefreshToken()));
    }

    // -------------------------------------------------------------------------------- other writes leave the version alone

    @Test
    void savingOtherFieldsOfTheUser_leavesTheCredentialsVersionAndTheSessionsAlone() throws Exception {
        AuthTokenResponseDto phone = signedInDevice();

        User alice = userRepository.findById(aliceId).orElseThrow();
        alice.setWalletBalance(new BigDecimal("250000.00"));
        alice.setPhone("0900000002");
        alice.setLastActionTime(LocalDateTime.now());
        userRepository.saveAndFlush(alice);

        assertEquals(0, versionInDatabase());
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, passwordInDatabase()));
        assertGetsThrough(phone.getToken());
    }

    @Test
    void aCopyOfTheUserReadBeforeAReset_andSavedAfterIt_cannotPutTheOldPasswordOrVersionBack() throws Exception {
        AuthTokenResponseDto tablet = signedInDevice();
        User readBeforeTheReset = userRepository.findById(aliceId).orElseThrow();

        resetPasswordWithTheMailedOtp(NEW_PASSWORD);
        // Like the wallet write of a request that started before the reset, or the last-action save of JwtAuthFilter.
        readBeforeTheReset.setWalletBalance(new BigDecimal("250000.00"));
        userRepository.saveAndFlush(readBeforeTheReset);

        assertEquals(1, versionInDatabase(), "credentials_version is not updatable through the entity");
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, passwordInDatabase()), "password is not updatable through the entity");
        assertEquals(new BigDecimal("250000.00"), userRepository.findById(aliceId).orElseThrow().getWalletBalance(),
                "the save itself went through");
        assertEquals(SESSION_OUTDATED_MESSAGE, assertRefusedWith401(tablet.getToken()));
        assertThrows(InvalidTokenException.class, () -> authService.refresh(tablet.getRefreshToken()));
    }

    @Test
    void aCopyOfTheUserReadBeforeAPasswordChange_whoseOldHashIsSavedAfterIt_cannotBringTheOldPasswordBack() throws Exception {
        AuthTokenResponseDto phone = signedInDevice();
        AuthTokenResponseDto tablet = signedInDevice();
        User readBeforeTheChange = userRepository.findById(aliceId).orElseThrow();

        AuthTokenResponseDto renewed = authService.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), phone.getToken());
        // The stale copy still holds the old hash and version; even set explicitly, a save must not write them.
        readBeforeTheChange.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        readBeforeTheChange.setCredentialsVersion(0);
        readBeforeTheChange.setLastActionTime(LocalDateTime.now());
        userRepository.saveAndFlush(readBeforeTheChange);

        assertTrue(passwordEncoder.matches(NEW_PASSWORD, passwordInDatabase()));
        assertFalse(passwordEncoder.matches(OLD_PASSWORD, passwordInDatabase()));
        assertEquals(1, versionInDatabase());
        assertGetsThrough(renewed.getToken());
        // The token that made the change is on the blacklist, which is checked first; the other device's ends by version.
        assertEquals(TOKEN_REVOKED_MESSAGE, assertRefusedWith401(phone.getToken()));
        assertEquals(SESSION_OUTDATED_MESSAGE, assertRefusedWith401(tablet.getToken()));
    }

    // ------------------------------------------------------------------------------------ a public URL stays usable

    @Test
    void onAPublicUrl_anExpiredABadlySignedOrAnOutdatedToken_leavesTheRequestAnonymous() throws Exception {
        int version = versionInDatabase();
        String expired = CraftedTokens.expired(EMAIL, version, TokenType.ACCESS);
        String badlySigned = CraftedTokens.signedWithAnotherKey(EMAIL, version, TokenType.ACCESS);
        AuthTokenResponseDto tablet = signedInDevice();
        authService.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), signedInDevice().getToken());

        assertEquals(TokenRejection.EXPIRED, assertServedAnonymouslyOnAPublicUrl(expired));
        assertEquals(TokenRejection.INVALID, assertServedAnonymouslyOnAPublicUrl(badlySigned));
        assertEquals(TokenRejection.OUTDATED, assertServedAnonymouslyOnAPublicUrl(tablet.getToken()));
        assertEquals(TokenRejection.INVALID, assertServedAnonymouslyOnAPublicUrl(tablet.getRefreshToken()));
        assertEquals(TokenRejection.INVALID, assertServedAnonymouslyOnAPublicUrl(CraftedTokens.legacy(EMAIL)));
    }

    @Test
    void onAPublicUrl_aRevokedTokenLeavesTheRequestAnonymousToo() throws Exception {
        AuthTokenResponseDto phone = signedInDevice();
        tokenBlacklistService.revoke(phone.getToken());

        assertEquals(TokenRejection.REVOKED, assertServedAnonymouslyOnAPublicUrl(phone.getToken()));
    }

    // ---------------------------------------------------------------------------- last action time, never a save

    @Test
    void theFilterRecordsTheLastActionTime_andNothingElseOfTheUser() throws Exception {
        AuthTokenResponseDto phone = signedInDevice();
        String hashBefore = passwordInDatabase();
        assertNull(userRepository.findById(aliceId).orElseThrow().getLastActionTime());

        assertGetsThrough(phone.getToken());

        User alice = userRepository.findById(aliceId).orElseThrow();
        assertNotNull(alice.getLastActionTime());
        assertEquals(0, BigDecimal.ZERO.compareTo(alice.getWalletBalance()));
        assertEquals(hashBefore, alice.getPassword());
        assertEquals(0, alice.getCredentialsVersion());
    }

    @Test
    void theLastActionTimeIsWrittenAtMostOnceEveryFiveMinutes() throws Exception {
        AuthTokenResponseDto phone = signedInDevice();
        LocalDateTime aMinuteAgo = LocalDateTime.now().minusMinutes(1).truncatedTo(ChronoUnit.SECONDS);
        userRepository.updateLastActionTime(aliceId, aMinuteAgo);

        assertGetsThrough(phone.getToken());

        assertEquals(aMinuteAgo, userRepository.findById(aliceId).orElseThrow().getLastActionTime());
    }

    @Test
    void aCopyOfTheUserThatGoesStaleInsideTheFilter_cannotWriteBackTheBalanceOrProfileCommittedMeanwhile() throws Exception {
        AuthTokenResponseDto phone = signedInDevice();
        String hashBefore = passwordInDatabase();
        // The filter reads Alice, then another request commits a wallet top-up and a new phone number before the filter
        // records the last action time: the filter's copy still has the old balance and the old phone.
        doAnswer(invocation -> {
            User readByTheFilter = (User) invocation.callRealMethod();
            User meanwhile = userRepository.findById(aliceId).orElseThrow();
            meanwhile.setWalletBalance(new BigDecimal("500000.00"));
            meanwhile.setPhone("0900000009");
            userRepository.saveAndFlush(meanwhile);
            return readByTheFilter;
        }).when(userDetailsService).loadApplicationUser(EMAIL);

        assertGetsThrough(phone.getToken());

        User alice = userRepository.findById(aliceId).orElseThrow();
        // A save of the filter's copy would have put 0 and the old phone back.
        assertEquals(0, new BigDecimal("500000.00").compareTo(alice.getWalletBalance()));
        assertEquals("0900000009", alice.getPhone());
        assertEquals(hashBefore, alice.getPassword());
        assertEquals(0, alice.getCredentialsVersion());
        assertNotNull(alice.getLastActionTime());
    }

    // ------------------------------------------------------------------------------------------------------ concurrency

    @Test
    void twoPasswordChangesAtOnce_queueUpOnTheRowLock_andTheSecondIsCheckedAgainstThePasswordTheFirstSet() throws Exception {
        AuthTokenResponseDto phone = signedInDevice();
        AuthTokenResponseDto tablet = signedInDevice();
        CountDownLatch firstIsInside = new CountDownLatch(1);
        CountDownLatch firstMayCommit = new CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        // The first change is held inside its transaction: row locked, password and version written, nothing committed.
        doAnswer(invocation -> {
            if (firstCall.compareAndSet(true, false)) {
                firstIsInside.countDown();
                awaitQuietly(firstMayCommit);
            }
            return null;
        }).when(auditLogService).record(any(AuditEntry.class));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AuthTokenResponseDto winner;
        try {
            Future<AuthTokenResponseDto> first = pool.submit(
                    () -> authService.changePassword(request(OLD_PASSWORD, NEW_PASSWORD), phone.getToken()));
            assertTrue(firstIsInside.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            // The tablet changes it too, knowing only the old password, and reads the user before the first commits.
            Future<AuthTokenResponseDto> second = pool.submit(
                    () -> authService.changePassword(request(OLD_PASSWORD, OTHER_PASSWORD), tablet.getToken()));
            assertThrows(TimeoutException.class, () -> second.get(PROBE_MILLIS, TimeUnit.MILLISECONDS),
                    "the second change has to wait for the row lock of the first");

            firstMayCommit.countDown();
            winner = first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(InvalidPasswordException.class, refused.getCause());
        } finally {
            firstMayCommit.countDown();
            pool.shutdownNow();
        }

        // Checked against the copy it had read, the second would have passed and overwritten the first password, and the
        // version in the winner's tokens would no longer be the database's.
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, passwordInDatabase()));
        assertEquals(1, versionInDatabase());
        assertGetsThrough(winner.getToken());
        assertEquals(SESSION_OUTDATED_MESSAGE, assertRefusedWith401(tablet.getToken()));
    }

    @Test
    void twoRefreshesWithTheSameToken_theSecondWaitsForTheFirstToCommitItsRevocation_andIsThenRefused() throws Exception {
        String reused = signedInDevice().getRefreshToken();
        CountDownLatch firstHasRevoked = new CountDownLatch(1);
        CountDownLatch firstMayCommit = new CountDownLatch(1);
        holdTheFirstRevocation(firstHasRevoked, firstMayCommit, false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AuthTokenResponseDto firstPair;
        try {
            Future<AuthTokenResponseDto> first = pool.submit(() -> authService.refresh(reused));
            assertTrue(firstHasRevoked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            // The same refresh token again (a retry, or a copy in other hands) while the first has not committed.
            Future<AuthTokenResponseDto> second = pool.submit(() -> authService.refresh(reused));
            assertThrows(TimeoutException.class, () -> second.get(PROBE_MILLIS, TimeUnit.MILLISECONDS),
                    "the second refresh has to wait for the row lock, not read the blacklist before the first commits");

            firstMayCommit.countDown();
            firstPair = first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(InvalidTokenException.class, refused.getCause());
        } finally {
            firstMayCommit.countDown();
            pool.shutdownNow();
        }

        // One pair came out of the token, not two.
        assertEquals(1, revokedTokenRepository.count());
        assertTrue(tokenBlacklistService.isRevoked(reused));
        assertGetsThrough(firstPair.getToken());
        assertNotNull(authService.refresh(firstPair.getRefreshToken()), "the pair of the first refresh goes on working");
    }

    @Test
    void whenTheFirstRefreshRollsBackAfterItsRevocation_theSecondWithTheSameTokenGoesAhead() throws Exception {
        String reused = signedInDevice().getRefreshToken();
        CountDownLatch firstHasRevoked = new CountDownLatch(1);
        CountDownLatch firstMayGoOn = new CountDownLatch(1);
        holdTheFirstRevocation(firstHasRevoked, firstMayGoOn, true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AuthTokenResponseDto secondPair;
        try {
            Future<AuthTokenResponseDto> first = pool.submit(() -> authService.refresh(reused));
            assertTrue(firstHasRevoked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            Future<AuthTokenResponseDto> second = pool.submit(() -> authService.refresh(reused));
            assertThrows(TimeoutException.class, () -> second.get(PROBE_MILLIS, TimeUnit.MILLISECONDS),
                    "the second refresh has to wait for the row lock of the first");

            firstMayGoOn.countDown();
            ExecutionException failed = assertThrows(ExecutionException.class,
                    () -> first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failed.getCause());
            secondPair = second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            firstMayGoOn.countDown();
            pool.shutdownNow();
        }

        // The first revocation went with the rest of its transaction, so the token was still unused for the second.
        assertEquals(1, revokedTokenRepository.count(), "only the revocation of the second refresh is left");
        assertEquals(0, versionIn(secondPair.getToken(), TokenType.ACCESS));
        assertGetsThrough(secondPair.getToken());
        assertThrows(InvalidTokenException.class, () -> authService.refresh(reused));
    }

    /**
     * Holds the first refresh inside its transaction right after it revoked the old token (row locked, revocation written,
     * nothing committed). With {@code thenFail} it fails afterwards, so its whole transaction rolls back.
     */
    private void holdTheFirstRevocation(CountDownLatch hasRevoked, CountDownLatch mayGoOn, boolean thenFail) {
        AtomicBoolean firstCall = new AtomicBoolean(true);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (firstCall.compareAndSet(true, false)) {
                hasRevoked.countDown();
                awaitQuietly(mayGoOn);
                if (thenFail) {
                    throw new IllegalStateException("the first refresh fails after its revocation");
                }
            }
            return null;
        }).when(tokenBlacklistService).revoke(anyString());
    }
}
