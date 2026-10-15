package vn.edu.huit.finance.core.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.huit.finance.core.Entity.Notification;

import java.time.LocalDateTime;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // n.user.id compares the user_id column, no join, and the DTO reads nothing lazy: a page is one query, plus the count
    // query when the page comes back full. The order is fixed here and the Pageable must be unsorted; the id breaks ties
    // between notifications raised in the same instant.
    @Query(value = "select n from Notification n where n.user.id = :userId order by n.createdAt desc, n.id desc",
            countQuery = "select count(n) from Notification n where n.user.id = :userId")
    Page<Notification> findNewestByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query("select count(n) from Notification n where n.user.id = :userId and n.isRead = false")
    long countUnreadByUserId(@Param("userId") Long userId);

    /** Empty both when the id does not exist and when it belongs to someone else, so the two look the same. */
    @Query("select n from Notification n where n.id = :id and n.user.id = :userId")
    Optional<Notification> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * One bulk UPDATE of the user's unread notifications; needs the caller's transaction and returns how many rows it
     * changed. Pending changes are flushed before it and the persistence context is cleared after it, so no Notification
     * loaded earlier in the transaction keeps a stale isRead. A bulk update bypasses the auditing listener, hence
     * updatedAt in the statement.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.isRead = true, n.updatedAt = :readAt where n.user.id = :userId and n.isRead = false")
    int markAllAsReadByUserId(@Param("userId") Long userId, @Param("readAt") LocalDateTime readAt);
}
