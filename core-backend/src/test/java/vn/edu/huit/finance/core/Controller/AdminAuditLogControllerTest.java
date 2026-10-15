package vn.edu.huit.finance.core.Controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.edu.huit.finance.core.Config.SecurityConfig;
import vn.edu.huit.finance.core.Dto.AuditLogFilterRequestDto;
import vn.edu.huit.finance.core.Dto.AuditLogPageResponseDto;
import vn.edu.huit.finance.core.Dto.AuditLogResponseDto;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.GlobalExceptionHandler;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CustomUserDetailsService;
import vn.edu.huit.finance.core.Security.JwtAuthFilter;
import vn.edu.huit.finance.core.Security.JwtUtil;
import vn.edu.huit.finance.core.Security.TokenClaims;
import vn.edu.huit.finance.core.Security.TokenType;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditEvent;
import vn.edu.huit.finance.core.Service.AuditLogQueryService;
import vn.edu.huit.finance.core.Service.LoginAttemptService;
import vn.edu.huit.finance.core.Service.TokenBlacklistService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/admin/audit-logs through the real filter chain: administrators only (a user's 403 is itself recorded), filters
 * validated before anything is read, and an answer that holds exactly the fields of a row, nothing more.
 */
@WebMvcTest(AdminAuditLogController.class)
@ContextConfiguration(classes = {
        AdminAuditLogController.class, GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthFilter.class})
@RecordApplicationEvents
class AdminAuditLogControllerTest {

    private static final String URL = "/api/admin/audit-logs";
    private static final String USER_TOKEN = "user.jwt.token";
    private static final String ADMIN_TOKEN = "admin.jwt.token";

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationEvents events;

    @MockBean private AuditLogQueryService auditLogQueryService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private CustomUserDetailsService userDetailsService;
    @MockBean private UserRepository userRepository;
    @MockBean private LoginAttemptService loginAttemptService;
    @MockBean private TokenBlacklistService tokenBlacklistService;

    @BeforeEach
    void stubTokens() {
        stubToken(USER_TOKEN, "alice", "ROLE_USER");
        stubToken(ADMIN_TOKEN, "root", "ROLE_ADMIN");
    }

    @AfterEach
    void clearTheSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void stubToken(String token, String username, String role) {
        when(jwtUtil.parse(token, TokenType.ACCESS)).thenReturn(new TokenClaims(username, 0));
        User user = new User();
        user.setUsername(username);
        user.setActive(true);
        user.setRole(role);
        user.setLastActionTime(LocalDateTime.now());
        when(userDetailsService.loadApplicationUser(username)).thenReturn(user);
        when(userDetailsService.loadUserByUsername(username)).thenReturn(
                new org.springframework.security.core.userdetails.User(username, "hash", AuthorityUtils.createAuthorityList(role)));
    }

    private static MockHttpServletRequestBuilder as(String token) {
        return get(URL).servletPath(URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static AuditLogPageResponseDto onePage() {
        AuditLogResponseDto row = AuditLogResponseDto.builder()
                .id(9L).timestamp(LocalDateTime.of(2026, 10, 1, 8, 30)).actorUserId(7L).actorEmail("alice@example.com")
                .action("TRANSACTION_DELETED").storedAction("TRANSACTION_DELETED").actionLabel("Xóa giao dịch")
                .category("DATA").targetType("TRANSACTION").targetLabel("Giao dịch").targetId(42L).result("SUCCESS")
                .description("Xóa giao dịch chi 150000 VNĐ").ipAddress("203.0.113.7")
                .build();
        return AuditLogPageResponseDto.builder()
                .content(List.of(row)).page(0).size(50).totalElements(1).totalPages(1).hasNext(false).hasPrevious(false)
                .build();
    }

    @Test
    void anAdministratorReadsAPageOfTheLog_withExactlyTheFieldsOfARow() throws Exception {
        when(auditLogQueryService.search(any())).thenReturn(onePage());

        String body = mockMvc.perform(as(ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].action").value("TRANSACTION_DELETED"))
                .andExpect(jsonPath("$.data.content[0].targetId").value(42))
                .andExpect(jsonPath("$.data.content[0].actorUserId").value(7))
                .andReturn().getResponse().getContentAsString();

        Set<String> fields = new TreeSet<>(com.jayway.jsonpath.JsonPath.<java.util.Map<String, Object>>read(body, "$.data.content[0]").keySet());
        assertEquals(new TreeSet<>(Set.of("id", "timestamp", "actorUserId", "actorEmail", "action", "storedAction",
                "actionLabel", "category", "targetType", "targetLabel", "targetId", "result", "description", "ipAddress")), fields);
    }

    @Test
    void theFiltersReachTheServiceAsSent() throws Exception {
        when(auditLogQueryService.search(any())).thenReturn(onePage());

        mockMvc.perform(as(ADMIN_TOKEN).param("action", "LOGIN_FAILED").param("userId", "7").param("email", "a@b.c")
                        .param("targetType", "USER").param("targetId", "7").param("from", "2026-09-01T00:00")
                        .param("to", "2026-10-01T23:59:59").param("page", "2").param("size", "20"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLogFilterRequestDto> filter = ArgumentCaptor.forClass(AuditLogFilterRequestDto.class);
        verify(auditLogQueryService).search(filter.capture());
        assertEquals(AuditAction.AUTH_LOGIN_FAILED, filter.getValue().actionFilter().orElseThrow());
        assertEquals(7L, filter.getValue().getUserId());
        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), filter.getValue().getFrom());
        assertEquals(LocalDateTime.of(2026, 10, 1, 23, 59, 59), filter.getValue().getTo());
        assertEquals(2, filter.getValue().getPage());
        assertEquals(20, filter.getValue().getSize());
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "size, 101", "size, 0", "page, -1", "page, 10001", "action, NOT_AN_ACTION", "targetType, PASSWORD",
            "userId, 0", "targetId, -3"})
    void aFilterOutOfBoundsIsRefusedWith400_beforeAnythingIsRead(String parameter, String value) throws Exception {
        mockMvc.perform(as(ADMIN_TOKEN).param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(auditLogQueryService);
    }

    @Test
    void aTargetIdWithoutItsKindAndARangeThatEndsBeforeItStartsAreRefused() throws Exception {
        mockMvc.perform(as(ADMIN_TOKEN).param("targetId", "5")).andExpect(status().isBadRequest());
        mockMvc.perform(as(ADMIN_TOKEN).param("from", "2026-10-02T00:00").param("to", "2026-10-01T00:00"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(auditLogQueryService);
    }

    @Test
    void anOrdinaryUserIsForbidden_theLogIsNeverRead_andTheRefusalIsItselfRecorded() throws Exception {
        mockMvc.perform(as(USER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(auditLogQueryService);
        assertEquals(List.of(AuditAction.AUTH_ACCESS_DENIED),
                events.stream(SecurityAuditEvent.class).map(event -> event.entry().action()).toList());
    }

    @Test
    void withoutATokenTheRequestIsRejected() throws Exception {
        mockMvc.perform(get(URL).servletPath(URL)).andExpect(status().isUnauthorized());

        verifyNoInteractions(auditLogQueryService);
    }
}
