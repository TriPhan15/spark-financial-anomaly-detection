package vn.edu.huit.finance.core.Dto;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.domain.Page;
import vn.edu.huit.finance.core.Entity.ActionLog;

import java.util.List;

/** One page of an audit log search: the rows as DTOs, newest first, plus what is needed to ask for the next page. */
@Data
@Builder
public class AuditLogPageResponseDto {
    private List<AuditLogResponseDto> content;
    /** Zero-based, as requested. */
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean hasNext;
    private boolean hasPrevious;

    public static AuditLogPageResponseDto from(Page<ActionLog> page) {
        return AuditLogPageResponseDto.builder()
                .content(page.getContent().stream().map(AuditLogResponseDto::from).toList())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .build();
    }
}
