package vn.edu.huit.finance.core.Entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.envers.Audited;

/**
 * A message for one user, created by the server (today: a budget reached 80% or 100%). The creation time is in BaseEntity.
 * The first index serves the newest-first listing, the second the unread count and marking everything as read.
 */
@Audited
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notifications_user_created", columnList = "user_id, created_at desc, id desc"),
        @Index(name = "idx_notifications_user_read_created", columnList = "user_id, is_read, created_at desc, id desc")})
@Getter
@Setter
@NoArgsConstructor
public class Notification extends BaseEntity {

    public static final int MESSAGE_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    /** Ready to show, in Vietnamese; the figures in it are the ones at the moment the notification was raised. */
    @Column(nullable = false, length = MESSAGE_MAX_LENGTH)
    private String message;

    /**
     * False when created, true once the owner has read it. The database default gives the rows that existed before the
     * column the same "unread" value, whether the column is added by the migration script or by ddl-auto=update.
     */
    @ColumnDefault("false")
    @Column(name = "is_read", nullable = false)
    private boolean isRead;

    /**
     * The transaction the notification is about; for a budget alert, the one that took the budget over the level.
     * A plain id like {@link #budgetId}: deleting the transaction never has to touch its notifications.
     */
    @Column(name = "transaction_id")
    private Long transactionId;

    /**
     * The budget behind a budget notification. A plain id and not a relation, so deleting a budget never has to touch
     * the notifications it raised; the app may find that the budget is gone.
     */
    @Column(name = "budget_id")
    private Long budgetId;

    /** The receipt the notification is about; a plain id like {@link #budgetId}. */
    @Column(name = "receipt_id")
    private Long receiptId;
}
