package vn.edu.huit.finance.core.Controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.edu.huit.finance.core.Config.SecurityConfig;
import vn.edu.huit.finance.core.Dto.ApiResponse;
import vn.edu.huit.finance.core.Dto.ReceiptAttemptResponseDto;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.GlobalExceptionHandler;
import vn.edu.huit.finance.core.Exception.ReceiptNotFoundException;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CustomUserDetailsService;
import vn.edu.huit.finance.core.Security.JwtAuthFilter;
import vn.edu.huit.finance.core.Security.JwtUtil;
import vn.edu.huit.finance.core.Security.Roles;
import vn.edu.huit.finance.core.Security.TokenClaims;
import vn.edu.huit.finance.core.Security.TokenType;
import vn.edu.huit.finance.core.Service.LoginAttemptService;
import vn.edu.huit.finance.core.Service.ReceiptProcessingService;
import vn.edu.huit.finance.core.Service.TokenBlacklistService;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The readings of a receipt carry what the AI answered, which no owner endpoint returns, so this is where "only an
 * administrator may see them" is proven: through the whole filter chain, and through @PreAuthorize alone.
 */
@WebMvcTest(AdminReceiptController.class)
@ContextConfiguration(classes = {
        AdminReceiptController.class, GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthFilter.class})
class AdminReceiptControllerTest {

    private static final String ATTEMPTS = "/api/admin/receipts/7/attempts";
    private static final String USER_TOKEN = "user.jwt.token";
    private static final String ADMIN_TOKEN = "admin.jwt.token";
    private static final String ANSWER = "{\"seller\": {\"seller_name\": \"Highlands\", \"total_cost_number\": 95000}}";

    @Autowired private MockMvc mockMvc;
    @Autowired private AdminReceiptController controller;

    @MockBean private ReceiptProcessingService receiptProcessingService;
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

    private static MockHttpServletRequestBuilder as(String token, String path) {
        return get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static List<ReceiptAttemptResponseDto> twoReadings() {
        return List.of(
                ReceiptAttemptResponseDto.builder().id(1L).receiptId(7L).attemptNumber(1).status("FAILED")
                        .aiRawData("{\"raw_text\": \"noise\"}").errorCode("AI_UNREADABLE")
                        .safeErrorMessage("AI không đọc được nội dung hóa đơn").build(),
                ReceiptAttemptResponseDto.builder().id(2L).receiptId(7L).attemptNumber(2).status("COMPLETED")
                        .aiRawData(ANSWER).clientId("attempt-2").build());
    }

    // ---------------------------------------------------------------------------------- who may call, through the filter chain

    @Test
    void anOrdinaryUserIsForbiddenEvenForTheirOwnReceiptAndTheServiceIsNeverAsked() throws Exception {
        mockMvc.perform(as(USER_TOKEN, ATTEMPTS)).andExpect(status().isForbidden());

        verifyNoInteractions(receiptProcessingService);
    }

    @Test
    void withoutATokenTheRequestIsRejected() throws Exception {
        mockMvc.perform(get(ATTEMPTS)).andExpect(status().isUnauthorized());

        verifyNoInteractions(receiptProcessingService);
    }

    @Test
    void anAdministratorReadsEveryReadingOfTheReceiptWithWhatTheAiAnswered() throws Exception {
        when(receiptProcessingService.getAttemptsOf(7L)).thenReturn(twoReadings());

        mockMvc.perform(as(ADMIN_TOKEN, ATTEMPTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Lấy lịch sử các lần đọc hóa đơn thành công"))
                .andExpect(jsonPath("$.data[0].attemptNumber").value(1))
                .andExpect(jsonPath("$.data[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].errorCode").value("AI_UNREADABLE"))
                .andExpect(jsonPath("$.data[1].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data[1].aiRawData").value(ANSWER))
                .andExpect(jsonPath("$.data[1].clientId").value("attempt-2"));

        verify(receiptProcessingService).getAttemptsOf(7L);
        verifyNoMoreInteractions(receiptProcessingService);
    }

    @Test
    void anUnknownReceiptIsANotFound() throws Exception {
        when(receiptProcessingService.getAttemptsOf(404L)).thenThrow(new ReceiptNotFoundException("Không tìm thấy hóa đơn"));

        mockMvc.perform(as(ADMIN_TOKEN, "/api/admin/receipts/404/attempts"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Không tìm thấy hóa đơn"));
    }

    @Test
    void anIdThatIsNotANumberIsA400() throws Exception {
        mockMvc.perform(as(ADMIN_TOKEN, "/api/admin/receipts/abc/attempts")).andExpect(status().isBadRequest());

        verifyNoInteractions(receiptProcessingService);
    }

    // ------------------------------------------------------------------------------------ @PreAuthorize on its own

    private static void signInWithRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "someone", null, AuthorityUtils.createAuthorityList(role)));
    }

    @Test
    void preAuthorizeAloneStopsAnOrdinaryUserBeforeTheHandlerRuns() {
        // Calling the bean directly skips the URL rule of SecurityConfig, so only the annotation can say no here.
        signInWithRole("ROLE_USER");

        assertThrows(AccessDeniedException.class, () -> controller.getAttempts(7L));

        verifyNoInteractions(receiptProcessingService);
    }

    @Test
    void preAuthorizeLetsAnAdministratorThrough() {
        signInWithRole("ROLE_ADMIN");
        when(receiptProcessingService.getAttemptsOf(7L)).thenReturn(twoReadings());

        ResponseEntity<ApiResponse<List<ReceiptAttemptResponseDto>>> response = controller.getAttempts(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(ANSWER, response.getBody().getData().get(1).getAiRawData());
    }

    @Test
    void theWholeClassIsClosedToNonAdministratorsByTheAnnotation() {
        PreAuthorize annotation = AdminReceiptController.class.getAnnotation(PreAuthorize.class);

        assertNotNull(annotation, "@PreAuthorize must sit on the class so that no handler can be added without it");
        assertEquals(Roles.HAS_ADMIN, annotation.value());
        for (Method method : AdminReceiptController.class.getDeclaredMethods()) {
            PreAuthorize own = method.getAnnotation(PreAuthorize.class);
            assertTrue(own == null || Roles.HAS_ADMIN.equals(own.value()),
                    method.getName() + " must not loosen the class-level rule");
        }
    }
}
