package vn.edu.huit.finance.core.Repository;

import vn.edu.huit.finance.core.Entity.Receipt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {
    /** One receipt with the transaction that carries it and that transaction's owner in a single query, so an ownership check adds none. */
    @Query("SELECT r FROM Receipt r " +
           "LEFT JOIN FETCH r.transaction t " +
           "LEFT JOIN FETCH t.user " +
           "WHERE r.id = :id")
    Optional<Receipt> findDetailedById(@Param("id") Long id);

    /** The receipt a transaction carries, with that transaction and its owner, in a single query; empty when it carries none. */
    @Query("SELECT r FROM Receipt r " +
           "JOIN FETCH r.transaction t " +
           "JOIN FETCH t.user " +
           "WHERE t.id = :transactionId")
    Optional<Receipt> findDetailedByTransactionId(@Param("transactionId") Long transactionId);

    /**
     * Claims a FAILED receipt for a retry, in one statement: raises its version while it is still FAILED and still at the
     * version the caller read, and changes nothing else (the status stays FAILED until the reading is recorded, in the same
     * database transaction). The UPDATE locks the row until that transaction ends, so of two retries that arrive together
     * exactly one is answered 1; the other waits, finds the version changed once the first has committed and is answered
     * 0, or claims the row after all if the first rolled back. Any other write prepared from the old version (an edit, an
     * approval) fails its own version check. The status is compared the way ReceiptStatus reads it, so a row written by
     * hand as "failed" is found too. The persistence context is flushed before and cleared after: read the receipt again.
     *
     * @return 1 when this caller holds the receipt now, 0 when it was no longer FAILED or no longer at that version
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Receipt r SET r.version = r.version + 1 " +
           "WHERE r.id = :id AND r.version = :version " +
           "AND UPPER(TRIM(r.status)) = :#{T(vn.edu.huit.finance.core.Entity.ReceiptStatus).FAILED.name()}")
    int claimFailedForRetry(@Param("id") Long id, @Param("version") Long version);

    List<Receipt> findByStatus(String status);
    Page<Receipt> findAllByOrderByCreatedAtDesc(Pageable pageable);
    @org.springframework.data.jpa.repository.Query("SELECT r.status, COUNT(r) FROM Receipt r GROUP BY r.status")
    List<Object[]> getReceiptStatusStatistics();
}
