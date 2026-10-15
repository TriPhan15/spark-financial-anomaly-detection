package vn.edu.huit.finance.core.Security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.huit.finance.core.Config.SecurityConfig;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.GlobalExceptionHandler;
import vn.edu.huit.finance.core.Exception.TransactionAccessDeniedException;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditEvent;
import vn.edu.huit.finance.core.Service.LoginAttemptService;
import vn.edu.huit.finance.core.Service.TokenBlacklistService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every 403 the application answers a signed-in caller is recorded as AUTH_ACCESS_DENIED, whichever check refused it:
 * a URL rule of SecurityConfig (API and admin pages), @PreAuthorize, or the ownership checks of the services. The API
 * answers each of them with the same ApiResponse JSON. A caller that is not signed in gets 401 and is not recorded: that
 * is no decision about a known caller, and anybody can cause it. Probe controllers stand for the real ones.
 */
@WebMvcTest({AccessDeniedAuditTest.ApiProbe.class, AccessDeniedAuditTest.AdminPageProbe.class})
@ContextConfiguration(classes = {AccessDeniedAuditTest.ApiProbe.class, AccessDeniedAuditTest.AdminPageProbe.class,
        GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthFilter.class})
@RecordApplicationEvents
class AccessDeniedAuditTest {

    private static final String ADMIN_API = "/api/admin/probe";
    private static final String ADMIN_METHOD = "/api/probe/admin-only";
    private static final String SOMEONE_ELSES = "/api/probe/someone-elses";
    private static final String ADMIN_PAGE = "/admin/probe";
    private static final String USER_TOKEN = "user.jwt.token";
    private static final String ADMIN_TOKEN = "admin.jwt.token";
    private static final String BOB = "bob";
    private static final String ROOT = "root";
    private static final String PASSWORD = "BobPass123";

    @RestController
    static class ApiProbe {
        @GetMapping(ADMIN_API)
        String adminOnlyByUrl() {
            return "admin";
        }

        @PreAuthorize(Roles.HAS_ADMIN)
        @GetMapping(ADMIN_METHOD)
        String adminOnlyByMethod() {
            return "admin";
        }

        @GetMapping(SOMEONE_ELSES)
        String someoneElses() {
            throw new TransactionAccessDeniedException("Bạn không có quyền thao tác trên giao dịch này");
        }
    }

    @Controller
    static class AdminPageProbe {
        @GetMapping(ADMIN_PAGE)
        @ResponseBody
        String adminPage() {
            return "page";
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationEvents events;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean private JwtUtil jwtUtil;
    @MockBean private CustomUserDetailsService userDetailsService;
    @MockBean private UserRepository userRepository;
    @MockBean private LoginAttemptService loginAttemptService;
    @MockBean private TokenBlacklistService tokenBlacklistService;

    @BeforeEach
    void setUp() {
        signedInWith(USER_TOKEN, BOB, Roles.USER);
        signedInWith(ADMIN_TOKEN, ROOT, Roles.ADMIN);
    }

    private void signedInWith(String token, String username, String role) {
        User user = new User();
        user.setId(username.equals(BOB) ? 2L : 1L);
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setRole(role);
        user.setActive(true);
        when(jwtUtil.parse(token, TokenType.ACCESS)).thenReturn(new TokenClaims(username, 0));
        when(userDetailsService.loadApplicationUser(username)).thenReturn(user);
        when(userDetailsService.loadUserByUsername(username)).thenReturn(new org.springframework.security.core.userdetails.User(
                username, passwordEncoder.encode(PASSWORD), AuthorityUtils.createAuthorityList(role)));
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
    }

    private static MockHttpServletRequestBuilder api(String url, String token) {
        MockHttpServletRequestBuilder request = get(url).servletPath(url);
        return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private List<AuditEntry> recorded() {
        return events.stream(SecurityAuditEvent.class).map(SecurityAuditEvent::entry).toList();
    }

    /** One AUTH_ACCESS_DENIED about the signed-in caller (looked up when the row is written), naming why and where. */
    private void assertOneDenialRecorded(String reason, String where) {
        List<AuditEntry> recorded = recorded();
        assertEquals(1, recorded.size(), recorded.toString());
        AuditEntry denial = recorded.get(0);
        assertEquals(AuditAction.AUTH_ACCESS_DENIED, denial.action());
        assertTrue(denial.actor().isCurrentCaller());
        assertNull(denial.targetId());
        assertEquals(reason + ": " + where, denial.description());
    }

    private void assertTheJson403OfTheApi(MvcResult result) throws Exception {
        assertEquals(403, result.getResponse().getStatus());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(result.getResponse().getContentType())));
        // JSON is UTF-8 whatever the Content-Type says (RFC 8259); MVC's answer carries no charset, the mock defaults to Latin-1.
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"code\":403"), body);
        assertTrue(body.contains(AuditingAccessDeniedHandler.MSG_ACCESS_DENIED), body);
    }

    @Test
    void aUserOnAnAdminUrlIsRefusedByTheUrlRule_withTheJson403OfTheApi_andRecorded() throws Exception {
        MvcResult result = mockMvc.perform(api(ADMIN_API, USER_TOKEN)).andReturn();

        assertTheJson403OfTheApi(result);
        assertOneDenialRecorded("Quy tắc phân quyền theo URL", "GET " + ADMIN_API);
    }

    @Test
    void aUserOnAnEndpointGuardedByPreAuthorizeIsRefused_andRecorded() throws Exception {
        MvcResult result = mockMvc.perform(api(ADMIN_METHOD, USER_TOKEN)).andReturn();

        assertTheJson403OfTheApi(result);
        assertOneDenialRecorded("Phân quyền theo vai trò", "GET " + ADMIN_METHOD);
    }

    @Test
    void aRecordOfSomebodyElseIsRefused_andRecorded() throws Exception {
        mockMvc.perform(api(SOMEONE_ELSES, USER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        assertOneDenialRecorded("Dữ liệu thuộc về người dùng khác", "GET " + SOMEONE_ELSES);
    }

    @Test
    void anAdministratorIsServed_andNothingIsRecorded() throws Exception {
        mockMvc.perform(api(ADMIN_API, ADMIN_TOKEN)).andExpect(status().isOk()).andExpect(content().string("admin"));
        mockMvc.perform(api(ADMIN_METHOD, ADMIN_TOKEN)).andExpect(status().isOk());

        assertEquals(List.of(), recorded());
    }

    @Test
    void aCallerWithoutTokenGets401_andIsNotRecordedAsDenied() throws Exception {
        mockMvc.perform(api(ADMIN_API, null)).andExpect(status().isUnauthorized());

        assertEquals(List.of(), recorded());
    }

    @Test
    void aUserSignedInOnTheAdminPagesIsRefusedThe403Page_andRecorded() throws Exception {
        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/admin/login").servletPath("/admin/login")
                        .param("username", BOB).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn().getRequest().getSession();
        events.clear();

        mockMvc.perform(get(ADMIN_PAGE).servletPath(ADMIN_PAGE).session(session))
                .andExpect(status().isForbidden());

        assertOneDenialRecorded("Quy tắc phân quyền theo URL", "GET " + ADMIN_PAGE);
    }
}
