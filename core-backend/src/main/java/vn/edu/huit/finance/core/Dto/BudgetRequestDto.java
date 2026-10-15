package vn.edu.huit.finance.core.Dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import vn.edu.huit.finance.core.Entity.Budget;
import vn.edu.huit.finance.core.Entity.BudgetPeriodType;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Body of POST and PUT /api/budgets. There is deliberately no user field: the owner is always the caller. */
@Getter
@Setter
public class BudgetRequestDto {

    /** Null means the budget applies to every category. */
    @Positive(message = "Mã danh mục không hợp lệ")
    private Long categoryId;

    @NotNull(message = "Số tiền ngân sách không được để trống")
    @DecimalMin(value = "0", inclusive = false, message = "Số tiền ngân sách phải lớn hơn 0")
    @Digits(integer = Budget.AMOUNT_PRECISION - Budget.AMOUNT_SCALE, fraction = Budget.AMOUNT_SCALE,
            message = "Số tiền ngân sách chỉ được có tối đa {integer} chữ số nguyên và {fraction} chữ số thập phân")
    private BigDecimal amount;

    @NotNull(message = "Loại kỳ hạn không được để trống")
    private BudgetPeriodType periodType;

    @NotNull(message = "Ngày bắt đầu không được để trống")
    private LocalDate startDate;

    @NotNull(message = "Ngày kết thúc không được để trống")
    private LocalDate endDate;

    // The two rules below are property-level (not a class-level constraint) so that GlobalExceptionHandler, which
    // reads field errors, reports their messages. Missing dates are already reported by the @NotNull rules above.

    @JsonIgnore
    @AssertTrue(message = "Ngày kết thúc không được trước ngày bắt đầu")
    public boolean isEndDateNotBeforeStartDate() {
        return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }

    @JsonIgnore
    @AssertTrue(message = "Khoảng thời gian vượt quá độ dài của kỳ hạn đã chọn")
    public boolean isRangeWithinPeriodType() {
        return periodType == null || startDate == null || endDate == null
                || endDate.isBefore(startDate)
                || periodType.allowsRange(startDate, endDate);
    }
}
