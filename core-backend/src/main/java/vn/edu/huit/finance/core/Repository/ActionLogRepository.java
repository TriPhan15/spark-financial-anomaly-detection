package vn.edu.huit.finance.core.Repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import vn.edu.huit.finance.core.Entity.ActionLog;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Reads of the activity log are always bounded: a page with its sort (the specification search of the admin audit API,
 * with a count), a limited list of the newest rows, or an aggregate over a time window. ActionLog has no relation, so
 * none of them can load anything lazily afterwards. Rows are only ever inserted, through AuditLogService.
 */
@Repository
public interface ActionLogRepository extends JpaRepository<ActionLog, Long>, JpaSpecificationExecutor<ActionLog> {

    /** The newest rows whose code is one of {@code codes}, as many and in the order {@code pageable} asks; no count query. */
    List<ActionLog> findByActionIn(Collection<String> codes, Pageable pageable);

    /** The newest rows whose code is none of {@code codes}, as many and in the order {@code pageable} asks; no count query. */
    List<ActionLog> findByActionNotIn(Collection<String> codes, Pageable pageable);

    /** Who did what how often since {@code since}: the most frequent first, as many groups as {@code pageable} asks. */
    @Query("SELECT a.userEmail AS actor, a.action AS action, COUNT(a.id) AS total, MAX(a.timestamp) AS lastAt "
            + "FROM ActionLog a WHERE a.timestamp >= :since "
            + "GROUP BY a.userEmail, a.action ORDER BY total DESC, lastAt DESC")
    List<AuditSummaryView> summarizeSince(@Param("since") LocalDateTime since, Pageable pageable);

    /** One group of {@link #summarizeSince}. */
    interface AuditSummaryView {
        String getActor();

        String getAction();

        Long getTotal();

        LocalDateTime getLastAt();
    }
}
