package vn.edu.huit.finance.core.Controller;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.edu.huit.finance.core.Dto.ApiResponse;
import vn.edu.huit.finance.core.Dto.ReceiptResponseDto;
import vn.edu.huit.finance.core.Dto.ReceiptRetryRequestDto;
import vn.edu.huit.finance.core.Dto.ReceiptUpdateRequestDto;
import vn.edu.huit.finance.core.Service.ReceiptProcessingService;

import java.math.BigDecimal;

/**
 * The signed-in user's own receipts. No endpoint here reads a user id from the request: the caller is resolved from the
 * SecurityContext inside ReceiptProcessingService, which answers 403 for a receipt that belongs to somebody else. What the
 * AI answered for a receipt is never part of an answer here.
 */
@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {

    private static final String MSG_FEEDBACK_SENT = "Đã gửi báo cáo cho Admin duyệt lại hóa đơn!";
    private static final String MSG_RECEIPT_OK = "Lấy thông tin hóa đơn thành công";
    private static final String MSG_UPDATED = "Cập nhật hóa đơn thành công";
    private static final String MSG_RETRIED = "Đã ghi nhận kết quả đọc lại hóa đơn";

    @Autowired
    private ReceiptProcessingService receiptProcessingService;

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ReceiptResponseDto>> getReceipt(@PathVariable Long id) {
        return ApiResponses.respond(HttpStatus.OK, MSG_RECEIPT_OK, receiptProcessingService.getMyReceipt(id));
    }

    // Two segments with a literal first one; /{id} has one and /{id}/retry ends in a literal, so none takes another's requests.
    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<ApiResponse<ReceiptResponseDto>> getReceiptOfTransaction(@PathVariable Long transactionId) {
        return ApiResponses.respond(HttpStatus.OK, MSG_RECEIPT_OK,
                receiptProcessingService.getMyReceiptOfTransaction(transactionId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ReceiptResponseDto>> updateReceipt(
            @PathVariable Long id, @Valid @RequestBody ReceiptUpdateRequestDto request) {
        return ApiResponses.respond(HttpStatus.OK, MSG_UPDATED, receiptProcessingService.editMyReceipt(id, request));
    }

    /**
     * Records a new reading of a FAILED receipt (its earlier readings are kept as they were). The answer says what became
     * of the receipt: COMPLETED_RAW, or FAILED again with the reason when the new reading holds no total.
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResponse<ReceiptResponseDto>> retryReceipt(
            @PathVariable Long id, @Valid @RequestBody ReceiptRetryRequestDto request) {
        return ApiResponses.respond(HttpStatus.OK, MSG_RETRIED, receiptProcessingService.retryMyReceipt(id, request));
    }

    @PostMapping("/feedback/{receiptId}")
    public ResponseEntity<ApiResponse<Void>> submitReceiptFeedback(
            @PathVariable Long receiptId,
            @RequestParam BigDecimal manualAmount) {
        receiptProcessingService.submitFeedback(receiptId, manualAmount);
        return ApiResponses.respond(HttpStatus.OK, MSG_FEEDBACK_SENT, null);
    }
}
