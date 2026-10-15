package vn.edu.huit.finance.core.Controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.edu.huit.finance.core.Config.SecurityConfig;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.GlobalExceptionHandler;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.AuthPaths;
import vn.edu.huit.finance.core.Security.CustomUserDetailsService;
import vn.edu.huit.finance.core.Security.JwtAuthFilter;
import vn.edu.huit.finance.core.Security.JwtUtil;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditEvent;
import vn.edu.huit.finance.core.Service.AuthService;
import vn.edu.huit.finance.core.Service.LoginAttemptService;
import vn.edu.huit.finance.core.Service.SocialAuthService;
import vn.edu.huit.finance.core.Service.TokenBlacklistService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every sign-in attempt on the API is recorded as a SecurityAuditEvent (a row of its own): success with the account,
 * wrong credentials and lockouts with only the email typed in, social sign-ins with the provider. Never a password,
 * never a token, never the provider's token.
 */
@WebMvcTest(AuthController.class)
@ContextConfiguration(classes = {
        AuthController.class, GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthFilter.class, JwtUtil.class})
@RecordApplicationEvents
class AuthControllerLoginAuditTest {

    private static final String LOGIN_URL = AuthPaths.BASE + "/login";
    private static final String SOCIAL_LOGIN_URL = AuthPaths.BASE + "/social-login";
    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD = "OldPass123";
    private static final String WRONG_PASSWORD = "Guess1234";
    private static final String PROVIDER_TOKEN = "google.id.token";

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationEvents events;
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
        alice = new User();
        alice.setId(1L);
        alice.setUsername(EMAIL);
        alice.setEmail(EMAIL);
        alice.setRole("ROLE_USER");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(alice));
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(new org.springframework.security.core.userdetails.User(
                EMAIL, passwordEncoder.encode(PASSWORD), AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    private static MockHttpServletRequestBuilder login(String password) {
        return post(LOGIN_URL).servletPath(LOGIN_URL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}");
    }

    private static MockHttpServletRequestBuilder socialLogin(String provider) {
        return post(SOCIAL_LOGIN_URL).servletPath(SOCIAL_LOGIN_URL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"" + provider + "\",\"token\":\"" + PROVIDER_TOKEN + "\"}");
    }

    private AuditEntry theOnlyRow() {
        List<AuditEntry> recorded = events.stream(SecurityAuditEvent.class).map(SecurityAuditEvent::entry).toList();
        assertEquals(1, recorded.size(), recorded.toString());
        return recorded.get(0);
    }

    private static void assertNoSecretIn(AuditEntry entry, String... secrets) {
        for (String secret : secrets) {
            assertFalse(entry.description().contains(secret), entry.action() + " must not contain " + secret);
        }
    }

    @Test
    void aSuccessfulSignInIsRecordedWithTheAccount() throws Exception {
        String body = mockMvc.perform(login(PASSWORD)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        AuditEntry row = theOnlyRow();
        assertEquals(AuditAction.AUTH_LOGIN_SUCCESS, row.action());
        assertEquals(AuditActor.of(alice), row.actor());
        assertEquals(1L, row.targetId());
        assertNoSecretIn(row, PASSWORD);
        assertFalse(body.isEmpty());
    }

    @Test
    void aWrongPasswordIsRecorded_namingOnlyTheEmailTypedIn() throws Exception {
        mockMvc.perform(login(WRONG_PASSWORD)).andExpect(status().isUnauthorized());

        AuditEntry row = theOnlyRow();
        assertEquals(AuditAction.AUTH_LOGIN_FAILED, row.action());
        assertEquals(AuditActor.unauthenticated(EMAIL), row.actor(), "not signed in: no account is named");
        assertNull(row.targetId());
        assertNoSecretIn(row, WRONG_PASSWORD, PASSWORD);
    }

    @Test
    void theFailureThatTriggersTheLockoutIsRecordedAsALockout() throws Exception {
        doNothing().doThrow(new LockedException("locked")).when(loginAttemptService).assertLoginAllowed(any(), any());

        mockMvc.perform(login(WRONG_PASSWORD)).andExpect(status().isTooManyRequests());

        AuditEntry row = theOnlyRow();
        assertEquals(AuditAction.AUTH_LOGIN_LOCKED, row.action());
        assertEquals(AuditActor.unauthenticated(EMAIL), row.actor());
    }

    @Test
    void anAttemptWhileLockedOutIsRecorded() throws Exception {
        doThrow(new LockedException("locked")).when(loginAttemptService).assertLoginAllowed(any(), any());

        mockMvc.perform(login(PASSWORD)).andExpect(status().isTooManyRequests());

        assertEquals(AuditAction.AUTH_LOGIN_LOCKED, theOnlyRow().action());
    }

    @Test
    void aSocialSignInIsRecordedWithTheAccountAndTheProvider_neverItsToken() throws Exception {
        when(socialAuthService.authenticate("Google", PROVIDER_TOKEN)).thenReturn(alice);

        mockMvc.perform(socialLogin("Google")).andExpect(status().isOk());

        AuditEntry row = theOnlyRow();
        assertEquals(AuditAction.AUTH_LOGIN_SUCCESS, row.action());
        assertEquals(AuditActor.of(alice), row.actor());
        assertEquals("Đăng nhập App Mobile qua google", row.description());
        assertNoSecretIn(row, PROVIDER_TOKEN);
    }

    @Test
    void aRefusedSocialSignInIsRecordedWithoutAnyIdentity_andAProviderNameTheServerDoesNotKnowIsNotRepeated()
            throws Exception {
        when(socialAuthService.authenticate(any(), any())).thenThrow(new RuntimeException("Provider không hỗ trợ"));

        mockMvc.perform(socialLogin("<script>evil</script>")).andExpect(status().isUnauthorized());

        AuditEntry row = theOnlyRow();
        assertEquals(AuditAction.AUTH_LOGIN_FAILED, row.action());
        assertEquals(AuditActor.unauthenticated(null), row.actor());
        assertEquals("Đăng nhập qua nhà cung cấp không hỗ trợ thất bại", row.description());
        assertNoSecretIn(row, PROVIDER_TOKEN, "script");
    }
}
