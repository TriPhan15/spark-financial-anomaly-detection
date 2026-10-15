package vn.edu.huit.finance.core.Entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * One row of the application activity log: who did what to which record, from where, when, and with which outcome.
 * Written only by AuditLogService and never changed afterwards (immutable: Hibernate ignores any attempt to update a row).
 * It is not the Hibernate Envers history of the entities (the *_aud tables): this log names the business action, also
 * where Envers sees nothing (bulk updates such as a password change, refused requests, operations outside the database).
 * <p>
 * The text columns hold no secret: no password or hash, OTP, token, raw AI answer or request body. The indexes serve the
 * admin audit page and API, which always list the newest rows first (timestamp, then id, both descending).
 */
@Entity
@Immutable
@Table(name = "action_logs", indexes = {
        @Index(name = "idx_action_logs_timestamp", columnList = "timestamp desc, id desc"),
        @Index(name = "idx_action_logs_action_timestamp", columnList = "action, timestamp desc, id desc"),
        @Index(name = "idx_action_logs_user_id_timestamp", columnList = "user_id, timestamp desc, id desc"),
        @Index(name = "idx_action_logs_user_email_timestamp", columnList = "user_email, timestamp desc, id desc"),
        @Index(name = "idx_action_logs_target_timestamp", columnList = "target_type, target_id, timestamp desc, id desc")})
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ActionLog {

    public static final int USER_EMAIL_MAX_LENGTH = 255;
    public static final int DESCRIPTION_MAX_LENGTH = 1000;
    public static final int TARGET_TYPE_MAX_LENGTH = 32;
    public static final int RESULT_MAX_LENGTH = 16;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The account that acted; null for the system and for a caller that was not signed in. */
    @Column(name = "user_id")
    private Long userId;

    /** The email of the account that acted, the identity an unauthenticated caller claimed, or the system's label. */
    @Column(name = "user_email", length = USER_EMAIL_MAX_LENGTH)
    private String userEmail;

    /** The name of an {@link AuditAction}, or a legacy code in rows written before those names existed. */
    @Column(name = "action")
    private String action;

    /** The name of an {@link AuditTargetType}; set together with {@link #targetId}, null for rows about no single record. */
    @Column(name = "target_type", length = TARGET_TYPE_MAX_LENGTH)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    /** The name of an {@link AuditResult}; null in rows written before the column existed. */
    @Column(name = "result", length = RESULT_MAX_LENGTH)
    private String result;

    @Column(name = "description", length = DESCRIPTION_MAX_LENGTH)
    private String description;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "timestamp")
    private LocalDateTime timestamp;
}
