package vn.edu.huit.finance.core.Dto;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.domain.Page;
import vn.edu.huit.finance.core.Entity.Notification;

import java.util.List;

/** One page of GET /api/notifications: the notifications as DTOs plus what the app needs to ask for the next page. */
@Data
@Builder
public class NotificationPageResponseDto {
    private List<NotificationResponseDto> content;
    /** Zero-based, as requested. */
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean hasNext;

    public static NotificationPageResponseDto from(Page<Notification> page) {
        return NotificationPageResponseDto.builder()
                .content(page.getContent().stream().map(NotificationResponseDto::from).toList())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .hasNext(page.hasNext())
                .build();
    }
}
