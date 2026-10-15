package vn.edu.huit.finance.core.Dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.edu.huit.finance.core.Entity.Receipt;
import vn.edu.huit.finance.core.Entity.Transaction;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Part of the cached home page (AppDashboardResponseDto), so it must read back from JSON too. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponseDto {
    private Long id;
    private String title;
    private BigDecimal amount;
    private LocalDateTime date;
    private String category;
    @JsonProperty("isExpense")
    private boolean isExpense;
    private String imageUrl;
    /**
     * The status of the attached receipt under the name the mobile app knows ({@code ReceiptStatus.legacyName}: a receipt
     * that was read is SUCCESS, whether or not it was edited or approved since); null without a receipt. The lifecycle
     * status itself is part of ReceiptResponseDto.
     */
    private String receiptStatus;

    /** The title is the note when there is one, otherwise the name of the category. */
    public static TransactionResponseDto from(Transaction transaction) {
        String note = transaction.getNote();
        String title = (note != null && !note.isBlank()) ? note : transaction.getCategoryName();
        Receipt receipt = transaction.getReceipt();
        String imageUrl = (receipt != null) ? receipt.getImageUrl() : null;
        String receiptStatus = (receipt != null) ? receipt.getLegacyStatus() : null;

        return TransactionResponseDto.builder()
                .id(transaction.getId())
                .title(title)
                .amount(transaction.getAmount())
                .date(transaction.getTransactionDate())
                .category(transaction.getCategoryName())
                .isExpense(transaction.isExpense())
                .imageUrl(imageUrl)
                .receiptStatus(receiptStatus)
                .build();
    }
}