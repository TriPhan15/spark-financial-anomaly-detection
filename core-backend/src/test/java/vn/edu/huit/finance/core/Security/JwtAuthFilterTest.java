package vn.edu.huit.finance.core.Security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.huit.finance.core.Config.SecurityConfig;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.GlobalExceptionHandler;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditEvent;
import vn.edu.huit.finance.core.Service.LoginAttemptService;
import vn.edu.huit.finance.core.Service.TokenBlacklistService;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JwtAuthFilter behind the real filter chain of SecurityConfig, with a probe controller on one public URL (permitAll) and
 * one protected URL. The filter never answers: an unusable bearer token leaves the request anonymous, so the public URL
 * still serves it and the protected one is refused by the JSON entry point (401, or 403 for a locked account). A usable
 * one authenticates the caller and records the last action time with one targeted UPDATE, never a save of the user.
 */
@WebMvcTest(JwtAuthFilterTest.ProbeController.class)
@ContextConfiguration(classes = {
        JwtAuthFilterTest.ProbeController.class, GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthFilter.class})
@RecordApplicationEvents
class JwtAuthFilterTest {

    private static final String PUBLIC_URL = "/api/categories";
    private static final String PROTECTED_URL = "/api/budgets";
    private static final String ANONYMOUS = "anonymous";
    private static final long ALICE_ID = 1L;
    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    private static final String VALID_TOKEN = "valid.jwt.token";
    private static final String EXPIRED_TOKEN = "expired.jwt.token";
    private static final String BADLY_SIGNED_TOKEN = "badly-signed.jwt.token";
    private static final String OUTDATED_TOKEN = "outdated.jwt.token";
    private static final String REVOKED_TOKEN = "revoked.jwt.token";
    private static final String REFRESH_TOKEN = "refresh.jwt.token";
    private static final String LOCKED_ACCOUNT_TOKEN = "locked-account.jwt.token";

    /** Answers with the name of the authenticated caller, or "anonymous". */
    @RestController
    static class ProbeController {
        @GetMapping({PUBLIC_URL, PROTECTED_URL})
        String caller(Principal principal) {
            return principal == null ? ANONYMOUS : principal.getName();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationEvents events;

    @MockBean private JwtUtil jwtUtil;
    @MockBean private CustomUserDetailsService userDetailsService;
    @MockBean private UserRepository userRepository;
    @MockBean private LoginAttemptService loginAttemptService;
    @MockBean private TokenBlacklistService tokenBlacklistService;

    private User alice;

    @BeforeEach
    void setUp() {
        alice = user(ALICE_ID, ALICE, true);
        User bob = user(2L, BOB, false);
        stubUser(alice);
        stubUser(bob);

        when(jwtUtil.parse(VALID_TOKEN, TokenType.ACCESS)).thenReturn(new TokenClaims(ALICE, 0));
        when(jwtUtil.parse(EXPIRED_TOKEN, TokenType.ACCESS)).thenThrow(new ExpiredJwtException(null, null, "expired"));
        when(jwtUtil.parse(BADLY_SIGNED_TOKEN, TokenType.ACCESS)).thenThrow(new SignatureException("no match"));
        when(jwtUtil.parse(REFRESH_TOKEN, TokenType.ACCESS)).thenThrow(new UnsupportedJwtException("not ACCESS"));
        // Carries credentials version 1 while Alice's account is at 0: not a token of her current password.
        when(jwtUtil.parse(OUTDATED_TOKEN, TokenType.ACCESS)).thenReturn(new TokenClaims(ALICE, 1));
        when(jwtUtil.parse(REVOKED_TOKEN, TokenType.ACCESS)).thenReturn(new TokenClaims(ALICE, 0));
        when(tokenBlacklistService.isRevoked(REVOKED_TOKEN)).thenReturn(true);
        when(jwtUtil.parse(LOCKED_ACCOUNT_TOKEN, TokenType.ACCESS)).thenReturn(new TokenClaims(BOB, 0));
    }

    private static User user(long id, String name, boolean active) {
        User user = new User();
        user.setId(id);
        user.setUsername(name);
        user.setEmail(name + "@example.com");
        user.setRole(Roles.USER);
        user.setActive(active);
        return user;
    }

    private void stubUser(User user) {
        when(userDetailsService.loadApplicationUser(user.getUsername())).thenReturn(user);
        when(userDetailsService.loadUserByUsername(user.getUsername())).thenReturn(
                new org.springframework.security.core.userdetails.User(
                        user.getUsername(), "hash", AuthorityUtils.createAuthorityList(Roles.USER)));
    }

    // MockMvc leaves getServletPath() empty; the filter branches on it, so set it explicitly.
    private static MockHttpServletRequestBuilder to(String url, String token) {
        MockHttpServletRequestBuilder request = get(url).servletPath(url);
        return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    // ---------------------------------------------------------------------------------------------- public endpoint

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {EXPIRED_TOKEN, BADLY_SIGNED_TOKEN, OUTDATED_TOKEN, REVOKED_TOKEN, REFRESH_TOKEN,
            LOCKED_ACCOUNT_TOKEN})
    void aPublicUrl_servesARequestWithAnUnusableToken_anonymously(String token) throws Exception {
        mockMvc.perform(to(PUBLIC_URL, token))
                .andExpect(status().isOk())
                .andExpect(content().string(ANONYMOUS));

        verify(userRepository, never()).updateLastActionTime(anyLong(), any());
    }

    @Test
    void aPublicUrl_stillKnowsTheCallerOfAUsableToken() throws Exception {
        mockMvc.perform(to(PUBLIC_URL, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string(ALICE));
    }

    // ------------------------------------------------------------------------------------------- protected endpoint

    static Stream<Arguments> unusableTokens() {
        return Stream.of(
                Arguments.of(EXPIRED_TOKEN, "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại."),
                Arguments.of(BADLY_SIGNED_TOKEN, "Token xác thực không hợp lệ. Vui lòng đăng nhập lại."),
                Arguments.of(REFRESH_TOKEN, "Token xác thực không hợp lệ. Vui lòng đăng nhập lại."),
                Arguments.of(OUTDATED_TOKEN, "Phiên đăng nhập đã hết hiệu lực. Vui lòng đăng nhập lại."),
                Arguments.of(REVOKED_TOKEN, "Phiên đăng nhập đã bị thu hồi. Vui lòng đăng nhập lại."),
                Arguments.of(null, "Thiếu token xác thực"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableTokens")
    void aProtectedUrl_refusesAnUnusableOrMissingToken_withAJson401FromTheEntryPoint(String token, String message)
            throws Exception {
        mockMvc.perform(to(PROTECTED_URL, token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.data").doesNotExist())
                // Written as the body, not through sendError (which would render the container's error page).
                .andExpect(result -> assertNull(result.getResponse().getErrorMessage()));
    }

    @Test
    void aProtectedUrl_refusesTheTokenOfALockedAccount_withAJson403() throws Exception {
        mockMvc.perform(to(PROTECTED_URL, LOCKED_ACCOUNT_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("Tài khoản đã bị khóa."));
    }

    @Test
    void aProtectedUrl_servesTheCallerOfAUsableToken() throws Exception {
        mockMvc.perform(to(PROTECTED_URL, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string(ALICE));
    }

    // --------------------------------------------------------------------------------------------- audit of refusals

    private List<AuditEntry> recordedRefusals() {
        return events.stream(SecurityAuditEvent.class).map(SecurityAuditEvent::entry).toList();
    }

    static Stream<Arguments> tokensThatMayNoLongerBeUsed() {
        return Stream.of(
                Arguments.of(REVOKED_TOKEN, ALICE, null, "REVOKED"),
                Arguments.of(OUTDATED_TOKEN, ALICE, ALICE_ID, "OUTDATED"),
                Arguments.of(LOCKED_ACCOUNT_TOKEN, BOB, 2L, "ACCOUNT_LOCKED"));
    }

    @ParameterizedTest(name = "{3}")
    @MethodSource("tokensThatMayNoLongerBeUsed")
    void aSignedTokenThatMayNoLongerBeUsedIsRecordedOnce_withTheIdentityItClaimsAndThePath_neverTheToken(
            String token, String subject, Long accountId, String reason) throws Exception {
        mockMvc.perform(to(PROTECTED_URL, token));

        List<AuditEntry> refusals = recordedRefusals();
        assertEquals(1, refusals.size());
        AuditEntry refusal = refusals.get(0);
        assertEquals(AuditAction.AUTH_TOKEN_REJECTED, refusal.action());
        // Refused, so not signed in: the subject is only claimed, the account (once loaded) is the target.
        assertEquals(AuditActor.unauthenticated(subject), refusal.actor());
        assertEquals(accountId, refusal.targetId());
        assertTrue(refusal.description().contains(reason), refusal.description());
        assertTrue(refusal.description().contains("GET " + PROTECTED_URL), refusal.description());
        assertFalse(refusal.description().contains(token));
    }

    @Test
    void aRevokedTokenIsRecordedOnAPublicUrlToo_althoughThatUrlServesTheRequestAnonymously() throws Exception {
        mockMvc.perform(to(PUBLIC_URL, REVOKED_TOKEN)).andExpect(status().isOk());

        assertEquals(List.of(AuditAction.AUTH_TOKEN_REJECTED), recordedRefusals().stream().map(AuditEntry::action).toList());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {EXPIRED_TOKEN, BADLY_SIGNED_TOKEN, REFRESH_TOKEN, VALID_TOKEN})
    void anExpiredGarbageOrUsableTokenIsNotRecorded_routineOrSendableByAnybody(String token) throws Exception {
        mockMvc.perform(to(PROTECTED_URL, token));

        assertEquals(List.of(), recordedRefusals());
    }

    @Test
    void noTokenAtAllIsNotRecorded() throws Exception {
        mockMvc.perform(to(PROTECTED_URL, null)).andExpect(status().isUnauthorized());

        assertEquals(List.of(), recordedRefusals());
    }

    // ---------------------------------------------------------------------------------------------- last action time

    @Test
    void theLastActionTimeIsRecordedWithOneTargetedUpdate_neverWithASaveOfTheUser() throws Exception {
        when(userRepository.updateLastActionTime(eq(ALICE_ID), any(LocalDateTime.class))).thenReturn(1);

        mockMvc.perform(to(PROTECTED_URL, VALID_TOKEN)).andExpect(status().isOk());

        verify(userRepository).updateLastActionTime(eq(ALICE_ID), any(LocalDateTime.class));
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void theLastActionTimeIsNotWrittenAgainWithinFiveMinutes() throws Exception {
        alice.setLastActionTime(LocalDateTime.now().minusMinutes(1));

        mockMvc.perform(to(PROTECTED_URL, VALID_TOKEN)).andExpect(status().isOk());

        verify(userRepository, never()).updateLastActionTime(anyLong(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void anAccountDeletedMeanwhile_doesNotBreakTheRequest_whenItsLastActionTimeMatchesNoRow() throws Exception {
        when(userRepository.updateLastActionTime(eq(ALICE_ID), any(LocalDateTime.class))).thenReturn(0);

        mockMvc.perform(to(PROTECTED_URL, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string(ALICE));
    }
}
