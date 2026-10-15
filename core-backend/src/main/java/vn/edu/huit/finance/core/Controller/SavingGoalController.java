package vn.edu.huit.finance.core.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.edu.huit.finance.core.Dto.ApiResponse;
import vn.edu.huit.finance.core.Dto.SavingGoalDto;
import vn.edu.huit.finance.core.Service.SavingGoalService;

import java.util.List;

/** The caller's saving goals. The owner is resolved from the SecurityContext inside SavingGoalService. */
@RestController
@RequestMapping("/api/savings")
public class SavingGoalController {

    private static final String MSG_OK = "OK";
    private static final String MSG_CREATED = "Thêm thành công";
    private static final String MSG_UPDATED = "Cập nhật thành công";
    private static final String MSG_DELETED = "Xóa thành công";
    private static final int CODE_OK = 200;

    @Autowired
    private SavingGoalService savingGoalService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SavingGoalDto>>> getMySavings() {
        return ResponseEntity.ok(ApiResponse.<List<SavingGoalDto>>builder()
                .code(CODE_OK).message(MSG_OK).data(savingGoalService.getMySavings()).build());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<String>> createSaving(@RequestBody SavingGoalDto dto) {
        savingGoalService.createSaving(dto);
        return ResponseEntity.ok(ApiResponse.<String>builder().code(CODE_OK).message(MSG_CREATED).build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> updateCurrentAmount(@PathVariable Long id, @RequestBody SavingGoalDto dto) {
        savingGoalService.updateCurrentAmount(id, dto.getCurrent());
        return ResponseEntity.ok(ApiResponse.<String>builder().code(CODE_OK).message(MSG_UPDATED).build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deleteSaving(@PathVariable Long id) {
        savingGoalService.deleteSaving(id);
        return ResponseEntity.ok(ApiResponse.<String>builder().code(CODE_OK).message(MSG_DELETED).build());
    }
}
