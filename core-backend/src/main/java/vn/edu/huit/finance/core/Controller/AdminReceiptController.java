package vn.edu.huit.finance.core.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.huit.finance.core.Dto.ApiResponse;
import vn.edu.huit.finance.core.Dto.ReceiptAttemptResponseDto;
import vn.edu.huit.finance.core.Security.Roles;
import vn.edu.huit.finance.core.Service.ReceiptProcessingService;

import java.util.List;

/**
 * Receipts as administrators see them: every reading of a receipt, with what the AI answered, which no endpoint of the
 * owner returns. The class is closed to everybody without ROLE_ADMIN twice, by the /api/admin/** rule of SecurityConfig
 * and by @PreAuthorize, so neither of them can be dropped by mistake without the other still holding.
 */
@RestController
@RequestMapping("/api/admin/receipts")
@PreAuthorize(Roles.HAS_ADMIN)
public class AdminReceiptController {

    private static final String MSG_ATTEMPTS_OK = "Lấy lịch sử các lần đọc hóa đơn thành công";

    @Autowired
    private ReceiptProcessingService receiptProcessingService;

    @GetMapping("/{id}/attempts")
    public ResponseEntity<ApiResponse<List<ReceiptAttemptResponseDto>>> getAttempts(@PathVariable Long id) {
        return ApiResponses.respond(HttpStatus.OK, MSG_ATTEMPTS_OK, receiptProcessingService.getAttemptsOf(id));
    }
}
