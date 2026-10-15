package vn.edu.huit.finance.core.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.NotificationPageResponseDto;
import vn.edu.huit.finance.core.Dto.NotificationResponseDto;
import vn.edu.huit.finance.core.Entity.Notification;
import vn.edu.huit.finance.core.Entity.NotificationType;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.NotificationNotFoundException;
import vn.edu.huit.finance.core.Repository.NotificationRepository;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Notifications of the authenticated caller. Reading and marking take no user id: the owner is resolved from the
 * SecurityContext. {@link #create} is for server-side callers (BudgetAlertService) and joins the transaction of whoever
 * calls it.
 */
@Slf4j
@Service
public class NotificationService {

    private static final String MSG_NOTIFICATION_NOT_FOUND = "Không tìm thấy thông báo";

    // Not injected: production uses the system clock, the same one the auditing listener fills updatedAt with; tests
    // replace it to control time.
    private Clock clock = Clock.systemDefaultZone();

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private CurrentUserProvider currentUserProvider;

    /** One page of the caller's notifications, newest first. The order is fixed here; the client only picks the page. */
    @Transactional(readOnly = true)
    public NotificationPageResponseDto getMyNotifications(int page, int size) {
        Long userId = currentUserProvider.getCurrentUser().getId();
        Page<Notification> notifications = notificationRepository.findNewestByUserId(userId, PageRequest.of(page, size));
        return NotificationPageResponseDto.from(notifications);
    }

    @Transactional(readOnly = true)
    public long countMyUnread() {
        return notificationRepository.countUnreadByUserId(currentUserProvider.getCurrentUser().getId());
    }

    /**
     * Marks one notification of the caller as read and returns it. A notification of another user gets the same 404 as
     * one that does not exist, so the answer never tells whether the id exists. Marking it again changes nothing.
     */
    @Transactional
    public NotificationResponseDto markAsRead(Long notificationId) {
        Long userId = currentUserProvider.getCurrentUser().getId();
        Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> {
                    log.warn("Notification to mark as read not found for the caller, userId={}, notificationId={}",
                            userId, notificationId);
                    return new NotificationNotFoundException(MSG_NOTIFICATION_NOT_FOUND);
                });

        if (!notification.isRead()) {
            notification.setRead(true);
            log.info("Notification marked as read, userId={}, notificationId={}", userId, notificationId);
        }
        return NotificationResponseDto.from(notification);
    }

    /** Marks every unread notification of the caller as read in one statement; returns how many changed from unread. */
    @Transactional
    public int markAllAsRead() {
        Long userId = currentUserProvider.getCurrentUser().getId();
        int marked = notificationRepository.markAllAsReadByUserId(userId, LocalDateTime.now(clock));
        log.info("Notifications marked as read in bulk, userId={}, count={}", userId, marked);
        return marked;
    }

    /**
     * The ids say what the notification is about and any of them may be null; a budget alert carries its budget and
     * the transaction that took the budget over the level.
     */
    @Transactional
    public void create(User user, NotificationType type, String message, Long transactionId, Long budgetId,
                       Long receiptId) {
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setType(type);
        notification.setMessage(message);
        notification.setTransactionId(transactionId);
        notification.setBudgetId(budgetId);
        notification.setReceiptId(receiptId);
        notificationRepository.save(notification);

        log.info("Notification created, userId={}, type={}, transactionId={}, budgetId={}, receiptId={}",
                user.getId(), type, transactionId, budgetId, receiptId);
    }
}
