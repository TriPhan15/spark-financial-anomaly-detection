package vn.edu.huit.finance.core.Dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import vn.edu.huit.finance.core.Entity.Receipt;
import vn.edu.huit.finance.core.Entity.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class TransactionRequestDto {

    @NotNull(message = "Số tiền không được để trống")
    @Positive(message = "Số tiền giao dịch phải lớn hơn 0")
    private BigDecimal amount;

    private String note;

    @NotBlank(message = "Địa chỉ giao dịch không được để trống")
    private String location;

    @NotBlank(message = "Vui lòng chọn nguồn tiền")
    private String paymentMethod;

    @NotNull(message = "Ngày giao dịch không được để trống")
    private LocalDateTime transactionDate;

    private Long categoryId;

    private String categoryName;

    private Long receiptId;
    @JsonProperty("isExpense")
    private boolean isExpense;
    private Long campaignId;
    /** What the AI answered for the scanned receipt, as one JSON text; it is kept as the receipt's first attempt, never on the transaction. */
    @Size(max = Receipt.AI_RAW_DATA_MAX_LENGTH, message = "Dữ liệu AI của hóa đơn (rawReceiptData) tối đa {max} ký tự")
    private String rawReceiptData;
    private String imageUrl;
    private List<String> additionalImages;

    /**
     * Optional idempotency key, made up by the client for one attempt to save (a UUID will do) and sent again unchanged
     * when that attempt is retried. A second request with a key the user has already used is answered with the first
     * transaction instead of creating another one. Blank counts as absent.
     */
    @Size(max = Transaction.CLIENT_ID_MAX_LENGTH,
            message = "Mã định danh yêu cầu (clientId) tối đa " + Transaction.CLIENT_ID_MAX_LENGTH + " ký tự")
    private String clientId;
}
