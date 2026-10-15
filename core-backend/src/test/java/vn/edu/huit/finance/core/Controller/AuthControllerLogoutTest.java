package vn.edu.huit.finance.core.Controller;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.edu.huit.finance.core.Config.SecurityConfig;
import vn.edu.huit.finance.core.Dto.AuthTokenResponseDto;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.GlobalExceptionHandler;
import vn.edu.huit.finance.core.Exception.InvalidTokenException;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.AuthPaths;
import vn.edu.huit.finance.core.Security.CustomUserDetailsService;
import vn.edu.huit.finance.core.Security.JwtAuthFilter;
import vn.edu.huit.finance.core.Security.JwtUtil;
import vn.edu.huit.finance.core.Security.TokenClaims;
import vn.edu.huit.finance.core.Security.TokenType;
import vn.edu.huit.finance.core.Service.AuthService;
import vn.edu.huit.finance.core.Service.LoginAttemptService;
import vn.edu.huit.finance.core.Service.SocialAuthService;
import vn.edu.huit.finance.core.Service.TokenBlacklistService;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Token revocation through the real security filter chain + JwtAuthFilter + GlobalExceptionHandler: POST /api/auth/logout,
 * the JSON 401 that every unusable access token gets on every protected endpoint (revoked, expired, badly signed, a
 * refresh token, no type or no credentials version, an older credentials version, an account that is gone), and the
 * envelope of /api/auth/refresh (its checks are in AuthService, see
 * AuthServiceRefreshTest and CrossSessionTokenRevocationTest). Explicit context classes keep the app class
 * (@EnableJpaAuditing) out of the slice.
 */
@WebMvcTest(AuthController.class)
@ContextConfiguration(classes = {
        AuthController.class, GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthFilter.class})
class AuthControllerLogoutTest {

    private static final String LOGOUT_URL = AuthPaths.LOGOUT_URL;
    private static final String CHANGE_PASSWORD_URL = AuthPaths.CHANGE_PASSWORD_URL;
    private static final String REFRESH_URL = AuthPaths.BASE + "/refresh";
    private static final String FORGOT_PASSWORD_URL = AuthPaths.BASE + "/forgot-password";
    private static final String OTHER_PROTECTED_URL = "/api/budgets";
    private static final String TOKEN = "valid.jwt.token";
    private static final String REVOKED_TOKEN = "revoked.jwt.token";
    private static final String BAD_TOKEN = "invalid.jwt.token";
    private static final String REFRESH_TOKEN = "valid.refresh.token";
    // Signed, not expired, not revoked, but issued before the password was last changed.
    private static final String OUTDATED_TOKEN = "outdated.jwt.token";
    // Signed, not expired, not revoked, but from the release before credentials versions: no claim at all.
    private static final String UNVERSIONED_TOKEN = "unversioned.jwt.token";
    // Signed and of the right type, but past its expiry.
    private static final String EXPIRED_TOKEN = "expired.jwt.token";
    private static final String USERNAME = "alice";
    // Not 0 on purpose: a stub that forgot the claim would answer 0 and must not pass by accident.
    private static final int CURRENT_VERSION = 3;
    private static final String CHANGE_PASSWORD_BODY = "{\"oldPassword\":\"OldPass123\",\"newPassword\":\"NewPass456\"}";
    private static final String REVOKED_MESSAGE = "Phiên đăng nhập đã bị thu hồi. Vui lòng đăng nhập lại.";
    private static final String OUTDATED_MESSAGE = "Phiên đăng nhập đã hết hiệu lực. Vui lòng đăng nhập lại.";
    private static final String EXPIRED_MESSAGE = "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.";
    private static final String INVALID_MESSAGE = "Token xác thực không hợp lệ. Vui lòng đăng nhập lại.";
    private static final String REFRESH_REFUSED_MESSAGE = "Refresh token không hợp lệ hoặc đã hết hạn";

    @Autowired private MockMvc mockMvc;

    @MockBean private AuthService authService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private CustomUserDetailsService userDetailsService;
    @MockBean private UserRepository userRepository;
    @MockBean private LoginAttemptService loginAttemptService;
    @MockBean private SocialAuthService socialAuthService;
    @MockBean private TokenBlacklistService tokenBlacklistService;

    @BeforeEach
    void stubJwtForAlice() {
        stubValidToken(TOKEN);
        // Signed and not expired, but on the blacklist.
        stubValidToken(REVOKED_TOKEN);
        when(tokenBlacklistService.isRevoked(REVOKED_TOKEN)).thenReturn(true);
        // Not usable as an access token, each for another reason: the blacklist is never asked about any of them.
        when(jwtUtil.parse(BAD_TOKEN, TokenType.ACCESS)).thenThrow(new SignatureException("signature does not match"));
        when(jwtUtil.parse(EXPIRED_TOKEN, TokenType.ACCESS)).thenThrow(new ExpiredJwtException(null, null, "expired"));
        when(jwtUtil.parse(UNVERSIONED_TOKEN, TokenType.ACCESS)).thenThrow(new UnsupportedJwtException("no type"));
        when(jwtUtil.parse(REFRESH_TOKEN, TokenType.ACCESS)).thenThrow(new UnsupportedJwtException("a refresh token"));
        when(jwtUtil.parse(OUTDATED_TOKEN, TokenType.ACCESS)).thenReturn(new TokenClaims(USERNAME, CURRENT_VERSION - 1));

        User alice = new User();
        alice.setUsername(USERNAME);
        alice.setActive(true);
        alice.setRole("ROLE_USER");
        alice.setLastActionTime(LocalDateTime.now());
        alice.setCredentialsVersion(CURRENT_VERSION);
        when(userDetailsService.loadApplicationUser(USERNAME)).thenReturn(alice);
        when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(
                new org.springframework.security.core.userdetails.User(
                        USERNAME, "hash", AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    private void stubValidToken(String token) {
        when(jwtUtil.parse(token, TokenType.ACCESS)).thenReturn(new TokenClaims(USERNAME, CURRENT_VERSION));
    }

    // MockMvc leaves getServletPath() empty, unlike a real container; JwtAuthFilter branches on it, so set it explicitly.
    private static MockHttpServletRequestBuilder logoutRequest() {
        return post(LOGOUT_URL).servletPath(LOGOUT_URL);
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder refreshRequest(String refreshToken) {
        return withJson(post(REFRESH_URL).servletPath(REFRESH_URL), "{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    // ------------------------------------------------------------------------------------------------------- logout

    @Test
    void logout_returns200_andHandsTheBareAccessTokenOfTheRequestToTheService() throws Exception {
        mockMvc.perform(withToken(logoutRequest(), TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Đăng xuất thành công!"));

        verify(authService).logout(TOKEN, null);
    }

    @Test
    void logout_alsoHandsOverTheRefreshTokenOfTheBody() throws Exception {
        mockMvc.perform(withToken(withJson(logoutRequest(), "{\"refreshToken\":\"" + REFRESH_TOKEN + "\"}"), TOKEN))
                .andExpect(status().isOk());

        verify(authService).logout(TOKEN, REFRESH_TOKEN);
    }

    @Test
    void logout_takesTheCallerFromTheJwt_notFromTheBody() throws Exception {
        AtomicReference<String> callerSeenByService = new AtomicReference<>();
        doAnswer(invocation -> {
            callerSeenByService.set(SecurityContextHolder.getContext().getAuthentication().getName());
            return null;
        }).when(authService).logout(any(), any());

        mockMvc.perform(withToken(withJson(logoutRequest(), "{\"userId\":999,\"refreshToken\":\"" + REFRESH_TOKEN + "\"}"), TOKEN))
                .andExpect(status().isOk());

        assertEquals(USERNAME, callerSeenByService.get());
    }

    @Test
    void logout_returns400_andSkipsTheService_whenTheRefreshTokenIsAbsurdlyLong() throws Exception {
        mockMvc.perform(withToken(withJson(logoutRequest(), "{\"refreshToken\":\"" + "a".repeat(2049) + "\"}"), TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(authService);
    }

    @Test
    void logout_needsAToken_andNeverReachesTheService() throws Exception {
        mockMvc.perform(logoutRequest())
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(authService);
    }

    @Test
    void logout_rejectsAnInvalidToken_andNeverReachesTheService() throws Exception {
        mockMvc.perform(withToken(logoutRequest(), BAD_TOKEN))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(authService);
    }

    @Test
    void logout_mapsInvalidTokenException_to401() throws Exception {
        doThrow(new InvalidTokenException("Thiếu token xác thực")).when(authService).logout(any(), any());

        mockMvc.perform(withToken(logoutRequest(), TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("Thiếu token xác thực"));
    }

    // ------------------------------------------------------------------------------- a revoked token is answered with 401

    @Test
    void aRevokedToken_getsA401_onLogout_andNeverReachesTheService() throws Exception {
        mockMvc.perform(withToken(logoutRequest(), REVOKED_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(REVOKED_MESSAGE));

        verifyNoInteractions(authService);
    }

    @Test
    void theA401IsTheJsonApiResponseEnvelope_notSpringsErrorPage() throws Exception {
        mockMvc.perform(withToken(logoutRequest(), REVOKED_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value(REVOKED_MESSAGE))
                // Written into the body, not handed to sendError (which would render the container's error page).
                .andExpect(result -> assertNull(result.getResponse().getErrorMessage()));
    }

    @Test
    void aRevokedToken_getsA401_onChangePassword_andNeverReachesTheService() throws Exception {
        mockMvc.perform(withToken(withJson(put(CHANGE_PASSWORD_URL).servletPath(CHANGE_PASSWORD_URL), CHANGE_PASSWORD_BODY),
                        REVOKED_TOKEN))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(authService);
    }

    @Test
    void aRevokedToken_getsA401_onEveryOtherProtectedEndpointToo() throws Exception {
        // Nothing is mapped to this URL in the slice: the filter answers before any controller is looked for.
        mockMvc.perform(withToken(get(OTHER_PROTECTED_URL).servletPath(OTHER_PROTECTED_URL), REVOKED_TOKEN))
                .andExpect(status().isUnauthorized());

        verify(userDetailsService, never()).loadApplicationUser(any());
    }

    @Test
    void aTokenThatIsNotRevoked_isNotAnswered401() throws Exception {
        mockMvc.perform(withToken(logoutRequest(), TOKEN))
                .andExpect(status().isOk());

        verify(tokenBlacklistService).isRevoked(TOKEN);
    }

    // ------------------------------------------------ a token that cannot be used as an access token gets a JSON 401

    @Test
    void anExpiredAccessToken_getsAJson401_notThe403OfAnAnonymousRequest_soTheAppRenewsTheSession() throws Exception {
        mockMvc.perform(withToken(get(OTHER_PROTECTED_URL).servletPath(OTHER_PROTECTED_URL), EXPIRED_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value(EXPIRED_MESSAGE))
                .andExpect(result -> assertNull(result.getResponse().getErrorMessage()));

        verify(userDetailsService, never()).loadApplicationUser(any());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {BAD_TOKEN, UNVERSIONED_TOKEN, REFRESH_TOKEN})
    void aTokenThatIsNotAUsableAccessToken_getsAJson401_withoutTheBlacklistOrTheUserBeingAsked(String token) throws Exception {
        // A bad signature, a token without a type or a credentials version, and a refresh token presented as a bearer.
        mockMvc.perform(withToken(logoutRequest(), token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value(INVALID_MESSAGE))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(tokenBlacklistService, never()).isRevoked(any());
        verify(userDetailsService, never()).loadApplicationUser(any());
        verifyNoInteractions(authService);
    }

    @Test
    void aTokenWhoseAccountIsGone_getsAJson401_thatDoesNotNameTheAccount() throws Exception {
        String strangersToken = "stranger.jwt.token";
        when(jwtUtil.parse(strangersToken, TokenType.ACCESS)).thenReturn(new TokenClaims("mallory", CURRENT_VERSION));
        when(userDetailsService.loadApplicationUser("mallory"))
                .thenThrow(new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found: mallory"));

        mockMvc.perform(withToken(logoutRequest(), strangersToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(INVALID_MESSAGE));

        verifyNoInteractions(authService);
    }

    // ----------------------------------------------- a token of an older credentials version, or of none, gets 401 too

    @Test
    void aTokenIssuedBeforeThePasswordWasChanged_getsAJson401_andNeverReachesTheService() throws Exception {
        mockMvc.perform(withToken(logoutRequest(), OUTDATED_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value(OUTDATED_MESSAGE));

        verifyNoInteractions(authService);
    }

    @Test
    void aTokenWithoutACredentialsVersion_getsAJson401_beforeTheUserIsEvenLoaded() throws Exception {
        mockMvc.perform(withToken(get(OTHER_PROTECTED_URL).servletPath(OTHER_PROTECTED_URL), UNVERSIONED_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value(INVALID_MESSAGE));

        verify(userDetailsService, never()).loadApplicationUser(any());
    }

    @Test
    void anOutdatedToken_isRefusedOnChangePasswordToo() throws Exception {
        mockMvc.perform(withToken(withJson(put(CHANGE_PASSWORD_URL).servletPath(CHANGE_PASSWORD_URL), CHANGE_PASSWORD_BODY),
                        OUTDATED_TOKEN))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(authService);
    }

    @Test
    void aTokenOfTheCurrentCredentialsVersion_getsThrough() throws Exception {
        mockMvc.perform(withToken(logoutRequest(), TOKEN))
                .andExpect(status().isOk());

        verify(jwtUtil).parse(TOKEN, TokenType.ACCESS);
        verify(authService).logout(TOKEN, null);
    }

    @Test
    void thePublicAuthEndpointsNeverConsultTheBlacklist() throws Exception {
        mockMvc.perform(withToken(post(FORGOT_PASSWORD_URL).servletPath(FORGOT_PASSWORD_URL).param("email", "a@b.c"),
                        REVOKED_TOKEN))
                .andExpect(status().isOk());

        verify(tokenBlacklistService, never()).isRevoked(any());
    }

    // ------------------------------------------------------------------------------------------------------- refresh

    @Test
    void refresh_answersWithTheNewPair_underTheKeysItHasAlwaysUsed() throws Exception {
        when(authService.refresh(REFRESH_TOKEN)).thenReturn(AuthTokenResponseDto.builder()
                .token("new.access.token")
                .refreshToken("new.refresh.token")
                .build());

        mockMvc.perform(refreshRequest(REFRESH_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Refresh token thành công"))
                .andExpect(jsonPath("$.data.token").value("new.access.token"))
                .andExpect(jsonPath("$.data.refreshToken").value("new.refresh.token"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());

        // Revoked, outdated and double-spent tokens are the service's to refuse, inside its transaction.
        verify(authService).refresh(REFRESH_TOKEN);
        verifyNoInteractions(tokenBlacklistService);
    }

    @Test
    void refresh_answersAJson401_whenTheServiceRefusesTheToken() throws Exception {
        when(authService.refresh(REFRESH_TOKEN)).thenThrow(new InvalidTokenException(REFRESH_REFUSED_MESSAGE));

        mockMvc.perform(refreshRequest(REFRESH_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value(REFRESH_REFUSED_MESSAGE));
    }

    @Test
    void refresh_handsAMissingTokenToTheService_soItKeepsItsOld401() throws Exception {
        when(authService.refresh(null)).thenThrow(new InvalidTokenException(REFRESH_REFUSED_MESSAGE));

        mockMvc.perform(withJson(post(REFRESH_URL).servletPath(REFRESH_URL), "{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_returns400_andSkipsTheService_whenTheTokenIsAbsurdlyLong() throws Exception {
        mockMvc.perform(refreshRequest("a".repeat(2049)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(authService);
    }
}
