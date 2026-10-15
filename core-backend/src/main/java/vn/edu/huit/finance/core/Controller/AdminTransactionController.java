package vn.edu.huit.finance.core.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.huit.finance.core.Dto.ApiResponse;
import vn.edu.huit.finance.core.Dto.AppDashboardResponseDto;
import vn.edu.huit.finance.core.Dto.PopularVendorResponseDto;
import vn.edu.huit.finance.core.Security.Roles;
import vn.edu.huit.finance.core.Service.TransactionService;

import java.util.List;

/**
 * Statistics of any user, for administrators. The user id is a request parameter on purpose, and only here. The class is
 * closed to everybody without ROLE_ADMIN twice: by the /api/admin/** rule of SecurityConfig and by @PreAuthorize, so
 * neither of them can be dropped by mistake without the other still holding.
 */
@RestController
@RequestMapping("/api/admin/transactions")
@PreAuthorize(Roles.HAS_ADMIN)
public class AdminTransactionController {

    private static final String MSG_VENDORS_OK = "Lấy danh sách độ phổ biến thành công";
    private static final String MSG_DASHBOARD_OK = "Load trang chủ App thành công";

    @Autowired
    private TransactionService transactionService;

    @GetMapping("/popular-vendors")
    public ResponseEntity<ApiResponse<List<PopularVendorResponseDto>>> getPopularVendors(@RequestParam Long userId) {
        return ApiResponses.respond(HttpStatus.OK, MSG_VENDORS_OK, transactionService.getPopularVendorsOf(userId));
    }

    @GetMapping("/app-dashboard")
    public ResponseEntity<ApiResponse<AppDashboardResponseDto>> getAppDashboard(@RequestParam Long userId) {
        return ApiResponses.respond(HttpStatus.OK, MSG_DASHBOARD_OK, transactionService.getDashboardOf(userId));
    }
}
