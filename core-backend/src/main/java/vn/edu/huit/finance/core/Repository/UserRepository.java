package vn.edu.huit.finance.core.Repository;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import vn.edu.huit.finance.core.Entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    List<User> findByWalletBalanceLessThan(BigDecimal amount);

    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);
    @Query(value = "SELECT CAST(u.id AS VARCHAR) as userId, " +
            "COALESCE(EXTRACT(DAY FROM (NOW() - MAX(t.transaction_date))), 999) as recency, " +
            "COUNT(t.id) as frequency, " +
            "COALESCE(SUM(t.amount), 0) as monetary " +
            "FROM users u LEFT JOIN transactions t ON u.id = t.user_id " +
            "GROUP BY u.id", nativeQuery = true)
    List<Object[]> calculateAllUsersRFM();

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.aiLabel = :label WHERE u.id = :id")
    void updateUserAiLabel(@org.springframework.data.repository.query.Param("id") Long id, @org.springframework.data.repository.query.Param("label") String label);

    long countByAiLabel(String aiLabel);

    /**
     * Records the latest activity of a user: one UPDATE of last_action_time and of nothing else, so a copy of the user
     * read earlier can never write back its balance, profile, role, password or credentials version. Runs in its own
     * transaction (JwtAuthFilter has none). Returns the rows changed: 0 once the account is gone.
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.lastActionTime = :lastActionTime WHERE u.id = :userId")
    int updateLastActionTime(@org.springframework.data.repository.query.Param("userId") Long userId,
                             @org.springframework.data.repository.query.Param("lastActionTime") LocalDateTime lastActionTime);

    /**
     * Stores a new password hash and ends every session of the user, in ONE atomic UPDATE: the hash and
     * credentials_version + 1 are written together or not at all. The only write either column gets on an existing row
     * (neither is updatable through the entity). Matches only while the row still has {@code expectedVersion}, so it can
     * never be applied on top of a change it did not see: 0 rows changed means nothing was written. Needs the caller's
     * transaction, which also holds the row lock. Pending changes are flushed first, the persistence context is left as it
     * is. Returns the rows changed.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE User u SET u.password = :passwordHash, u.credentialsVersion = u.credentialsVersion + 1 "
            + "WHERE u.id = :id AND u.credentialsVersion = :expectedVersion")
    int updatePasswordAndEndSessions(@org.springframework.data.repository.query.Param("id") Long id,
                                     @org.springframework.data.repository.query.Param("passwordHash") String passwordHash,
                                     @org.springframework.data.repository.query.Param("expectedVersion") Integer expectedVersion);
}
