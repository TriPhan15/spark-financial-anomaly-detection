package vn.edu.huit.finance.core.Controller;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.huit.finance.core.Dto.AdminDashboardSummaryDto;
import vn.edu.huit.finance.core.Dto.LowBalanceUserDto;
import vn.edu.huit.finance.core.Dto.MonthlyTransactionCountDto;
import vn.edu.huit.finance.core.Dto.ReceiptStatusCountDto;
import vn.edu.huit.finance.core.Dto.TopSpenderDto;
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

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin dashboard page, rendered by Thymeleaf from the cached DTOs of AdminStatisticsService instead of rows and
 * entities. Security is left out (addFilters = false): the /admin/** rule of SecurityConfig is not what is under test.
 */
@WebMvcTest(AdminController.class)
@ContextConfiguration(classes = AdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminDashboardPageTest {

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

    @Test
    void theDashboardShowsTheCachedFiguresOfTheCurrentMonth() throws Exception {
        YearMonth now = YearMonth.now();
        when(adminStatisticsService.getDashboardSummary(now, TransactionService.LOW_BALANCE_THRESHOLD))
                .thenReturn(new AdminDashboardSummaryDto(new BigDecimal("1250000.50"), 42, 17,
                        List.of(new LowBalanceUserDto(7L, "an_low", "0900000007", new BigDecimal("12000.00"))),
                        List.of(new ReceiptStatusCountDto("SUCCESS", 5), new ReceiptStatusCountDto("FAILED", 2),
                                new ReceiptStatusCountDto("WAITING_FOR_REVIEW", 1))));
        when(adminStatisticsService.getTopSpenders(0, 5))
                .thenReturn(List.of(new TopSpenderDto("binh_top", new BigDecimal("900000.00"))));
        when(adminStatisticsService.getMonthlyTransactionCounts(now, 6))
                .thenReturn(List.of(new MonthlyTransactionCountDto(now.minusMonths(1), 3),
                        new MonthlyTransactionCountDto(now, 17)));
        when(userRepo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        // The charts are inlined into JavaScript, where Thymeleaf writes every non-ASCII character as a \\u escape.
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("1.250.000 VNĐ"),
                        containsString("binh_top"),
                        containsString("900.000 VNĐ"),
                        containsString("an_low"),
                        containsString("0900000007"),
                        containsString("12.000 VNĐ"),
                        containsString("const labelsReal = [\"Th\\u00E1ng " + now.minusMonths(1).getMonthValue()
                                + "\",\"Th\\u00E1ng " + now.getMonthValue() + "\"];"),
                        containsString("const dataReal = [3,17];"),
                        containsString("const aiLabelsReal = [\"Th\\u00E0nh c\\u00F4ng\",\"L\\u1ED7i AI\",\"\\u0110ang x\\u1EED l\\u00FD\"];"),
                        containsString("const aiDataReal = [5,2,1];"))));

        verify(adminStatisticsService).getDashboardSummary(now, TransactionService.LOW_BALANCE_THRESHOLD);
        verify(adminStatisticsService).getTopSpenders(0, 5);
        verify(adminStatisticsService).getMonthlyTransactionCounts(now, 6);
    }

    @Test
    void rejectingAReceiptGoesThroughTheServiceSoTheCachesAreEvictedAfterItsCommit() throws Exception {
        mockMvc.perform(post("/admin/receipts/reject/{id}", 7L))
                .andExpect(redirectedUrl("/admin/receipts"));

        verify(receiptProcessingService).rejectReceipt(7L);
    }
}
