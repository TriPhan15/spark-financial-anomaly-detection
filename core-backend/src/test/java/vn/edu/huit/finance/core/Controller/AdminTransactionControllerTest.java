package vn.edu.huit.finance.core.Controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import vn.edu.huit.finance.core.Dto.AppDashboardResponseDto;
import vn.edu.huit.finance.core.Dto.PopularVendorResponseDto;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.GlobalExceptionHandler;
import vn.edu.huit.finance.core.Exception.UserNotFoundException;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CustomUserDetailsService;
import vn.edu.huit.finance.core.Security.JwtAuthFilter;
import vn.edu.huit.finance.core.Security.JwtUtil;
import vn.edu.huit.finance.core.Security.Roles;
import vn.edu.huit.finance.core.Security.TokenClaims;
import vn.edu.huit.finance.core.Security.TokenType;
import vn.edu.huit.finance.core.Service.LoginAttemptService;
import vn.edu.huit.finance.core.Service.TokenBlacklistService;
import vn.edu.huit.finance.core.Service.TransactionService;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The cross-user statistics are the only place where a user id comes from the request, so this is where "only an
 * administrator may do it" is proven: through the whole filter chain, and through @PreAuthorize alone.
 */
@WebMvcTest(AdminTransactionController.class)
@ContextConfiguration(classes = {
        AdminTransactionController.class, GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthFilter.class})
class AdminTransactionControllerTest {

    private static final String VENDORS = "/api/admin/transactions/popular-vendors";
    private static final String DASHBOARD = "/api/admin/transactions/app-dashboard";
    private static final String USER_TOKEN = "user.jwt.token";
    private static final String ADMIN_TOKEN = "admin.jwt.token";

    @Autowired private MockMvc mockMvc;
    @Autowired private AdminTransactionController controller;

    @MockBean private TransactionService transactionService;
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

    private static PopularVendorResponseDto vendor() {
        return PopularVendorResponseDto.builder().vendorName("Highlands").transactionCount(5).build();
    }

    // ---------------------------------------------------------------------------------- who may call, through the filter chain

    @ParameterizedTest
    @ValueSource(strings = {VENDORS + "?userId=2", DASHBOARD + "?userId=2"})
    void anOrdinaryUserIsForbiddenAndTheServiceIsNeverAsked(String path) throws Exception {
        mockMvc.perform(as(USER_TOKEN, path)).andExpect(status().isForbidden());

        verifyNoInteractions(transactionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {VENDORS + "?userId=2", DASHBOARD + "?userId=2"})
    void anOrdinaryUserAskingAboutThemselvesIsForbiddenToo(String path) throws Exception {
        // Not even their own id opens the administrator endpoints: they have /api/transactions/... for that.
        mockMvc.perform(as(USER_TOKEN, path.replace("userId=2", "userId=1"))).andExpect(status().isForbidden());

        verifyNoInteractions(transactionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {VENDORS + "?userId=2", DASHBOARD + "?userId=2"})
    void withoutATokenTheRequestIsRejected(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().is4xxClientError());

        verifyNoInteractions(transactionService);
    }

    @Test
    void anAdministratorReadsThePopularVendorsOfTheUserTheyName() throws Exception {
        when(transactionService.getPopularVendorsOf(2L)).thenReturn(List.of(vendor()));

        mockMvc.perform(as(ADMIN_TOKEN, VENDORS + "?userId=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Lấy danh sách độ phổ biến thành công"))
                .andExpect(jsonPath("$.data[0].vendorName").value("Highlands"))
                .andExpect(jsonPath("$.data[0].transactionCount").value(5));

        verify(transactionService).getPopularVendorsOf(2L);
        verifyNoMoreInteractions(transactionService);
    }

    @Test
    void anAdministratorReadsTheDashboardOfTheUserTheyName() throws Exception {
        when(transactionService.getDashboardOf(2L)).thenReturn(AppDashboardResponseDto.builder()
                .recentTransactions(List.of()).saleTransactions(List.of()).build());

        mockMvc.perform(as(ADMIN_TOKEN, DASHBOARD + "?userId=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Load trang chủ App thành công"))
                .andExpect(jsonPath("$.data.recentTransactions").isEmpty())
                .andExpect(jsonPath("$.data.biggestTransaction").doesNotExist());

        verify(transactionService).getDashboardOf(2L);
        verifyNoMoreInteractions(transactionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {VENDORS, DASHBOARD})
    void theUserIdIsRequiredNoDefaultToSomebodyElsesData(String path) throws Exception {
        mockMvc.perform(as(ADMIN_TOKEN, path))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Thiếu tham số bắt buộc: userId"));

        verifyNoInteractions(transactionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {VENDORS + "?userId=abc", DASHBOARD + "?userId=abc"})
    void aUserIdThatIsNotANumberIsA400(String path) throws Exception {
        mockMvc.perform(as(ADMIN_TOKEN, path)).andExpect(status().isBadRequest());

        verifyNoInteractions(transactionService);
    }

    @Test
    void anUnknownUserIsANotFoundForTheAdministrator() throws Exception {
        when(transactionService.getPopularVendorsOf(404L)).thenThrow(new UserNotFoundException("Không tìm thấy người dùng"));

        mockMvc.perform(as(ADMIN_TOKEN, VENDORS + "?userId=404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Không tìm thấy người dùng"));
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

        assertThrows(AccessDeniedException.class, () -> controller.getPopularVendors(2L));
        assertThrows(AccessDeniedException.class, () -> controller.getAppDashboard(2L));

        verifyNoInteractions(transactionService);
    }

    @Test
    void preAuthorizeAloneStopsAUserWhoseRoleOnlyLooksLikeAdmin() {
        signInWithRole("ROLE_ADMINISTRATOR");

        assertThrows(AccessDeniedException.class, () -> controller.getPopularVendors(2L));

        signInWithRole("ADMIN"); // without the ROLE_ prefix it is not the authority Spring checks for
        assertThrows(AccessDeniedException.class, () -> controller.getPopularVendors(2L));
    }

    @Test
    void preAuthorizeLetsAnAdministratorThrough() {
        signInWithRole("ROLE_ADMIN");
        when(transactionService.getPopularVendorsOf(2L)).thenReturn(List.of(vendor()));

        ResponseEntity<ApiResponse<List<PopularVendorResponseDto>>> response = controller.getPopularVendors(2L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Highlands", response.getBody().getData().get(0).getVendorName());
    }

    @Test
    void theWholeClassIsClosedToNonAdministratorsByTheAnnotation() {
        PreAuthorize annotation = AdminTransactionController.class.getAnnotation(PreAuthorize.class);

        assertNotNull(annotation, "@PreAuthorize must sit on the class so that no handler can be added without it");
        assertEquals(Roles.HAS_ADMIN, annotation.value());
        for (Method method : AdminTransactionController.class.getDeclaredMethods()) {
            PreAuthorize own = method.getAnnotation(PreAuthorize.class);
            assertTrue(own == null || Roles.HAS_ADMIN.equals(own.value()),
                    method.getName() + " must not loosen the class-level rule");
        }
    }
}
