package vn.edu.huit.finance.core.Entity;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.envers.Audited;
import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Audited
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    /**
     * BCrypt hash. updatable = false: a save of this entity never writes it, so a copy read before a password change or
     * reset and saved after it (wallet, profile, last action time) cannot put the old hash back. Only
     * UserRepository.updatePasswordAndEndSessions changes it, together with the credentials version.
     */
    @Column(nullable = false, updatable = false)
    private String password;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false, unique = true)
    private String phone;

    @Column(nullable = false)
    private String role = "ROLE_USER";

    @Column(nullable = false)
    private boolean active = true;

    private LocalDateTime lastLoginTime;

    private LocalDateTime lastActionTime;
    @JsonIgnore
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude
    private List<Transaction> transactions;
    @Column(nullable = false)
    private BigDecimal walletBalance = BigDecimal.ZERO;
    @Column(name = "ai_label", length = 50)
    private String aiLabel;

    /**
     * Goes up by one when the password is changed or reset. Every JWT carries the value it was issued for, so the bump
     * ends every session opened before it, on every device. Deliberately not a JPA @Version, which every update of the
     * row (wallet, profile, last action time) would bump. updatable = false: a save of this entity never writes it, so a
     * copy read before the bump cannot put the old value back; UserRepository.updatePasswordAndEndSessions is its only
     * writer.
     */
    @Column(name = "credentials_version", nullable = false, updatable = false)
    @ColumnDefault("0")
    private Integer credentialsVersion = 0;

    /** True when a token issued for {@code tokenVersion} is still good: the password has not changed since. */
    public boolean hasCredentialsVersion(Integer tokenVersion) {
        return tokenVersion != null && tokenVersion.equals(credentialsVersion);
    }
}
