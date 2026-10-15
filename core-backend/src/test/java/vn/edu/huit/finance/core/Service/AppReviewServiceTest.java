package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.edu.huit.finance.core.Entity.AppReview;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Repository.AppReviewRepository;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Moderation of app reviews: each decision with its row, by the administrator who called. */
@ExtendWith(MockitoExtension.class)
class AppReviewServiceTest {

    @Mock private AppReviewRepository repository;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private AppReviewService service;

    private AppReview review() {
        AppReview review = new AppReview();
        review.setId(4L);
        review.setUserEmail("alice@example.com");
        review.setComment("Tuyệt vời <b>");
        when(repository.findById(4L)).thenReturn(Optional.of(review));
        return review;
    }

    private AuditEntry recorded() {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        return entry.getValue();
    }

    @Test
    void anApprovalIsRecordedWithTheReviewer_notTheComment() {
        AppReview review = review();

        service.approveReview(4L);

        assertEquals(AppReviewService.STATUS_APPROVED, review.getStatus());
        AuditEntry entry = recorded();
        assertEquals(AuditAction.REVIEW_APPROVED, entry.action());
        assertEquals(AuditActor.currentCaller(), entry.actor());
        assertEquals(4L, entry.targetId());
        assertEquals("Duyệt hiển thị đánh giá của: alice@example.com", entry.description());
        assertFalse(entry.description().contains("Tuyệt vời"));
    }

    @Test
    void aDeletionIsRecorded() {
        AppReview review = review();

        service.deleteReview(4L);

        verify(repository).delete(review);
        assertEquals(AuditAction.REVIEW_DELETED, recorded().action());
    }

    @Test
    void aReviewThatDoesNotExistIsRefused_andNothingIsRecorded() {
        when(repository.findById(5L)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.approveReview(5L));

        verifyNoInteractions(auditLogService);
    }
}
