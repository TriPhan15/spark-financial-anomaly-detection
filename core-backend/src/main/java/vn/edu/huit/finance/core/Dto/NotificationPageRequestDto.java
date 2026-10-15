package vn.edu.huit.finance.core.Dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * The query parameters of GET /api/notifications. There is deliberately no sort (the server always lists the newest
 * first) and no user: the owner is always the caller. A parameter left out keeps its default.
 */
@Getter
@Setter
public class NotificationPageRequestDto {

    public static final int FIRST_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 50;

    @Min(value = FIRST_PAGE, message = "Số trang phải lớn hơn hoặc bằng 0")
    private int page = FIRST_PAGE;

    @Min(value = MIN_SIZE, message = "Số thông báo mỗi trang phải từ 1 đến 50")
    @Max(value = MAX_SIZE, message = "Số thông báo mỗi trang phải từ 1 đến 50")
    private int size = DEFAULT_SIZE;
}
