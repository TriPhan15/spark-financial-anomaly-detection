package vn.edu.huit.finance.core.Controller;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.huit.finance.core.Config.SecurityConfig;
import vn.edu.huit.finance.core.Entity.Receipt;
import vn.edu.huit.finance.core.Exception.GlobalExceptionHandler;
import vn.edu.huit.finance.core.Repository.AppReviewRepository;
import vn.edu.huit.finance.core.Repository.CampaignRepository;
import vn.edu.huit.finance.core.Repository.CategoryRepository;
import vn.edu.huit.finance.core.Repository.ReceiptRepository;
import vn.edu.huit.finance.core.Repository.SubscriptionPlanRepository;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CustomUserDetailsService;
import vn.edu.huit.finance.core.Security.JwtAuthFilter;
import vn.edu.huit.finance.core.Security.JwtUtil;
import vn.edu.huit.finance.core.Security.Roles;
import vn.edu.huit.finance.core.Service.AdminStatisticsService;
import vn.edu.huit.finance.core.Service.AppReviewService;
import vn.edu.huit.finance.core.Service.AuditLogQueryService;
import vn.edu.huit.finance.core.Service.BackupService;
import vn.edu.huit.finance.core.Service.CategoryService;
import vn.edu.huit.finance.core.Service.LoginAttemptService;
import vn.edu.huit.finance.core.Service.ReceiptProcessingService;
import vn.edu.huit.finance.core.Service.SubscriptionPlanService;
import vn.edu.huit.finance.core.Service.TokenBlacklistService;
import vn.edu.huit.finance.core.Service.TransactionService;
import vn.edu.huit.finance.core.Service.UserService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin side of a receipt, as it was before the lifecycle. Approving and rejecting are for administrators only: the
 * two services behind the handlers take no user and trust their caller, so this is where "only an administrator" is
 * proven, through the filter chain of the admin pages (the session of the form login) and through @PreAuthorize alone.
 * And the list of receipts still shows the three badges it always showed, whatever lifecycle status a row holds.
 */
@WebMvcTest(AdminController.class)
@ContextConfiguration(classes = {
        AdminController.class, GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthFilter.class})
class AdminReceiptReviewTest {

    private static final String APPROVE = "/admin/receipts/approve/7";
    private static final String REJECT = "/admin/receipts/reject/7";
    private static final String RECEIPT_LIST = "/admin/receipts";
    private static final String BADGE_SUCCESS = "bg-success rounded-pill px-3 py-2 w-100\">Thành công</span>";
    private static final String BADGE_FAILED = "bg-danger rounded-pill px-3 py-2 w-100\">Lỗi AI</span>";
    private static final String BADGE_IN_PROGRESS = "bg-warning text-dark rounded-pill px-3 py-2 w-100\">Đang xử lý</span>";

    @Autowired private MockMvc mockMvc;
    @Autowired private AdminController controller;

    @MockBean private TransactionService transactionService;
    @MockBean private ReceiptProcessingService receiptProcessingService;
    @MockBean private AdminStatisticsService adminStatisticsService;
    @MockBean private UserRepository userRepo;
    @MockBean private CategoryRepository categoryRepo;
    @MockBean private CampaignRepository campaignRepo;
    @MockBean private ReceiptRepository receiptRepo;
    @MockBean private BackupService backupService;
    @MockBean private UserService userService;
    @MockBean private EntityManager entityManager;
    @MockBean private SubscriptionPlanRepository subscriptionPlanRepository;
    @MockBean private SubscriptionPlanService subscriptionPlanService;
    @MockBean private CategoryService categoryService;
    @MockBean private AppReviewService appReviewService;
    @MockBean private AuditLogQueryService auditLogQueryService;
    @MockBean private LoginAttemptService loginAttemptService;
    @MockBean private AppReviewRepository appReviewRepository;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private CustomUserDetailsService userDetailsService;
    @MockBean private TokenBlacklistService tokenBlacklistService;

    @AfterEach
    void clearTheSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication someoneWithRole(String role) {
        return new UsernamePasswordAuthenticationToken("someone", null, AuthorityUtils.createAuthorityList(role));
    }

    /** The session the form login of the admin pages leaves behind for a user with this role. */
    private static MockHttpSession sessionOf(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(someoneWithRole(role)));
        return session;
    }

    // ---------------------------------------------------------------------------------- who may call, through the filter chain

    @ParameterizedTest
    @ValueSource(strings = {APPROVE, REJECT})
    void withoutAnAdminSessionTheRequestIsSentToTheLoginPageAndNoReceiptIsTouched(String url) throws Exception {
        mockMvc.perform(post(url))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/admin/login"));

        verifyNoInteractions(transactionService, receiptProcessingService);
    }

    @ParameterizedTest
    @ValueSource(strings = {APPROVE, REJECT})
    void anOrdinaryUserIsForbiddenAndNoReceiptIsTouched(String url) throws Exception {
        mockMvc.perform(post(url).session(sessionOf(Roles.USER))).andExpect(status().isForbidden());

        verifyNoInteractions(transactionService, receiptProcessingService);
    }

    @Test
    void anAdministratorApprovesAReceiptThroughTheServiceThatReconcilesTheWallet() throws Exception {
        mockMvc.perform(post(APPROVE).session(sessionOf(Roles.ADMIN))).andExpect(redirectedUrl("/admin/receipts"));

        verify(transactionService).approveReceipt(7L);
        verifyNoInteractions(receiptProcessingService);
    }

    @Test
    void anAdministratorRejectsAReceiptThroughTheReceiptService() throws Exception {
        mockMvc.perform(post(REJECT).session(sessionOf(Roles.ADMIN))).andExpect(redirectedUrl("/admin/receipts"));

        verify(receiptProcessingService).rejectReceipt(7L);
        verifyNoInteractions(transactionService);
    }

    // ------------------------------------------------------------------------------------------ the list of receipts

    private static Receipt receipt(long id, String status) {
        Receipt receipt = new Receipt();
        receipt.setId(id);
        receipt.setStatus(status);
        receipt.setVendorName("Cửa hàng " + id);
        receipt.setExtractedTotal(new BigDecimal("245000.00"));
        return receipt;
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }

    @Test
    void theListShowsAReceiptThatWasReadAsSuccessWhateverBecameOfItSinceAndKeepsItsOtherTwoBadges() throws Exception {
        when(userRepo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(receiptRepo.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                receipt(1, "COMPLETED_RAW"), receipt(2, "EDITED"), receipt(3, "APPROVED"), receipt(4, "SUCCESS"),
                receipt(5, "FAILED"), receipt(6, "WAITING_FOR_REVIEW"), receipt(7, "PENDING"))));

        String page = mockMvc.perform(get(RECEIPT_LIST).session(sessionOf(Roles.ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // Before the lifecycle the four read receipts would all have been stored as SUCCESS; the page must not start
        // showing three of them as "Đang xử lý" because their status is spelled out further now.
        assertEquals(4, occurrences(page, BADGE_SUCCESS));
        assertEquals(1, occurrences(page, BADGE_FAILED));
        assertEquals(2, occurrences(page, BADGE_IN_PROGRESS));
    }

    // ------------------------------------------------------------------------------------ @PreAuthorize on its own

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_ADMINISTRATOR", "ADMIN"})
    void preAuthorizeAloneStopsEverybodyWhoIsNotAnAdministratorBeforeTheHandlerRuns(String role) {
        // Calling the bean directly skips the /admin/** rule of SecurityConfig, so only the annotation can say no here.
        SecurityContextHolder.getContext().setAuthentication(someoneWithRole(role));

        assertThrows(AccessDeniedException.class, () -> controller.approveReceipt(7L));
        assertThrows(AccessDeniedException.class, () -> controller.rejectReceipt(7L));

        verifyNoInteractions(transactionService, receiptProcessingService);
    }

    @Test
    void preAuthorizeLetsAnAdministratorThrough() {
        SecurityContextHolder.getContext().setAuthentication(someoneWithRole(Roles.ADMIN));

        assertEquals("redirect:/admin/receipts", controller.approveReceipt(7L));
        assertEquals("redirect:/admin/receipts", controller.rejectReceipt(7L));

        verify(transactionService).approveReceipt(7L);
        verify(receiptProcessingService).rejectReceipt(7L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"approveReceipt", "rejectReceipt"})
    void bothHandlersCarryTheAdministratorRule(String handler) throws NoSuchMethodException {
        PreAuthorize rule = AdminController.class.getMethod(handler, Long.class).getAnnotation(PreAuthorize.class);

        assertNotNull(rule, handler + " must be closed by @PreAuthorize as well as by the URL rule");
        assertEquals(Roles.HAS_ADMIN, rule.value());
    }
}
