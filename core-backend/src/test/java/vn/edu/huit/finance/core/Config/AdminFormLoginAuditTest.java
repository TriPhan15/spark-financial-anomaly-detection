package vn.edu.huit.finance.core.Config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.GlobalExceptionHandler;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CustomUserDetailsService;
import vn.edu.huit.finance.core.Security.JwtAuthFilter;
import vn.edu.huit.finance.core.Security.JwtUtil;
import vn.edu.huit.finance.core.Security.Roles;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditEvent;
import vn.edu.huit.finance.core.Service.LoginAttemptService;
import vn.edu.huit.finance.core.Service.TokenBlacklistService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/**
 * The sign-in form of the admin pages (Spring Security's form login, configured in SecurityConfig) records every attempt
 * and every sign-out as a SecurityAuditEvent, like the API's sign-in does. No password ever reaches a row.
 */
@WebMvcTest
@ContextConfiguration(classes = {GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthFilter.class})
@RecordApplicationEvents
class AdminFormLoginAuditTest {

    private static final String LOGIN = "/admin/login";
    private static final String LOGOUT = "/admin/logout";
    private static final String ROOT = "root";
    private static final String PASSWORD = "RootPass123";
    private static final String WRONG_PASSWORD = "Guess1234";

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationEvents events;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean private CustomUserDetailsService userDetailsService;
    @MockBean private UserRepository userRepository;
    @MockBean private LoginAttemptService loginAttemptService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private TokenBlacklistService tokenBlacklistService;

    private User root;

    @BeforeEach
    void setUp() {
        root = new User();
        root.setId(1L);
        root.setUsername(ROOT);
        root.setEmail("root@example.com");
        root.setRole(Roles.ADMIN);
        when(userDetailsService.loadUserByUsername(ROOT)).thenReturn(new org.springframework.security.core.userdetails.User(
                ROOT, passwordEncoder.encode(PASSWORD), AuthorityUtils.createAuthorityList(Roles.ADMIN)));
        when(userRepository.findByUsername(ROOT)).thenReturn(Optional.of(root));
    }

    private static MockHttpServletRequestBuilder signIn(String password) {
        return post(LOGIN).servletPath(LOGIN).param("username", ROOT).param("password", password);
    }

    private List<AuditEntry> recorded() {
        return events.stream(SecurityAuditEvent.class).map(SecurityAuditEvent::entry).toList();
    }

    private AuditEntry theOnlyRow() {
        List<AuditEntry> recorded = recorded();
        assertEquals(1, recorded.size(), recorded.toString());
        return recorded.get(0);
    }

    private static void assertNoPasswordIn(AuditEntry entry) {
        assertFalse(entry.description().contains(PASSWORD));
        assertFalse(entry.description().contains(WRONG_PASSWORD));
    }

    @Test
    void aSuccessfulSignInIsRecordedWithTheAccount() throws Exception {
        mockMvc.perform(signIn(PASSWORD)).andExpect(redirectedUrl("/admin/dashboard"));

        AuditEntry row = theOnlyRow();
        assertEquals(AuditAction.AUTH_LOGIN_SUCCESS, row.action());
        assertEquals(AuditActor.of(root), row.actor());
        assertEquals(1L, row.targetId());
        assertNoPasswordIn(row);
    }

    @Test
    void aWrongPasswordIsRecorded_withTheNameTypedInAsTheOnlyIdentity() throws Exception {
        mockMvc.perform(signIn(WRONG_PASSWORD)).andExpect(redirectedUrl("/admin/login?error=true"));

        AuditEntry row = theOnlyRow();
        assertEquals(AuditAction.AUTH_LOGIN_FAILED, row.action());
        assertEquals(AuditActor.unauthenticated(ROOT), row.actor());
        assertNull(row.targetId());
        assertNoPasswordIn(row);
    }

    @Test
    void theFailureThatTriggersTheLockoutIsRecordedAsALockout() throws Exception {
        // The lockout filter lets the attempt through; after recording the failure, the account or address is locked.
        doNothing().doThrow(new LockedException("locked")).when(loginAttemptService).assertLoginAllowed(any(), any());

        mockMvc.perform(signIn(WRONG_PASSWORD)).andExpect(redirectedUrl("/admin/login?locked=true"));

        AuditEntry row = theOnlyRow();
        assertEquals(AuditAction.AUTH_LOGIN_LOCKED, row.action());
        assertEquals(AuditActor.unauthenticated(ROOT), row.actor());
    }

    @Test
    void anAttemptWhileLockedOutIsRecorded_andThePasswordIsNotEvenChecked() throws Exception {
        doThrow(new LockedException("locked")).when(loginAttemptService).assertLoginAllowed(any(), any());

        mockMvc.perform(signIn(PASSWORD)).andExpect(redirectedUrl("/admin/login?locked=true"));

        assertEquals(AuditAction.AUTH_LOGIN_LOCKED, theOnlyRow().action());
        verify(userDetailsService, never()).loadUserByUsername(any());
    }

    @Test
    void aSignOutIsRecordedWithTheAccount() throws Exception {
        MockHttpSession session = (MockHttpSession) mockMvc.perform(signIn(PASSWORD)).andReturn().getRequest().getSession();
        events.clear();

        mockMvc.perform(post(LOGOUT).servletPath(LOGOUT).session(session)).andExpect(redirectedUrl("/admin/login"));

        AuditEntry row = theOnlyRow();
        assertEquals(AuditAction.AUTH_LOGOUT, row.action());
        assertEquals(AuditActor.of(root), row.actor());
        assertEquals(1L, row.targetId());
    }

    @Test
    void aSignOutWithoutASessionRecordsNothing() throws Exception {
        mockMvc.perform(post(LOGOUT).servletPath(LOGOUT)).andExpect(redirectedUrl("/admin/login"));

        assertEquals(List.of(), recorded());
    }
}
