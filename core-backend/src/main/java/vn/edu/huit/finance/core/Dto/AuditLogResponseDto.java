package vn.edu.huit.finance.core.Dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.edu.huit.finance.core.Entity.ActionLog;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.AuditResult;
import vn.edu.huit.finance.core.Entity.AuditTargetType;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;

/**
 * One action_logs row as administrators see it; the only mapping of that entity. A row stored with a legacy code is
 * shown under the action that replaced it, with the stored code kept next to it; a code no action knows is shown as it
 * is. The result of a row written before the result column existed is the one its action has.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponseDto {
    private Long id;
    private LocalDateTime timestamp;
    /** The account that acted; null for the system and for a caller that was not signed in. */
    private Long actorUserId;
    private String actorEmail;
    /** The name of the action, also for a row stored with a legacy code; the stored code when no action knows it. */
    private String action;
    /** The code exactly as stored. */
    private String storedAction;
    private String actionLabel;
    /** SECURITY, DATA or SYSTEM; null for a code no action knows. */
    private String category;
    private String targetType;
    /** How the page names the kind of record: the stored target type, or for an older row the one its action is about. */
    private String targetLabel;
    private Long targetId;
    /** SUCCESS or FAILURE; null when neither the row nor its action tells. */
    private String result;
    private String description;
    private String ipAddress;

    public static AuditLogResponseDto from(ActionLog row) {
        Optional<AuditAction> action = AuditAction.fromStored(row.getAction());
        Optional<AuditTargetType> targetType = Arrays.stream(AuditTargetType.values())
                .filter(type -> type.name().equals(row.getTargetType()))
                .findFirst()
                .or(() -> action.map(AuditAction::getTargetType));
        String result = row.getResult() != null
                ? row.getResult()
                : action.map(AuditAction::getResult).map(AuditResult::name).orElse(null);
        return AuditLogResponseDto.builder()
                .id(row.getId())
                .timestamp(row.getTimestamp())
                .actorUserId(row.getUserId())
                .actorEmail(row.getUserEmail())
                .action(action.map(AuditAction::name).orElse(row.getAction()))
                .storedAction(row.getAction())
                .actionLabel(action.map(AuditAction::getLabel).orElse(row.getAction()))
                .category(action.map(found -> found.getCategory().name()).orElse(null))
                .targetType(row.getTargetType())
                .targetLabel(targetType.map(AuditTargetType::getLabel).orElse(row.getTargetType()))
                .targetId(row.getTargetId())
                .result(result)
                .description(row.getDescription())
                .ipAddress(row.getIpAddress())
                .build();
    }
}
