package vn.edu.huit.finance.core.Entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * One reading of a receipt by the AI: its answer exactly as the client sent it, and what was concluded from it. A receipt
 * keeps every reading it ever had, numbered from 1 in the order they were recorded; a retry adds one and changes no other.
 * The row is written once, when it is inserted ({@link Immutable}: no update of the entity reaches the database), and it
 * goes only together with its receipt (ON DELETE CASCADE). The final fields of the receipt are what the owner and the
 * administrators work with; nothing that happens to them afterwards (an edit, an approval) touches an attempt.
 * <p>
 * Not audited: the table is itself the insert-only history of the readings, and an audit copy would only store the
 * largest column of the receipt a second time.
 */
@Entity
@Immutable
@Table(name = "receipt_processing_attempts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_receipt_attempts_receipt_number", columnNames = {"receipt_id", "attempt_number"}),
        @UniqueConstraint(name = "uk_receipt_attempts_receipt_client_id", columnNames = {"receipt_id", "client_id"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReceiptProcessingAttempt {

    /** The number of the first reading of a receipt. */
    public static final int FIRST_ATTEMPT_NUMBER = 1;
    /** Width of the column behind {@link #status}. */
    public static final int STATUS_MAX_LENGTH = 20;
    /** Width of the column behind {@link #safeErrorMessage}. */
    public static final int SAFE_ERROR_MESSAGE_MAX_LENGTH = 255;

    private static final String MSG_BAD_NUMBER = "Số thứ tự lần đọc hóa đơn phải từ %d trở lên";
    private static final String MSG_NO_ANSWER = "Lần đọc hóa đơn phải có dữ liệu AI";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false, foreignKey = @ForeignKey(name = "fk_receipt_attempts_receipt"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Receipt receipt;

    /** {@link #FIRST_ATTEMPT_NUMBER} for the first reading of the receipt, one more for each later one; unique per receipt. */
    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    /** The name of a {@link ReceiptAttemptStatus}. */
    @Column(name = "status", nullable = false, length = STATUS_MAX_LENGTH)
    private String status;

    /** What the AI answered, exactly as it arrived (not trimmed, not re-serialised). Never part of an answer to the owner. */
    @Column(name = "ai_raw_data", columnDefinition = "TEXT", nullable = false)
    private String aiRawData;

    /** Why the reading FAILED: the name of a {@link ReceiptErrorCode}. Null for every other status. */
    @Column(name = "error_code", length = Receipt.ERROR_CODE_MAX_LENGTH)
    private String errorCode;

    /** The fixed text of {@link #errorCode} as it read when the attempt was recorded; never the message of an exception. */
    @Column(name = "safe_error_message", length = SAFE_ERROR_MESSAGE_MAX_LENGTH)
    private String safeErrorMessage;

    /**
     * The idempotency key of the retry that recorded this reading, unique per receipt, so that a repeat of that retry is
     * recognised. Null for the reading of a checkout, for a retry sent without one and for an imported reading.
     */
    @Column(name = "client_id", length = Transaction.CLIENT_ID_MAX_LENGTH)
    private String clientId;

    /** When the reading was recorded; for an imported one, when the receipt that held it was created. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** When the reading ended. Null only for a LEGACY attempt: when that reading ended was never recorded. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    private ReceiptProcessingAttempt(Receipt receipt, int attemptNumber, ReceiptAttemptStatus status, String aiRawData,
                                     ReceiptErrorCode failure, String clientId, LocalDateTime createdAt,
                                     LocalDateTime completedAt) {
        if (attemptNumber < FIRST_ATTEMPT_NUMBER) {
            throw new IllegalArgumentException(String.format(MSG_BAD_NUMBER, FIRST_ATTEMPT_NUMBER));
        }
        if (aiRawData == null) {
            throw new IllegalArgumentException(MSG_NO_ANSWER);
        }
        this.receipt = receipt;
        this.attemptNumber = attemptNumber;
        this.status = status.name();
        this.aiRawData = aiRawData;
        this.errorCode = failure != null ? failure.name() : null;
        this.safeErrorMessage = failure != null ? failure.getMessage() : null;
        this.clientId = clientId;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }

    /** A reading whose answer held a usable total, recorded at {@code endedAt}, the moment it ended. */
    public static ReceiptProcessingAttempt completed(Receipt receipt, int attemptNumber, String aiRawData, String clientId,
                                                     LocalDateTime endedAt) {
        return new ReceiptProcessingAttempt(receipt, attemptNumber, ReceiptAttemptStatus.COMPLETED, aiRawData, null,
                clientId, endedAt, endedAt);
    }

    /** A reading whose answer held no usable total, for {@code reason}, recorded at {@code endedAt}, the moment it ended. */
    public static ReceiptProcessingAttempt failed(Receipt receipt, int attemptNumber, String aiRawData, String clientId,
                                                  ReceiptErrorCode reason, LocalDateTime endedAt) {
        return new ReceiptProcessingAttempt(receipt, attemptNumber, ReceiptAttemptStatus.FAILED, aiRawData, reason,
                clientId, endedAt, endedAt);
    }

    /** An answer the receipt held before attempts existed, imported as it was; {@code recordedAt} is when the receipt was created. */
    public static ReceiptProcessingAttempt legacy(Receipt receipt, int attemptNumber, String aiRawData, LocalDateTime recordedAt) {
        return new ReceiptProcessingAttempt(receipt, attemptNumber, ReceiptAttemptStatus.LEGACY, aiRawData, null,
                null, recordedAt, null);
    }
}
