package vn.edu.huit.finance.core.Controller;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.edu.huit.finance.core.Dto.ApiResponse;
import vn.edu.huit.finance.core.Dto.AppDashboardResponseDto;
import vn.edu.huit.finance.core.Dto.PopularVendorResponseDto;
import vn.edu.huit.finance.core.Dto.TransactionFilterRequestDto;
import vn.edu.huit.finance.core.Dto.TransactionRequestDto;
import vn.edu.huit.finance.core.Dto.TransactionResponseDto;
import vn.edu.huit.finance.core.Dto.TransactionUpdateRequestDto;
import vn.edu.huit.finance.core.Service.TransactionService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The signed-in user's own transactions. No endpoint here reads a user id from the request: the owner is always the
 * caller, resolved from the SecurityContext inside TransactionService. Lookups across users live in AdminTransactionController.
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private static final String MSG_LIST_OK = "Lấy giao dịch thành công";
    private static final String MSG_CREATED = "Lưu giao dịch thành công!";
    private static final String MSG_UPDATED = "Cập nhật giao dịch thành công";
    private static final String MSG_DELETED = "Đã xóa giao dịch và hoàn tiền thành công!";
    private static final String MSG_FILTER_OK = "Lấy dữ liệu thành công";
    private static final String MSG_CANCELED = "Đã hủy hóa đơn thành công!";
    private static final String MSG_VENDORS_OK = "Lấy danh sách độ phổ biến thành công";
    private static final String MSG_DASHBOARD_OK = "Load trang chủ App thành công";

    @Autowired
    private TransactionService transactionService;

    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<List<TransactionResponseDto>>> myTransactions() {
        return ApiResponses.respond(HttpStatus.OK, MSG_LIST_OK, transactionService.getMyTransactions());
    }

    @PostMapping("/checkout")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createTransaction(
            @Valid @RequestBody TransactionRequestDto request) {
        return ApiResponses.respond(HttpStatus.OK, MSG_CREATED, transactionService.createMyTransaction(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TransactionResponseDto>> updateTransaction(
            @PathVariable Long id, @Valid @RequestBody TransactionUpdateRequestDto request) {
        return ApiResponses.respond(HttpStatus.OK, MSG_UPDATED, transactionService.updateTransaction(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTransaction(@PathVariable Long id) {
        transactionService.deleteTransaction(id);
        return ApiResponses.respond(HttpStatus.OK, MSG_DELETED, null);
    }

    @GetMapping("/filter")
    public ResponseEntity<ApiResponse<List<TransactionResponseDto>>> filterTransactions(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String vendorName,
            @RequestParam(defaultValue = TransactionFilterRequestDto.DEFAULT_SORT_BY) String sortBy,
            @RequestParam(defaultValue = TransactionFilterRequestDto.DEFAULT_SORT_DIRECTION) String sortDir) {
        TransactionFilterRequestDto filter = TransactionFilterRequestDto.builder()
                .categoryId(categoryId)
                .minPrice(minPrice)
                .maxPrice(maxPrice)
                .vendorName(vendorName)
                .sortBy(sortBy)
                .sortDirection(sortDir)
                .build();
        return ApiResponses.respond(HttpStatus.OK, MSG_FILTER_OK, transactionService.filterMyTransactions(filter));
    }

    @PutMapping("/cancel/{receiptId}")
    public ResponseEntity<ApiResponse<Void>> cancelReceipt(@PathVariable Long receiptId) {
        transactionService.cancelMyReceipt(receiptId);
        return ApiResponses.respond(HttpStatus.OK, MSG_CANCELED, null);
    }

    @GetMapping("/popular-vendors")
    public ResponseEntity<ApiResponse<List<PopularVendorResponseDto>>> getPopularVendors() {
        return ApiResponses.respond(HttpStatus.OK, MSG_VENDORS_OK, transactionService.getMyPopularVendors());
    }

    @GetMapping("/app-dashboard")
    public ResponseEntity<ApiResponse<AppDashboardResponseDto>> getAppDashboardData() {
        return ApiResponses.respond(HttpStatus.OK, MSG_DASHBOARD_OK, transactionService.getMyDashboard());
    }
}
