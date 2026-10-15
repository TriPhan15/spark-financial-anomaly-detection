package vn.edu.huit.finance.core.Controller;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.huit.finance.core.Dto.ApiResponse;
import vn.edu.huit.finance.core.Dto.NotificationPageRequestDto;
import vn.edu.huit.finance.core.Dto.NotificationPageResponseDto;
import vn.edu.huit.finance.core.Dto.NotificationResponseDto;
import vn.edu.huit.finance.core.Service.NotificationService;

/** The caller's own notifications. The owner is taken from the JWT inside NotificationService; nothing here reads a user id. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private static final String MSG_LIST_OK = "Lấy danh sách thông báo thành công";
    private static final String MSG_UNREAD_COUNT_OK = "Lấy số thông báo chưa đọc thành công";
    private static final String MSG_READ_ALL_OK = "Đã đánh dấu tất cả thông báo là đã đọc";
    private static final String MSG_READ_OK = "Đã đánh dấu thông báo là đã đọc";

    @Autowired
    private NotificationService notificationService;

    /** ?page=0&size=20; an invalid page or size is a 400 through the validation handler, and a sort parameter is ignored. */
    @GetMapping
    public ResponseEntity<ApiResponse<NotificationPageResponseDto>> getMyNotifications(
            @Valid @ModelAttribute NotificationPageRequestDto pageRequest) {
        return ApiResponses.respond(HttpStatus.OK, MSG_LIST_OK,
                notificationService.getMyNotifications(pageRequest.getPage(), pageRequest.getSize()));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Long>> countMyUnread() {
        return ApiResponses.respond(HttpStatus.OK, MSG_UNREAD_COUNT_OK, notificationService.countMyUnread());
    }

    // A literal path of one segment; /{id}/read has two, so neither mapping can take the other's requests.
    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<Integer>> markAllAsRead() {
        return ApiResponses.respond(HttpStatus.OK, MSG_READ_ALL_OK, notificationService.markAllAsRead());
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<ApiResponse<NotificationResponseDto>> markAsRead(@PathVariable Long id) {
        return ApiResponses.respond(HttpStatus.OK, MSG_READ_OK, notificationService.markAsRead(id));
    }
}
