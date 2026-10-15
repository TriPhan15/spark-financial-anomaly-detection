package vn.edu.huit.finance.core.Controller;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.huit.finance.core.Dto.AuditLogFilterRequestDto;
import vn.edu.huit.finance.core.Dto.AuditLogPageResponseDto;
import vn.edu.huit.finance.core.Dto.AuditLogResponseDto;
import vn.edu.huit.finance.core.Dto.AuditSummaryRowDto;
import vn.edu.huit.finance.core.Entity.ActionLog;
import vn.edu.huit.finance.core.Repository.AppReviewRepository;
import vn.edu.huit.finance.core.Repository.CampaignRepository;
import vn.edu.huit.finance.core.Repository.CategoryRepository;
import vn.edu.huit.finance.core.Repository.ReceiptRepository;
import vn.edu.huit.finance.core.Repository.SubscriptionPlanRepository;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Service.AdminStatisticsService;
import vn.edu.huit.finance.core.Service.AppReviewService;
import vn.edu.huit.finance.core.Service.AuditLogQueryService;
import vn.edu.huit.finance.core.Service.BackupService;
import vn.edu.huit.finance.core.Service.CategoryService;
import vn.edu.huit.finance.core.Service.LoginAttemptService;
import vn.edu.huit.finance.core.Service.ReceiptProcessingService;
import vn.edu.huit.finance.core.Service.SubscriptionPlanService;
import vn.edu.huit.finance.core.Service.TransactionService;
import vn.edu.huit.finance.core.Service.UserService;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit log page of the admin site, rendered by Thymeleaf from the DTOs of AuditLogQueryService: the two tabs of the
 * newest rows (legacy rows under today's names), the summary, and the filtered, paged search. Everything a row holds is
 * escaped. Security is left out (addFilters = false): AdminAuditLogControllerTest and AccessDeniedAuditTest cover it.
 */
@WebMvcTest(AdminController.class)
@ContextConfiguration(classes = AdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminAuditLogPageTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 1, 8, 30, 15);

    @Autowired private MockMvc mockMvc;

    @MockBean private AdminStatisticsService adminStatisticsService;
    @MockBean private ReceiptProcessingService receiptProcessingService;
    @MockBean private UserRepository userRepo;
    @MockBean private CategoryRepository categoryRepo;
    @MockBean private CampaignRepository campaignRepo;
    @MockBean private ReceiptRepository receiptRepo;
    @MockBean private BackupService backupService;
    @MockBean private UserService userService;
    @MockBean private TransactionService transactionService;
    @MockBean private EntityManager entityManager;
    @MockBean private SubscriptionPlanRepository subscriptionPlanRepository;
    @MockBean private SubscriptionPlanService subscriptionPlanService;
    @MockBean private CategoryService categoryService;
    @MockBean private AppReviewService appReviewService;
    @MockBean private AuditLogQueryService auditLogQueryService;
    @MockBean private LoginAttemptService loginAttemptService;
    @MockBean private AppReviewRepository appReviewRepository;

    private static AuditLogResponseDto dto(ActionLog row) {
        return AuditLogResponseDto.from(row);
    }

    @BeforeEach
    void setUp() {
        // A row an older build wrote (legacy code, no target or result columns) and one of today's, with a hostile text.
        AuditLogResponseDto legacyPlan = dto(ActionLog.builder().id(1L).userEmail("root@example.com").action("CREATE_PLAN")
                .description("Tạo gói hội viên mới: VIP").timestamp(AT.minusDays(40)).build());
        AuditLogResponseDto deleted = dto(ActionLog.builder().id(2L).userId(7L).userEmail("alice@example.com")
                .action("TRANSACTION_DELETED").targetType("TRANSACTION").targetId(42L).result("SUCCESS")
                .description("<script>alert('x')</script>").timestamp(AT).build());
        AuditLogResponseDto lockout = dto(ActionLog.builder().id(3L).userEmail("mallory@example.com").action("LOGIN_LOCKED")
                .ipAddress("203.0.113.9").description("Khóa đăng nhập").timestamp(AT).build());
        AuditLogResponseDto denied = dto(ActionLog.builder().id(4L).userId(7L).userEmail("alice@example.com")
                .action("AUTH_ACCESS_DENIED").result("FAILURE").ipAddress("203.0.113.7")
                .description("Quy tắc phân quyền theo URL: GET /api/admin/audit-logs").timestamp(AT).build());
        when(auditLogQueryService.latestOtherEvents()).thenReturn(List.of(deleted, legacyPlan));
        when(auditLogQueryService.latestSecurityEvents()).thenReturn(List.of(denied, lockout));
        when(auditLogQueryService.summary()).thenReturn(List.of(AuditSummaryRowDto.builder()
                .actorEmail("mallory@example.com").action("AUTH_LOGIN_FAILED").actionLabel("Đăng nhập thất bại")
                .targetLabel("Tài khoản").total(12).lastAt(AT).build()));
        when(auditLogQueryService.search(any())).thenReturn(AuditLogPageResponseDto.builder()
                .content(List.of(deleted)).page(1).size(20).totalElements(41).totalPages(3).hasNext(true).hasPrevious(true)
                .build());
    }

    @Test
    void theTabsShowTheNewestRows_legacyRowsUnderTodaysNames_andEveryTextEscaped() throws Exception {
        mockMvc.perform(get("/admin/auditlog"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Tạo gói hội viên"),
                        containsString("Gói hội viên"),
                        containsString("Giao dịch #42"),
                        containsString("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;"),
                        not(containsString("<script>alert('x')</script>")),
                        containsString("Bị Khóa"),
                        containsString("Truy cập bị từ chối"),
                        containsString("GET /api/admin/audit-logs"),
                        containsString("mallory@example.com"),
                        containsString("Đăng nhập thất bại"))));
    }

    @Test
    void aLockoutStillOffersToLiftIt_withTheEmailAndAddressItWasRecordedWith() throws Exception {
        mockMvc.perform(get("/admin/auditlog"))
                .andExpect(content().string(containsString(
                        "handleUnlock(&quot;mallory@example.com&quot;, &quot;203.0.113.9&quot;)")));
    }

    @Test
    void theSearchPassesTheFilterOn_andItsPagesKeepTheFilter() throws Exception {
        mockMvc.perform(get("/admin/auditlog").param("action", "TRANSACTION_DELETED").param("userId", "7")
                        .param("size", "20").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("data-open=\"true\""),
                        containsString("tổng <strong>41</strong> dòng"),
                        containsString("action=TRANSACTION_DELETED&amp;userId=7"),
                        containsString("page=0"),
                        containsString("page=2"))));

        ArgumentCaptor<AuditLogFilterRequestDto> filter = ArgumentCaptor.forClass(AuditLogFilterRequestDto.class);
        verify(auditLogQueryService).search(filter.capture());
        assertEquals("TRANSACTION_DELETED", filter.getValue().getAction());
        assertEquals(7L, filter.getValue().getUserId());
        assertEquals(20, filter.getValue().getSize());
    }

    @Test
    void withoutAFilterThePageOpensOnItsFirstTab() throws Exception {
        mockMvc.perform(get("/admin/auditlog"))
                .andExpect(content().string(containsString("data-open=\"false\"")));
    }

    @Test
    void aFilterThatIsNotValidIsExplainedOnThePage_withoutSearchingAndWithoutClassNames() throws Exception {
        mockMvc.perform(get("/admin/auditlog").param("userId", "abc").param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Giá trị của userId không hợp lệ"),
                        containsString("Số dòng mỗi trang phải từ 1 đến 100"),
                        not(containsString("java.lang")),
                        containsString("data-open=\"true\""))));

        verify(auditLogQueryService, never()).search(any());
    }
}
