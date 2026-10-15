package vn.edu.huit.finance.core.Dto;

import lombok.Builder;
import lombok.Data;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.AuditTargetType;
import vn.edu.huit.finance.core.Repository.ActionLogRepository;

import java.time.LocalDateTime;
import java.util.Optional;

/** How often one actor performed one action within the summary window of the admin audit page. */
@Data
@Builder
public class AuditSummaryRowDto {
    private String actorEmail;
    /** The code as stored; old and new codes of one action are counted apart, as they were written. */
    private String action;
    private String actionLabel;
    /** The kind of record the action is about; null for an action about no single record or a code no action knows. */
    private String targetLabel;
    private long total;
    private LocalDateTime lastAt;

    public static AuditSummaryRowDto from(ActionLogRepository.AuditSummaryView group) {
        Optional<AuditAction> action = AuditAction.fromStored(group.getAction());
        return AuditSummaryRowDto.builder()
                .actorEmail(group.getActor())
                .action(group.getAction())
                .actionLabel(action.map(AuditAction::getLabel).orElse(group.getAction()))
                .targetLabel(action.map(AuditAction::getTargetType).map(AuditTargetType::getLabel).orElse(null))
                .total(group.getTotal() == null ? 0 : group.getTotal())
                .lastAt(group.getLastAt())
                .build();
    }
}
