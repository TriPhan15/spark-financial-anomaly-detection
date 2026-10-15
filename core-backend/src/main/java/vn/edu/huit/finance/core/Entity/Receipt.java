package vn.edu.huit.finance.core.Entity;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A scanned receipt. Two kinds of data belong to it and never mix: what the AI answered, kept per reading in its
 * {@link ReceiptProcessingAttempt}s (written once each, never changed, never removed by a retry), and the final fields
 * ({@link #vendorName}, {@link #extractedTotal}, {@link #extractedDate}), which start as what the latest reading found and
 * are what the owner corrects and an administrator approves. The status only changes through {@link #moveTo}, so no
 * service can make a move the lifecycle does not know.
 */
@Audited
@Entity
@Table(name = "receipts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Receipt extends BaseEntity {

    /** Longest AI answer a receipt keeps (characters); the requests that carry one refuse a longer one. */
    public static final int AI_RAW_DATA_MAX_LENGTH = 32768;
    /** Width of the column behind {@link #errorCode}. */
    public static final int ERROR_CODE_MAX_LENGTH = 40;

    private static final String MSG_MOVE_NOT_ALLOWED = "Không thể chuyển hóa đơn từ trạng thái %s sang trạng thái %s";
    private static final String MSG_NOT_A_NEW_RECEIPT = "Lần đọc đầu tiên chỉ được ghi cùng với hóa đơn mới";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String vendorName;

    private BigDecimal extractedTotal;

    private LocalDate extractedDate;

    @Column(name = "image_url", length = 2048)
    private String imageUrl;

    @Column(nullable = false)
    private String status = ReceiptStatus.PENDING.name();

    /**
     * LEGACY, read-only. Where the build that introduced the lifecycle (Phase 1, never released) kept the answer of the
     * AI, overwriting it on every retry. Nothing writes it any more: no insert or update of the entity names the column,
     * and it is not audited. It is still read once, to import what it holds as a LEGACY attempt (the migration of
     * receipt_processing_attempts, or the first retry of a receipt that has no attempt yet); Transaction.rawReceiptData is
     * the older copy of the same kind. Plan: once the migration has run on every database, stop reading both and drop
     * the two columns in a task of their own.
     */
    @NotAudited
    @Setter(AccessLevel.NONE)
    @Column(name = "ai_raw_data", columnDefinition = "TEXT", insertable = false, updatable = false)
    private String aiRawData;

    /** Why the receipt is FAILED: the name of a {@link ReceiptErrorCode}. Null in every other status. */
    @Column(name = "error_code", length = ERROR_CODE_MAX_LENGTH)
    private String errorCode;

    /**
     * Optimistic lock: every update of the row raises it, and an update that was prepared from an older version changes
     * nothing (409). It backs the state checks up, it does not replace them.
     */
    @Version
    @Column(name = "version", nullable = false)
    @ColumnDefault("0")
    private Long version;

    @OneToOne(mappedBy = "receipt")
    private Transaction transaction;

    /**
     * Every reading of the receipt, oldest first. Lazy, and only ever appended to: the first reading of a new receipt is
     * saved with it through this list, and a later one is saved on its own (ReceiptProcessingAttemptRepository), so that
     * recording a reading never loads the earlier ones. Not audited (the attempts are a history of their own).
     */
    @NotAudited
    @OneToMany(mappedBy = "receipt", cascade = CascadeType.PERSIST)
    @OrderBy("attemptNumber ASC")
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private List<ReceiptProcessingAttempt> attempts = new ArrayList<>();

    /** For a receipt that is being built: the attempt of its first reading, inserted together with the receipt. */
    public void addFirstAttempt(ReceiptProcessingAttempt attempt) {
        if (id != null || !attempts.isEmpty()) {
            throw new IllegalStateException(MSG_NOT_A_NEW_RECEIPT);
        }
        attempts.add(attempt);
    }

    /** The readings of the receipt, oldest first. Loads them for a saved receipt: services query the repository instead. */
    public List<ReceiptProcessingAttempt> getAttempts() {
        return Collections.unmodifiableList(attempts);
    }

    /** The status as the lifecycle knows it; empty when the row holds a text that is none of its statuses. */
    public Optional<ReceiptStatus> lifecycleStatus() {
        return ReceiptStatus.fromStored(status);
    }

    /** The status under the name the clients that predate the lifecycle know (see {@link ReceiptStatus#legacyName}). */
    public String getLegacyStatus() {
        return ReceiptStatus.legacyNameOf(status);
    }

    /** True when the lifecycle knows a move from the current status to {@code target}. */
    public boolean canMoveTo(ReceiptStatus target) {
        return lifecycleStatus().filter(current -> current.canTransitionTo(target)).isPresent();
    }

    /**
     * The one way the status changes. A move the lifecycle does not know is refused, whoever asks; callers check
     * {@link #canMoveTo} first so that they can answer with a message of their own. An error code only describes a FAILED
     * receipt, so every other move clears it; use {@link #failWith} to fail a receipt.
     */
    public void moveTo(ReceiptStatus target) {
        if (!canMoveTo(target)) {
            throw new IllegalStateException(String.format(MSG_MOVE_NOT_ALLOWED, status, target));
        }
        this.status = target.name();
        if (target != ReceiptStatus.FAILED) {
            this.errorCode = null;
        }
    }

    /** Moves the receipt to FAILED and records why. */
    public void failWith(ReceiptErrorCode reason) {
        moveTo(ReceiptStatus.FAILED);
        this.errorCode = reason.name();
    }
}
