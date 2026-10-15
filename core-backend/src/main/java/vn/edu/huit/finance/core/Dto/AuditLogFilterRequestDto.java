package vn.edu.huit.finance.core.Dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.AuditTargetType;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The query parameters of an audit log search (GET /api/admin/audit-logs and the search tab of /admin/auditlog). Every
 * filter is optional and they all apply together; the order is fixed (newest first) and a page holds at most
 * {@value #MAX_SIZE} rows. A parameter left out keeps its default.
 */
@Getter
@Setter
public class AuditLogFilterRequestDto {

    public static final int FIRST_PAGE = 0;
    public static final int MAX_PAGE = 10_000;
    public static final int DEFAULT_SIZE = 50;
    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 100;
    public static final int EMAIL_MAX_LENGTH = 255;

    /** An action code: the name of an {@link AuditAction} or one of its legacy codes; the rows of both are found. */
    private String action;

    /** The account that acted. */
    @Positive(message = "Mã người dùng phải lớn hơn 0")
    private Long userId;

    /** The email (or claimed identity) the rows name as the actor, exactly as stored. */
    @Size(max = EMAIL_MAX_LENGTH, message = "Email không được dài quá 255 ký tự")
    private String email;

    /** The name of an {@link AuditTargetType}; may be given alone or with {@link #targetId}. */
    private String targetType;

    @Positive(message = "Mã đối tượng phải lớn hơn 0")
    private Long targetId;

    /** Inclusive. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime from;

    /** Inclusive. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime to;

    @Min(value = FIRST_PAGE, message = "Số trang phải từ 0 đến 10000")
    @Max(value = MAX_PAGE, message = "Số trang phải từ 0 đến 10000")
    private int page = FIRST_PAGE;

    @Min(value = MIN_SIZE, message = "Số dòng mỗi trang phải từ 1 đến 100")
    @Max(value = MAX_SIZE, message = "Số dòng mỗi trang phải từ 1 đến 100")
    private int size = DEFAULT_SIZE;

    @AssertTrue(message = "Mã hành động không hợp lệ")
    public boolean isActionKnown() {
        return isBlank(action) || AuditAction.fromStored(action).isPresent();
    }

    @AssertTrue(message = "Loại đối tượng không hợp lệ")
    public boolean isTargetTypeKnown() {
        return isBlank(targetType) || targetTypeFilter().isPresent();
    }

    @AssertTrue(message = "Mã đối tượng phải đi kèm loại đối tượng")
    public boolean isTargetComplete() {
        return targetId == null || !isBlank(targetType);
    }

    @AssertTrue(message = "Thời điểm bắt đầu phải trước thời điểm kết thúc")
    public boolean isTimeRangeOrdered() {
        return from == null || to == null || !from.isAfter(to);
    }

    /** The action asked for, if any. */
    public Optional<AuditAction> actionFilter() {
        return AuditAction.fromStored(action);
    }

    /** The kind of target asked for, if any. */
    public Optional<AuditTargetType> targetTypeFilter() {
        if (isBlank(targetType)) {
            return Optional.empty();
        }
        String name = targetType.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(AuditTargetType.values()).filter(type -> type.name().equals(name)).findFirst();
    }

    /** The email asked for, trimmed; empty when none was. */
    public Optional<String> emailFilter() {
        return isBlank(email) ? Optional.empty() : Optional.of(email.trim());
    }

    /** True when a filter or a page other than the first was asked for: the admin page then opens on its search. */
    public boolean hasCriteria() {
        return !isBlank(action) || userId != null || !isBlank(email) || !isBlank(targetType) || targetId != null
                || from != null || to != null || page != FIRST_PAGE;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
