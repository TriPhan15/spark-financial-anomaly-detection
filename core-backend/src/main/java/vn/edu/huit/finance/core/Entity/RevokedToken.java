package vn.edu.huit.finance.core.Entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One JWT that was revoked before it expired (logout, password change). Not audited on purpose: the table is itself an
 * append-only log, and rows are purged in bulk once the token they name would have expired anyway.
 */
@Entity
@Table(name = "revoked_tokens", indexes = @Index(name = "idx_revoked_tokens_expires_at", columnList = "expires_at"))
@Getter
@Setter
@NoArgsConstructor
public class RevokedToken {

    /** Length of a SHA-256 digest written in hexadecimal. */
    public static final int TOKEN_HASH_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 of the token, never the token itself: a leaked table must not hand out usable credentials. */
    @Column(name = "token_hash", nullable = false, unique = true, length = TOKEN_HASH_LENGTH)
    private String tokenHash;

    /** When the token would have expired anyway; from then on the row is useless and gets purged. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at", nullable = false)
    private LocalDateTime revokedAt;

    public RevokedToken(String tokenHash, LocalDateTime expiresAt, LocalDateTime revokedAt) {
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
    }
}
