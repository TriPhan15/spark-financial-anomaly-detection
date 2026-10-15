package vn.edu.huit.finance.core.Controller;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.edu.huit.finance.core.Dto.ApiResponse;
import vn.edu.huit.finance.core.Dto.BudgetRequestDto;
import vn.edu.huit.finance.core.Dto.BudgetResponseDto;
import vn.edu.huit.finance.core.Service.BudgetService;

import java.util.List;

/** CRUD of the caller's own budgets. The owner is taken from the JWT inside BudgetService; nothing here reads a user id. */
@RestController
@RequestMapping("/api/budgets")
public class BudgetController {

    private static final String MSG_LIST_OK = "Lấy danh sách ngân sách thành công";
    private static final String MSG_GET_OK = "Lấy ngân sách thành công";
    private static final String MSG_CREATED = "Tạo ngân sách thành công";
    private static final String MSG_UPDATED = "Cập nhật ngân sách thành công";
    private static final String MSG_DELETED = "Xóa ngân sách thành công";

    @Autowired
    private BudgetService budgetService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<BudgetResponseDto>>> getMyBudgets() {
        return respond(HttpStatus.OK, MSG_LIST_OK, budgetService.getMyBudgets());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<BudgetResponseDto>> getMyBudget(@PathVariable Long id) {
        return respond(HttpStatus.OK, MSG_GET_OK, budgetService.getMyBudget(id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BudgetResponseDto>> createBudget(@Valid @RequestBody BudgetRequestDto request) {
        return respond(HttpStatus.CREATED, MSG_CREATED, budgetService.createBudget(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<BudgetResponseDto>> updateBudget(
            @PathVariable Long id, @Valid @RequestBody BudgetRequestDto request) {
        return respond(HttpStatus.OK, MSG_UPDATED, budgetService.updateBudget(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteBudget(@PathVariable Long id) {
        budgetService.deleteBudget(id);
        return respond(HttpStatus.OK, MSG_DELETED, null);
    }

    private static <T> ResponseEntity<ApiResponse<T>> respond(HttpStatus status, String message, T data) {
        return ResponseEntity.status(status).body(ApiResponse.<T>builder()
                .code(status.value())
                .message(message)
                .data(data)
                .build());
    }
}
