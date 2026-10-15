package vn.edu.huit.finance.core.Dto;

import lombok.Builder;
import lombok.Data;
import vn.edu.huit.finance.core.Entity.Receipt;
import vn.edu.huit.finance.core.Entity.ReceiptErrorCode;
import vn.edu.huit.finance.core.Entity.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A receipt as its owner sees it: the final fields, where it stands in the lifecycle and the version an edit has to be
 * based on. Deliberately without the readings of the AI (the receipt's attempts and their raw answers), which no client
 * needs: only administrators get those, through ReceiptAttemptResponseDto.
 */
@Data
@Builder
public class ReceiptResponseDto {
    private Long id;
    /** The transaction that carries the receipt. */
    private Long transactionId;
    /** The name of the receipt's {@code ReceiptStatus}; a row that predates the lifecycle is shown under its current name. */
    private String status;
    /** Sent back with an edit (ReceiptUpdateRequestDto.version): an edit based on an older version is answered 409. */
    private Long version;
    private String vendorName;
    private BigDecimal extractedTotal;
    private LocalDate extractedDate;
    private String imageUrl;
    /** Why the receipt is FAILED (the name of a {@code ReceiptErrorCode}); null in every other status. */
    private String errorCode;
    /** The fixed text that goes with {@link #errorCode}; never the message of an exception. */
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** The only mapping from the entity. The transaction is read for its id alone, so nothing is loaded for it. */
    public static ReceiptResponseDto from(Receipt receipt) {
        Transaction carrier = receipt.getTransaction();
        return ReceiptResponseDto.builder()
                .id(receipt.getId())
                .transactionId(carrier != null ? carrier.getId() : null)
                .status(receipt.lifecycleStatus().map(Enum::name).orElse(receipt.getStatus()))
                .version(receipt.getVersion())
                .vendorName(receipt.getVendorName())
                .extractedTotal(receipt.getExtractedTotal())
                .extractedDate(receipt.getExtractedDate())
                .imageUrl(receipt.getImageUrl())
                .errorCode(receipt.getErrorCode())
                .errorMessage(ReceiptErrorCode.fromStored(receipt.getErrorCode())
                        .map(ReceiptErrorCode::getMessage)
                        .orElse(null))
                .createdAt(receipt.getCreatedAt())
                .updatedAt(receipt.getUpdatedAt())
                .build();
    }
}
