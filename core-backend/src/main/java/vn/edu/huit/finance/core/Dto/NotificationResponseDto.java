package vn.edu.huit.finance.core.Dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import vn.edu.huit.finance.core.Entity.Notification;
import vn.edu.huit.finance.core.Entity.NotificationType;

import java.time.LocalDateTime;

@Data
@Builder
public class NotificationResponseDto {
    private Long id;
    private NotificationType type;
    private String message;
    // Named explicitly: Lombok's isRead() getter alone would make Jackson write "read".
    @JsonProperty("isRead")
    private boolean isRead;
    private LocalDateTime createdAt;
    /** The transaction the notification is about; it may have been deleted since. */
    private Long transactionId;
    /** The budget that raised the notification; it may have been deleted since. */
    private Long budgetId;
    /** The receipt the notification is about; it may have been deleted since. */
    private Long receiptId;

    public static NotificationResponseDto from(Notification notification) {
        return NotificationResponseDto.builder()
                .id(notification.getId())
                .type(notification.getType())
                .message(notification.getMessage())
                .isRead(notification.isRead())
                .createdAt(notification.getCreatedAt())
                .transactionId(notification.getTransactionId())
                .budgetId(notification.getBudgetId())
                .receiptId(notification.getReceiptId())
                .build();
    }
}
