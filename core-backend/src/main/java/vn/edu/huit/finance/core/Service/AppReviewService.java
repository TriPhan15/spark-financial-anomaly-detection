package vn.edu.huit.finance.core.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Entity.AppReview;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Repository.AppReviewRepository;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;

import java.util.NoSuchElementException;

/**
 * Moderation of the reviews users leave about the app, for administrators only (the caller checks that). Each decision
 * and its audit row, naming the administrator, commit together.
 */
@Slf4j
@Service
public class AppReviewService {

    public static final String STATUS_APPROVED = "APPROVED";
    private static final String MSG_REVIEW_NOT_FOUND = "Không tìm thấy đánh giá";
    private static final String AUDIT_APPROVED = "Duyệt hiển thị đánh giá của: %s";
    private static final String AUDIT_DELETED = "Xóa đánh giá vi phạm của: %s";

    @Autowired
    private AppReviewRepository appReviewRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Transactional
    public void approveReview(Long reviewId) {
        AppReview review = findReview(reviewId);
        review.setStatus(STATUS_APPROVED);
        appReviewRepository.save(review);

        record(AuditAction.REVIEW_APPROVED, reviewId, String.format(AUDIT_APPROVED, review.getUserEmail()));
        log.info("Review approved, reviewId={}", reviewId);
    }

    @Transactional
    public void deleteReview(Long reviewId) {
        AppReview review = findReview(reviewId);
        appReviewRepository.delete(review);

        record(AuditAction.REVIEW_DELETED, reviewId, String.format(AUDIT_DELETED, review.getUserEmail()));
        log.info("Review deleted, reviewId={}", reviewId);
    }

    private AppReview findReview(Long reviewId) {
        return appReviewRepository.findById(reviewId).orElseThrow(() -> {
            log.warn("Review not found, reviewId={}", reviewId);
            return new NoSuchElementException(MSG_REVIEW_NOT_FOUND);
        });
    }

    /** By the administrator who called, looked up when the row is written. */
    private void record(AuditAction action, Long reviewId, String description) {
        auditLogService.record(AuditEntry.of(action, AuditActor.currentCaller(), reviewId, description));
    }
}
