package vn.edu.huit.finance.core.Controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.edu.huit.finance.core.Config.SecurityConfig;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.GlobalExceptionHandler;
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

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Password login and social login issue their tokens in the controller: both tokens have to carry the credentials version
 * the account has at that moment, or the next password change could not end these sessions, and each has to be of its
 * type (the access token ACCESS, the refresh token REFRESH). A real JwtUtil signs them and the test parses them back out
 * of the answer. (Refresh and change-password issue theirs in AuthService, see
 * CrossSessionTokenRevocationTest.)
 */
@WebMvcTest(AuthController.class)
@ContextConfiguration(classes = {
        AuthController.class, GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthFilter.class, JwtUtil.class})
class AuthControllerTokenIssueTest {

    private static final String LOGIN_URL = AuthPaths.BASE + "/login";
    private static final String SOCIAL_LOGIN_URL = AuthPaths.BASE + "/social-login";
    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD = "OldPass123";
    private static final String PROVIDER = "google";
    private static final String PROVIDER_TOKEN = "google.id.token";
    // Not 0: the password has been changed a few times already, and the tokens must name the version they belong to.
    private static final int CREDENTIALS_VERSION = 4;
    private static final Map<String, TokenType> ISSUED_TOKENS = Map.of(
            "$.data.token", TokenType.ACCESS,
            "$.data.refreshToken", TokenType.REFRESH);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean private AuthService authService;
    @MockBean private CustomUserDetailsService userDetailsService;
    @MockBean private UserRepository userRepository;
    @MockBean private LoginAttemptService loginAttemptService;
    @MockBean private SocialAuthService socialAuthService;
    @MockBean private TokenBlacklistService tokenBlacklistService;

    private User alice;

    @BeforeEach
    void setUp() {
        alice = aliceOfVersion(CREDENTIALS_VERSION);
    }

    private static User aliceOfVersion(int credentialsVersion) {
        User user = new User();
        user.setId(1L);
        user.setUsername(EMAIL);
        user.setEmail(EMAIL);
        user.setRole("ROLE_USER");
        user.setCredentialsVersion(credentialsVersion);
        return user;
    }

    private MvcResult passwordLogin() throws Exception {
        // The real AuthenticationManager of SecurityConfig checks the password against this hash.
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(new org.springframework.security.core.userdetails.User(
                EMAIL, passwordEncoder.encode(PASSWORD), AuthorityUtils.createAuthorityList("ROLE_USER")));

        return mockMvc.perform(post(LOGIN_URL).servletPath(LOGIN_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void passwordLogin_issuesAnAccessAndARefreshToken_thatBothCarryTheAccountsCredentialsVersion() throws Exception {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(alice));

        assertTokensCarryTheVersion(passwordLogin(), CREDENTIALS_VERSION);
    }

    @Test
    void passwordLogin_issuesTheVersionReadBeforeThePasswordCheck_soALoginThatRacesAResetCannotOutliveIt() throws Exception {
        // The first read is before the password check; by the second one a reset has committed and raised the version.
        when(userRepository.findByEmail(EMAIL))
                .thenReturn(Optional.of(aliceOfVersion(CREDENTIALS_VERSION)))
                .thenReturn(Optional.of(aliceOfVersion(CREDENTIALS_VERSION + 1)));

        // Tokens of the new version would survive the reset, although the password they were won with is gone.
        assertTokensCarryTheVersion(passwordLogin(), CREDENTIALS_VERSION);
    }

    @Test
    void socialLogin_issuesAnAccessAndARefreshToken_thatBothCarryTheAccountsCredentialsVersion() throws Exception {
        when(socialAuthService.authenticate(PROVIDER, PROVIDER_TOKEN)).thenReturn(alice);

        MvcResult result = mockMvc.perform(post(SOCIAL_LOGIN_URL).servletPath(SOCIAL_LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"" + PROVIDER + "\",\"token\":\"" + PROVIDER_TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertTokensCarryTheVersion(result, CREDENTIALS_VERSION);
    }

    /** Both tokens of the answer: signed, of their own type (parse refuses the other one), for the account and the version. */
    private void assertTokensCarryTheVersion(MvcResult result, int expectedVersion) throws Exception {
        String body = result.getResponse().getContentAsString();
        for (Map.Entry<String, TokenType> issued : ISSUED_TOKENS.entrySet()) {
            String token = JsonPath.read(body, issued.getKey());
            TokenClaims claims = jwtUtil.parse(token, issued.getValue());
            assertEquals(EMAIL, claims.subject(), issued.getKey());
            assertEquals(expectedVersion, claims.credentialsVersion(), issued.getKey());
        }
    }
}
