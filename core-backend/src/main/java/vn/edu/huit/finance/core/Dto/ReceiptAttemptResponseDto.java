package vn.edu.huit.finance.core.Dto;

import lombok.Builder;
import lombok.Data;
import vn.edu.huit.finance.core.Entity.ReceiptProcessingAttempt;

import java.time.LocalDateTime;

/**
 * One reading of a receipt as an administrator sees it (GET /api/admin/receipts/{id}/attempts). Unlike ReceiptResponseDto,
 * which is what the owner gets, it carries the answer of the AI: never return it from an endpoint that is not for
 * administrators only.
 */
@Data
@Builder
public class ReceiptAttemptResponseDto {
    private Long id;
    private Long receiptId;
    private Integer attemptNumber;
    /** The name of the attempt's {@code ReceiptAttemptStatus}: COMPLETED, FAILED or LEGACY. */
    private String status;
    /** What the AI answered, exactly as it was stored. */
    private String aiRawData;
    private String errorCode;
    private String safeErrorMessage;
    /** The idempotency key of the retry that recorded the reading, if it sent one. */
    private String clientId;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    /** The only mapping from the entity. The receipt is read for its id alone, which loads nothing. */
    public static ReceiptAttemptResponseDto from(ReceiptProcessingAttempt attempt) {
        return ReceiptAttemptResponseDto.builder()
                .id(attempt.getId())
                .receiptId(attempt.getReceipt().getId())
                .attemptNumber(attempt.getAttemptNumber())
                .status(attempt.getStatus())
                .aiRawData(attempt.getAiRawData())
                .errorCode(attempt.getErrorCode())
                .safeErrorMessage(attempt.getSafeErrorMessage())
                .clientId(attempt.getClientId())
                .createdAt(attempt.getCreatedAt())
                .completedAt(attempt.getCompletedAt())
                .build();
    }
}
