package vn.edu.huit.finance.core.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.huit.finance.core.Entity.RevokedToken;

import java.time.LocalDateTime;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, Long> {

    /** Runs for every authenticated request: token_hash is unique, so this is one index lookup. */
    boolean existsByTokenHash(String tokenHash);

    /** One bulk DELETE of the rows that expired before the cutoff; needs the caller's transaction. Returns how many went. */
    @Modifying
    @Query("delete from RevokedToken r where r.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
