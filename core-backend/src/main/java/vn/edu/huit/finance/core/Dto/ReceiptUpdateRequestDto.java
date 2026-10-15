package vn.edu.huit.finance.core.Dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Body of PUT /api/receipts/{id}: the owner corrects the final fields of a receipt. A field that is left out keeps its
 * current value. There is deliberately no user, status or AI data here: the owner is always the caller, the status follows
 * from the edit, and what the AI answered can never be changed by a request.
 */
@Getter
@Setter
public class ReceiptUpdateRequestDto {

    private static final int VENDOR_NAME_MAX_LENGTH = 255;

    /** The version of the receipt the edit is based on (ReceiptResponseDto.version); an older one is answered 409. */
    @NotNull(message = "Thiếu phiên bản (version) của hóa đơn")
    private Long version;

    /** A blank name clears the vendor. */
    @Size(max = VENDOR_NAME_MAX_LENGTH, message = "Tên cửa hàng tối đa {max} ký tự")
    private String vendorName;

    @Positive(message = "Tổng tiền hóa đơn phải lớn hơn 0")
    private BigDecimal extractedTotal;

    private LocalDate extractedDate;

    // A rule across fields, written as a property of its own because the validation handler of GlobalExceptionHandler
    // only reads field errors.
    @JsonIgnore
    @AssertTrue(message = "Cần ít nhất một trường để sửa: vendorName, extractedTotal hoặc extractedDate")
    public boolean isAnyFieldGiven() {
        return vendorName != null || extractedTotal != null || extractedDate != null;
    }
}
