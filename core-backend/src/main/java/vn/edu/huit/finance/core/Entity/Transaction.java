package vn.edu.huit.finance.core.Entity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.envers.Audited;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Audited
@Entity
@Table(name = "transactions", uniqueConstraints = @UniqueConstraint(
        name = "uk_transactions_user_client_id",
        columnNames = {"user_id", "client_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Transaction extends BaseEntity {

    /** Widest client id the column holds; the checkout request refuses a longer one. */
    public static final int CLIENT_ID_MAX_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private BigDecimal amount;

    private String note;

    @Column(name = "transaction_date", nullable = false)
    private LocalDateTime transactionDate;

    @Column(name = "is_expense", nullable = false)
    private boolean isExpense;

    @Column(name = "category_name")
    private String categoryName;

    @Column(name = "status")
    private String status;

    @Column(name = "voucher_code")
    private String voucherCode;

    @Column(columnDefinition = "TEXT")
    private String additionalImages;

    private String location;
    private String paymentMethod;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    @JsonIgnore
    private Category category;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id", referencedColumnName = "id")
    @JsonIgnore
    private Receipt receipt;

    /**
     * LEGACY, read-only. Where the checkout kept the answer of the AI before receipts had a lifecycle. The copy that counts
     * is the receipt's attempts now (ReceiptProcessingAttempt). The column stays, so that the older rows lose nothing,
     * but it is never written again, which is what keeps a second, diverging copy from appearing; what it holds is read
     * once, to import it as the receipt's first (LEGACY) attempt. Plan: see Receipt.aiRawData, retired together.
     */
    @Column(name = "raw_receipt_data", columnDefinition = "TEXT", insertable = false, updatable = false)
    private String rawReceiptData;

    /**
     * Idempotency key the client made up for one attempt to save this transaction, unique per user. Null when the
     * transaction was created without one (every older row, the payment webhook's transactions); a null is never a
     * duplicate of another null.
     */
    @Column(name = "client_id", length = CLIENT_ID_MAX_LENGTH)
    private String clientId;

}
