package vn.edu.huit.finance.core.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.huit.finance.core.Entity.ReceiptProcessingAttempt;

import java.util.List;

/** The readings of receipts. Insert and read only: an attempt is never updated, and it is deleted only with its receipt. */
public interface ReceiptProcessingAttemptRepository extends JpaRepository<ReceiptProcessingAttempt, Long> {

    /** True when the receipt has recorded the reading of the retry that carried this idempotency key. */
    boolean existsByReceiptIdAndClientId(Long receiptId, String clientId);

    /** The number of the receipt's latest reading; 0 when it has none yet. */
    @Query("SELECT COALESCE(MAX(a.attemptNumber), 0) FROM ReceiptProcessingAttempt a WHERE a.receipt.id = :receiptId")
    int findLastAttemptNumber(@Param("receiptId") Long receiptId);

    /** Every reading of the receipt, oldest first. */
    List<ReceiptProcessingAttempt> findByReceiptIdOrderByAttemptNumberAsc(Long receiptId);
}
